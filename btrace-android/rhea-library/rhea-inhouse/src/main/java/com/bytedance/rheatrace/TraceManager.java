/*
 * Copyright (C) 2021 ByteDance Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.bytedance.rheatrace;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import com.bytedance.rheatrace.server.HttpServer;
import com.bytedance.rheatrace.prop.TraceProperties;
import com.bytedance.rheatrace.trace.TraceAbilityCenter;
import com.bytedance.rheatrace.trace.base.TraceAbility;
import com.bytedance.rheatrace.trace.base.TraceGlobal;
import com.bytedance.rheatrace.trace.base.TraceMeta;
import com.bytedance.rheatrace.utils.HandlerThreadUtils;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.ArrayList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;


public class TraceManager {

    private static final String TAG = "RheaTrace:Manager";
    private static final String ONLINE_ARTIFACT_SUFFIX = ".rheajank.zip";
    private static final String ONLINE_TEMP_SUFFIX = ".tmp";
    private static final long ONLINE_TEMP_TTL_MS = 24L * 60L * 60L * 1000L;
    private static final int SAMPLING_RECORD_MEMORY_ESTIMATE = 2304;

    private enum Mode {
        NONE,
        DEBUG,
        ONLINE
    }

    private String tracingDirPath;
    private long[] traceTokens = null;
    private Mode mode = Mode.NONE;
    private RheaTrace3.OnlineConfig onlineConfig;
    private File onlineDir;
    private String onlineAppName = "";
    private long onlineStartToken;
    private volatile boolean onlineEnabled;
    private volatile boolean onlineForeground = true;
    private volatile boolean onlineDumpBusy;
    private long lastOnlineDumpUptimeMs = Long.MIN_VALUE;
    private Application lifecycleApplication;
    private Application.ActivityLifecycleCallbacks lifecycleCallbacks;

    public static TraceManager getInstance() {
        return Holder.INSTANCE;
    }

    public synchronized void init(Context context) {
        if (mode == Mode.ONLINE) {
            Log.w(TAG, "debug init ignored because online mode is already active");
            return;
        }
        if (mode == Mode.NONE) {
            mode = Mode.DEBUG;
        }
        if (TraceProperties.shouldStartWhenAppLaunch()) {
            startTracing(false);
        }
        tracingDirPath = context.getFilesDir().getAbsolutePath() + "/rhea/tracing/" + Process.myPid();
        Thread serverThread = new Thread(() -> HttpServer.start(context.getExternalFilesDir(null), tracingDirPath));
        serverThread.start();
    }

    /** 初始化常驻在线采集。在线模式不启动调试 HTTP 服务。 */
    public synchronized RheaTrace3.OnlineInitResult initOnline(Context context,
                                                                RheaTrace3.OnlineConfig config) {
        if (context == null || config == null) {
            return RheaTrace3.OnlineInitResult.INVALID_CONFIG;
        }
        if (!config.isEnabled()) {
            return RheaTrace3.OnlineInitResult.DISABLED;
        }
        if (mode == Mode.DEBUG) {
            return RheaTrace3.OnlineInitResult.MODE_CONFLICT;
        }
        if (mode == Mode.ONLINE) {
            return RheaTrace3.OnlineInitResult.ALREADY_STARTED;
        }
        mode = Mode.ONLINE;
        onlineConfig = config;
        onlineEnabled = config.isEnabled();
        Context appContext = context.getApplicationContext() == null
                ? context : context.getApplicationContext();
        onlineAppName = appContext.getPackageName();
        onlineDir = new File(appContext.getNoBackupFilesDir(), "rhea/jank");
        if (!makeDumpDir(onlineDir.getAbsolutePath())) {
            resetOnlineState();
            return RheaTrace3.OnlineInitResult.NATIVE_INIT_FAILED;
        }
        cleanupOnlineArtifacts();
        if (!TraceGlobal.init() || !startTracing(false)) {
            resetOnlineState();
            return RheaTrace3.OnlineInitResult.NATIVE_INIT_FAILED;
        }
        TraceAbility<?> ability = getSamplingAbility();
        onlineStartToken = ability == null ? 0 : ability.mark();
        applyOnlineNativeState();
        registerLifecycle(appContext);
        return RheaTrace3.OnlineInitResult.STARTED;
    }

    public static boolean isOnlineMode() {
        return getInstance().mode == Mode.ONLINE;
    }

    public static int getOnlineBufferSizeBytes() {
        RheaTrace3.OnlineConfig config = getInstance().onlineConfig;
        return config == null ? 0 : config.getBufferSizeBytes();
    }

    public static long getOnlineSampleIntervalNs() {
        RheaTrace3.OnlineConfig config = getInstance().onlineConfig;
        return config == null ? 0 : config.getMinSampleIntervalNs();
    }

    /** 将配置的字节数换算为 native 记录数；双缓冲的内存估算已包含在常量中。 */
    public static int getOnlineBufferCapacityRecords() {
        int bytes = getOnlineBufferSizeBytes();
        if (bytes <= 0) {
            return 256;
        }
        return Math.max(256, Math.min(8192, bytes / SAMPLING_RECORD_MEMORY_ESTIMATE));
    }

    public synchronized void setOnlineTracingEnabled(boolean enabled) {
        if (mode != Mode.ONLINE) {
            return;
        }
        onlineEnabled = enabled;
        applyOnlineNativeState();
    }

    public synchronized boolean startTracing(boolean async) {
        if (traceTokens != null) {
            Log.e(TAG, "start failed: already started and not yet stopped");
            return false;
        }
        if (!TraceGlobal.init()) {
            Log.e(TAG, "start failed: global dependency init failed");
            return false;
        }
        List<TraceMeta> traceMetas = requireTraceMetas();
        if (traceMetas.isEmpty()) {
            return false;
        }
        if (async) {
            Thread startThread = new Thread(() -> {
                List<TraceAbility<?>> traceAbilities = TraceAbilityCenter.getAbilities(traceMetas);
                long[] tokens = new long[traceMetas.size()];
                for (int i = 0; i < traceMetas.size(); i++) {
                    tokens[i] = traceAbilities.get(i).start();
                }
                this.traceTokens = tokens;
            });
            startThread.start();
        } else {
            List<TraceAbility<?>> traceAbilities = TraceAbilityCenter.getAbilities(traceMetas);
            long[] tokens = new long[traceMetas.size()];
            for (int i = 0; i < traceMetas.size(); i++) {
                tokens[i] = traceAbilities.get(i).start();
            }
            this.traceTokens = tokens;
        }
        return true;
    }

    public boolean stopTracing() {
        long[] startTokens = this.traceTokens;
        traceTokens = null;
        if (startTokens == null) {
            Log.e(TAG, "stop failed: no start tokens");
            return false;
        }

        List<TraceMeta> traceMetas = requireTraceMetas();
        List<TraceAbility<?>> traceAbilities = TraceAbilityCenter.getAbilities(traceMetas);
        long[] endTokens = new long[traceMetas.size()];
        for (int i = 0; i < traceMetas.size(); i++) {
            endTokens[i] = traceAbilities.get(i).stop();
        }
        HandlerThreadUtils.getCollectorThreadHandler().post(() -> {
            JSONObject extra = getExtra();
            String extraStr = extra == null ? "" : extra.toString();
            String path = getDumpPath();
            if (!makeDumpDir(path)) {
                Log.e(TAG, "make dump dir failed: " + path);
                HttpServer.getServer().onTraceDumpFinished(-100, getDumpPath(), traceMetas, startTokens, endTokens);
                return;
            }
            for (int i = 0; i < traceMetas.size(); i++) {
                long startToken = startTokens[i];
                long endToken = endTokens[i];
                TraceAbility<?> ability = traceAbilities.get(i);
                int result = ability.dumpTokenRange(startToken, endToken, path, extraStr);
                if (result != 0) {
                    Log.e(TAG, "dumping failed for " + traceMetas.get(i).getName() + ", error code is " + result);
                }
            }
            HttpServer.getServer().onTraceDumpFinished(0, getDumpPath(), traceMetas, startTokens, endTokens);
        });
        return true;
    }

    public synchronized RheaTrace3.DumpRequestResult dumpJankTrace(
            RheaTrace3.JankEvent event, RheaTrace3.DumpCallback callback) {
        if (mode != Mode.ONLINE || onlineConfig == null || traceTokens == null) {
            return RheaTrace3.DumpRequestResult.NOT_INITIALIZED;
        }
        if (!onlineConfig.isEnabled() || !onlineEnabled) {
            return RheaTrace3.DumpRequestResult.DISABLED;
        }
        if (onlineConfig.isForegroundOnly() && !onlineForeground) {
            return RheaTrace3.DumpRequestResult.BACKGROUND;
        }
        long now = SystemClock.uptimeMillis();
        if (lastOnlineDumpUptimeMs != Long.MIN_VALUE
                && now - lastOnlineDumpUptimeMs < onlineConfig.getDumpCooldownMs()) {
            return RheaTrace3.DumpRequestResult.COOLDOWN;
        }
        if (onlineDumpBusy) {
            return RheaTrace3.DumpRequestResult.BUSY;
        }
        onlineDumpBusy = true;
        lastOnlineDumpUptimeMs = now;
        HandlerThreadUtils.getCollectorThreadHandler().post(() -> dumpOnlineArtifact(event, callback));
        return RheaTrace3.DumpRequestResult.ACCEPTED;
    }

    private void dumpOnlineArtifact(RheaTrace3.JankEvent event, RheaTrace3.DumpCallback callback) {
        File artifact = null;
        String message = "";
        File tempDir = null;
        try {
            if (onlineDir == null || onlineConfig == null) {
                throw new IOException("online collector is not initialized");
            }
            cleanupOnlineArtifacts();
            tempDir = new File(onlineDir, event.getEventId() + ONLINE_TEMP_SUFFIX);
            deleteRecursively(tempDir);
            if (!tempDir.mkdirs()) {
                throw new IOException("cannot create temporary dump directory");
            }
            TraceAbility<?> ability = getSamplingAbility();
            long endToken = ability == null ? 0 : ability.mark();
            if (ability == null || endToken <= onlineStartToken) {
                throw new IOException("sampling buffer is empty");
            }
            JSONObject extra = getExtra(event);
            int result = ability.dumpTokenRange(onlineStartToken, endToken,
                    tempDir.getAbsolutePath(), extra == null ? "" : extra.toString());
            if (result != 0) {
                throw new IOException("native dump failed with code " + result);
            }
            File sampling = new File(tempDir, "sampling");
            File mapping = new File(tempDir, "sampling-mapping");
            if (!sampling.isFile() || !mapping.isFile() || sampling.length() == 0) {
                throw new IOException("native dump files are missing");
            }
            boolean truncated = endToken - onlineStartToken > getOnlineBufferCapacityRecords();
            JSONObject manifest = buildManifest(event, sampling, mapping, truncated);
            File manifestFile = new File(tempDir, "manifest.json");
            writeUtf8(manifestFile, manifest.toString());
            File zipTemp = new File(onlineDir, event.getEventId() + ONLINE_ARTIFACT_SUFFIX + ONLINE_TEMP_SUFFIX);
            File zipFinal = new File(onlineDir, event.getEventId() + ONLINE_ARTIFACT_SUFFIX);
            if (zipTemp.exists()) {
                zipTemp.delete();
            }
            zipFiles(zipTemp, manifestFile, sampling, mapping);
            if (zipFinal.exists() && !zipFinal.delete()) {
                throw new IOException("cannot replace existing artifact");
            }
            if (!zipTemp.renameTo(zipFinal)) {
                throw new IOException("cannot commit artifact atomically");
            }
            artifact = zipFinal;
            cleanupOnlineArtifacts();
            if (!artifact.isFile()) {
                artifact = null;
                throw new IOException("artifact exceeds online disk quota");
            }
            message = "ok";
            notifyDump(callback, new RheaTrace3.DumpResult(
                    RheaTrace3.DumpStatus.SUCCESS, event.getEventId(), artifact, message));
        } catch (Throwable throwable) {
            message = throwable.getMessage() == null ? throwable.getClass().getSimpleName()
                    : throwable.getMessage();
            Log.e(TAG, "online dump failed", throwable);
            notifyDump(callback, new RheaTrace3.DumpResult(
                    RheaTrace3.DumpStatus.FAILED, event.getEventId(), artifact, message));
        } finally {
            deleteRecursively(tempDir);
            if (onlineDir != null) {
                new File(onlineDir, event.getEventId() + ONLINE_ARTIFACT_SUFFIX + ONLINE_TEMP_SUFFIX).delete();
            }
            onlineDumpBusy = false;
        }
    }

    public synchronized List<File> getPendingJankTraces() {
        if (onlineDir == null || !onlineDir.isDirectory()) {
            return Collections.emptyList();
        }
        cleanupOnlineArtifacts();
        File[] files = onlineDir.listFiles((dir, name) -> name.endsWith(ONLINE_ARTIFACT_SUFFIX));
        if (files == null || files.length == 0) {
            return Collections.emptyList();
        }
        List<File> result = new ArrayList<>();
        Collections.addAll(result, files);
        Collections.sort(result, Comparator.comparingLong(File::lastModified));
        return result;
    }

    public synchronized boolean deleteJankTrace(File artifact) {
        if (artifact == null || onlineDir == null) {
            return false;
        }
        try {
            File parent = onlineDir.getCanonicalFile();
            File target = artifact.getCanonicalFile();
            File targetParent = target.getParentFile();
            if (targetParent == null || !parent.equals(targetParent)
                    || !target.getName().endsWith(ONLINE_ARTIFACT_SUFFIX)) {
                return false;
            }
            return target.delete();
        } catch (IOException e) {
            return false;
        }
    }

    public synchronized void stopOnlineTracing() {
        if (mode != Mode.ONLINE) {
            return;
        }
        TraceAbility<?> ability = getSamplingAbility();
        if (ability != null && traceTokens != null) {
            ability.stop();
        }
        traceTokens = null;
        TraceGlobal.setOnlineEnabled(false);
        unregisterLifecycle();
        resetOnlineState();
    }

    public void clearAfterTracing() {
        File directory = new File(tracingDirPath);
        if (directory.exists() && directory.isDirectory()) {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    file.delete();
                }
            }
            directory.delete();
        }
    }

    private List<TraceMeta> requireTraceMetas() {
        // currently there is only sampling trace data, we will extend it later
        return Collections.singletonList(TraceMeta.Sampling);
    }

    private String getDumpPath() {
        File directory = new File(tracingDirPath);
        if (!directory.exists()) {
            directory.mkdirs();
        }
        return tracingDirPath;
    }

    private boolean makeDumpDir(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        File directory = new File(path);
        if (!directory.exists()) {
            return directory.mkdirs();
        }
        return directory.isDirectory();
    }

    private JSONObject getExtra() {
        try {
            JSONObject params = new JSONObject();
            params.put("processId", Process.myPid());
            return params;
        } catch (JSONException e) {
            return null;
        }
    }

    private JSONObject getExtra(RheaTrace3.JankEvent event) {
        try {
            JSONObject params = getExtra();
            if (params == null) {
                params = new JSONObject();
            }
            params.put("eventId", event.getEventId());
            params.put("eventStartElapsedRealtimeNanos", event.getStartElapsedRealtimeNanos());
            params.put("eventEndElapsedRealtimeNanos", event.getEndElapsedRealtimeNanos());
            params.put("scene", event.getScene());
            params.put("reason", event.getReason());
            params.put("preRollMs", onlineConfig == null ? 0 : onlineConfig.getPreRollMs());
            params.put("mappingId", onlineConfig == null ? "" : onlineConfig.getMappingId());
            params.put("appName", onlineAppName);
            params.put("onlineMode", true);
            return params;
        } catch (JSONException e) {
            return null;
        }
    }

    private JSONObject buildManifest(RheaTrace3.JankEvent event, File sampling, File mapping,
                                     boolean truncated)
            throws JSONException, IOException {
        JSONObject manifest = new JSONObject();
        manifest.put("schemaVersion", 1);
        manifest.put("samplingFormatVersion", 5);
        manifest.put("byteOrder", "little-endian");
        manifest.put("eventId", event.getEventId());
        manifest.put("eventStartElapsedRealtimeNanos", event.getStartElapsedRealtimeNanos());
        manifest.put("eventEndElapsedRealtimeNanos", event.getEndElapsedRealtimeNanos());
        manifest.put("scene", event.getScene());
        manifest.put("reason", event.getReason());
        manifest.put("preRollMs", onlineConfig.getPreRollMs());
        manifest.put("mappingId", onlineConfig.getMappingId());
        manifest.put("bufferSizeBytes", onlineConfig.getBufferSizeBytes());
        manifest.put("bufferCapacityRecords", getOnlineBufferCapacityRecords());
        manifest.put("appName", onlineAppName);
        manifest.put("processId", Process.myPid());
        manifest.put("dumpedAtElapsedRealtimeMs", SystemClock.elapsedRealtime());
        manifest.put("truncated", truncated);
        JSONObject files = new JSONObject();
        files.put("sampling", fileInfo(sampling));
        files.put("sampling-mapping", fileInfo(mapping));
        manifest.put("files", files);
        return manifest;
    }

    private JSONObject fileInfo(File file) throws JSONException, IOException {
        JSONObject info = new JSONObject();
        info.put("size", file.length());
        info.put("sha256", sha256(file));
        return info;
    }

    private static String sha256(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
                int count;
                while ((count = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, count);
                }
            }
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest.digest()) {
                result.append(String.format(Locale.US, "%02x", value & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is unavailable", e);
        }
    }

    private static void writeUtf8(File file, String value) throws IOException {
        try (OutputStream output = new BufferedOutputStream(new FileOutputStream(file))) {
            output.write(value.getBytes(Charset.forName("UTF-8")));
        }
    }

    private static void zipFiles(File target, File manifest, File sampling, File mapping)
            throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(target)))) {
            addZipEntry(zip, manifest, "manifest.json");
            addZipEntry(zip, sampling, "sampling.bin");
            addZipEntry(zip, mapping, "sampling-mapping.bin");
        }
    }

    private static void addZipEntry(ZipOutputStream zip, File source, String name) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        try (InputStream input = new BufferedInputStream(new FileInputStream(source))) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                zip.write(buffer, 0, count);
            }
        }
        zip.closeEntry();
    }

    private synchronized void cleanupOnlineArtifacts() {
        if (onlineDir == null || !onlineDir.isDirectory()) {
            return;
        }
        File[] files = onlineDir.listFiles((dir, name) -> name.endsWith(ONLINE_ARTIFACT_SUFFIX));
        File[] allFiles = onlineDir.listFiles();
        if (allFiles != null) {
            long now = System.currentTimeMillis();
            for (File file : allFiles) {
                if (file.getName().endsWith(ONLINE_TEMP_SUFFIX)
                        && now - file.lastModified() > ONLINE_TEMP_TTL_MS) {
                    deleteRecursively(file);
                }
            }
        }
        if (files == null) {
            return;
        }
        long now = System.currentTimeMillis();
        long ttl = onlineConfig == null ? Long.MAX_VALUE : onlineConfig.getArtifactTtlMs();
        List<File> valid = new ArrayList<>();
        long total = 0;
        for (File file : files) {
            if (ttl != Long.MAX_VALUE && now - file.lastModified() > ttl) {
                file.delete();
            } else {
                valid.add(file);
                total += file.length();
            }
        }
        long quota = onlineConfig == null ? Long.MAX_VALUE : onlineConfig.getDiskQuotaBytes();
        Collections.sort(valid, Comparator.comparingLong(File::lastModified));
        for (File file : valid) {
            if (total <= quota) {
                break;
            }
            total -= file.length();
            file.delete();
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        file.delete();
    }

    private void notifyDump(RheaTrace3.DumpCallback callback, RheaTrace3.DumpResult result) {
        if (callback != null) {
            try {
                callback.onComplete(result);
            } catch (Throwable throwable) {
                Log.e(TAG, "online dump callback failed", throwable);
            }
        }
    }

    private TraceAbility<?> getSamplingAbility() {
        List<TraceAbility<?>> abilities = TraceAbilityCenter.getAbilities(requireTraceMetas());
        return abilities.isEmpty() ? null : abilities.get(0);
    }

    private void registerLifecycle(Context context) {
        if (!onlineConfig.isForegroundOnly() || !(context instanceof Application)) {
            return;
        }
        lifecycleApplication = (Application) context;
        lifecycleCallbacks = new Application.ActivityLifecycleCallbacks() {
            private int resumedCount;

            @Override public void onActivityResumed(Activity activity) {
                resumedCount++;
                onlineForeground = resumedCount > 0;
                applyOnlineNativeState();
            }
            @Override public void onActivityPaused(Activity activity) {
                resumedCount = Math.max(0, resumedCount - 1);
                onlineForeground = resumedCount > 0;
                applyOnlineNativeState();
            }
            @Override public void onActivityCreated(Activity activity, Bundle state) { }
            @Override public void onActivityStarted(Activity activity) { }
            @Override public void onActivityStopped(Activity activity) { }
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
            @Override public void onActivityDestroyed(Activity activity) { }
        };
        lifecycleApplication.registerActivityLifecycleCallbacks(lifecycleCallbacks);
    }

    private void unregisterLifecycle() {
        if (lifecycleApplication != null && lifecycleCallbacks != null) {
            lifecycleApplication.unregisterActivityLifecycleCallbacks(lifecycleCallbacks);
        }
        lifecycleApplication = null;
        lifecycleCallbacks = null;
        onlineForeground = true;
    }

    private void resetOnlineState() {
        unregisterLifecycle();
        traceTokens = null;
        mode = Mode.NONE;
        onlineConfig = null;
        onlineDir = null;
        onlineAppName = "";
        onlineStartToken = 0;
        onlineEnabled = false;
        onlineDumpBusy = false;
        lastOnlineDumpUptimeMs = Long.MIN_VALUE;
    }

    private void applyOnlineNativeState() {
        if (mode != Mode.ONLINE) {
            return;
        }
        boolean shouldCollect = onlineEnabled
                && (onlineConfig == null || !onlineConfig.isForegroundOnly() || onlineForeground);
        TraceGlobal.setOnlineEnabled(shouldCollect);
    }

    private static class Holder {
        @SuppressLint("StaticFieldLeak")
        private static final TraceManager INSTANCE = new TraceManager();
    }
}
