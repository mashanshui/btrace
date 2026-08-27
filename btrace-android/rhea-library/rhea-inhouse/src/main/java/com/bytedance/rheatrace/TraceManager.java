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
import android.app.ActivityManager;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.os.Build;
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
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;


public class TraceManager {

    private static final String TAG = "RheaTrace:Manager";
    private static final String ONLINE_ARTIFACT_SUFFIX = ".rheatrace.zip";
    private static final String ONLINE_TEMP_SUFFIX = ".tmp";
    private static final long ONLINE_TEMP_TTL_MS = 24L * 60L * 60L * 1000L;
    private static final int SAMPLING_RECORD_MEMORY_ESTIMATE = 2304;

    private enum Mode {
        NONE,
        DEBUG,
        ONLINE,
        ONLINE_STOPPED
    }

    private String tracingDirPath;
    private long[] traceTokens = null;
    private Mode mode = Mode.NONE;
    private RheaTrace3.OnlineTraceConfig onlineConfig;
    private File onlineDir;
    private String onlineAppName = "";
    private volatile boolean onlineEnabled;
    private volatile boolean onlineForeground = true;
    private volatile boolean onlineDumpBusy;
    private Application lifecycleApplication;
    private Application.ActivityLifecycleCallbacks lifecycleCallbacks;

    public static TraceManager getInstance() {
        return Holder.INSTANCE;
    }

    public synchronized void init(Context context) {
        if (mode == Mode.ONLINE || mode == Mode.ONLINE_STOPPED) {
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
    public synchronized RheaTrace3.InitResult initOnline(Application context,
                                                          RheaTrace3.OnlineTraceConfig config) {
        if (context == null || config == null) {
            return RheaTrace3.InitResult.INVALID_CONFIG;
        }
        if (!config.isEnabled()) {
            return RheaTrace3.InitResult.DISABLED;
        }
        if (mode == Mode.DEBUG) {
            return RheaTrace3.InitResult.MODE_CONFLICT;
        }
        if (mode == Mode.ONLINE || mode == Mode.ONLINE_STOPPED) {
            return RheaTrace3.InitResult.ALREADY_STARTED;
        }
        mode = Mode.ONLINE;
        onlineConfig = config;
        onlineEnabled = config.isEnabled();
        Context appContext = context.getApplicationContext() == null
                ? context : context.getApplicationContext();
        onlineAppName = appContext.getPackageName();
        onlineDir = new File(appContext.getNoBackupFilesDir(), "rhea/stack");
        if (!makeDumpDir(onlineDir.getAbsolutePath())) {
            resetOnlineState();
            return RheaTrace3.InitResult.NATIVE_INIT_FAILED;
        }
        cleanupOnlineArtifacts();
        if (!TraceGlobal.init()) {
            resetOnlineState();
            return RheaTrace3.InitResult.NATIVE_INIT_FAILED;
        }
        if (!startTracing(false)) {
            resetOnlineState();
            mode = Mode.ONLINE_STOPPED;
            return RheaTrace3.InitResult.NATIVE_INIT_FAILED;
        }
        registerLifecycle(appContext);
        applyOnlineNativeState();
        return RheaTrace3.InitResult.STARTED;
    }

    public static boolean isOnlineMode() {
        return getInstance().mode == Mode.ONLINE;
    }

    public static int getOnlineBufferSizeBytes() {
        RheaTrace3.OnlineTraceConfig config = getInstance().onlineConfig;
        return config == null ? 0 : config.getBufferSizeBytes();
    }

    public static long getOnlineSampleIntervalNs() {
        RheaTrace3.OnlineTraceConfig config = getInstance().onlineConfig;
        return config == null ? 0 : config.getMinSampleIntervalNs();
    }

    public static boolean isOnlineJniHookEnabled() {
        RheaTrace3.OnlineTraceConfig config = getInstance().onlineConfig;
        return config != null && config.isEnableJniHook();
    }

    public static boolean isOnlineObjectAllocationEnabled() {
        RheaTrace3.OnlineTraceConfig config = getInstance().onlineConfig;
        return config != null && config.isEnableObjectAllocation();
    }

    public static boolean isOnlineWakeupEnabled() {
        RheaTrace3.OnlineTraceConfig config = getInstance().onlineConfig;
        return config != null && config.isEnableWakeup();
    }

    public static boolean isOnlineRusageEnabled() {
        RheaTrace3.OnlineTraceConfig config = getInstance().onlineConfig;
        return config != null && config.isEnableRusage();
    }

    public static boolean isOnlineStackCaptureStatsEnabled() {
        RheaTrace3.OnlineTraceConfig config = getInstance().onlineConfig;
        return config != null && config.isEnableStackCaptureStats();
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
                long[] tokens = startAbilities(traceAbilities);
                if (tokens == null) {
                    Log.e(TAG, "async start failed: native collector is unavailable");
                    return;
                }
                this.traceTokens = tokens;
            });
            startThread.start();
        } else {
            List<TraceAbility<?>> traceAbilities = TraceAbilityCenter.getAbilities(traceMetas);
            long[] tokens = startAbilities(traceAbilities);
            if (tokens == null) {
                Log.e(TAG, "start failed: native collector is unavailable");
                return false;
            }
            this.traceTokens = tokens;
        }
        return true;
    }

    private long[] startAbilities(List<TraceAbility<?>> traceAbilities) {
        long[] tokens = new long[traceAbilities.size()];
        for (int i = 0; i < traceAbilities.size(); i++) {
            TraceAbility<?> ability = traceAbilities.get(i);
            tokens[i] = ability.start();
            if (tokens[i] == TraceAbility.START_FAILED) {
                ability.destroy();
                for (int started = i - 1; started >= 0; --started) {
                    traceAbilities.get(started).stop();
                }
                return null;
            }
        }
        return tokens;
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

    private static final class RangeSnapshot {
        final RheaTrace3.BufferTimeRange range;
        final long endToken;
        final long snapshotTimeNanos;
        final long droppedByRateLimit;

        RangeSnapshot(RheaTrace3.BufferTimeRange range, long endToken, long snapshotTimeNanos,
                      long droppedByRateLimit) {
            this.range = range;
            this.endToken = endToken;
            this.snapshotTimeNanos = snapshotTimeNanos;
            this.droppedByRateLimit = droppedByRateLimit;
        }
    }

    private static final class ExportSpec {
        final boolean all;
        final RheaTrace3.BufferTimeRange requested;
        final RheaTrace3.BufferTimeRange available;
        final RheaTrace3.BufferTimeRange actual;
        final long snapshotEndToken;
        final long snapshotTimeNanos;
        final boolean partial;
        final long droppedByRateLimit;

        ExportSpec(boolean all, RheaTrace3.BufferTimeRange requested,
                   RheaTrace3.BufferTimeRange available, RheaTrace3.BufferTimeRange actual,
                   long snapshotEndToken, long snapshotTimeNanos, boolean partial,
                   long droppedByRateLimit) {
            this.all = all;
            this.requested = requested;
            this.available = available;
            this.actual = actual;
            this.snapshotEndToken = snapshotEndToken;
            this.snapshotTimeNanos = snapshotTimeNanos;
            this.partial = partial;
            this.droppedByRateLimit = droppedByRateLimit;
        }
    }

    public synchronized RheaTrace3.BufferTimeRange getAvailableStackTimeRange() {
        RangeSnapshot snapshot = readRangeSnapshot();
        return snapshot == null ? RheaTrace3.BufferTimeRange.EMPTY : snapshot.range;
    }

    public synchronized RheaTrace3.ExportRequestResult exportStackData(
            long startTimeNanos, long endTimeNanos, RheaTrace3.ExportCallback callback) {
        RheaTrace3.ExportRequestResult state = validateExportState();
        if (state != RheaTrace3.ExportRequestResult.ACCEPTED) {
            return state;
        }
        if (startTimeNanos < 0 || endTimeNanos <= startTimeNanos) {
            return RheaTrace3.ExportRequestResult.INVALID_RANGE;
        }
        long now = SystemClock.elapsedRealtimeNanos();
        if (endTimeNanos > now) {
            return RheaTrace3.ExportRequestResult.FUTURE_RANGE;
        }
        RangeSnapshot snapshot = readRangeSnapshot();
        if (snapshot == null || snapshot.range.isEmpty()) {
            return RheaTrace3.ExportRequestResult.EMPTY_RANGE;
        }
        long actualStart = Math.max(startTimeNanos,
                snapshot.range.getStartElapsedRealtimeNanos());
        long actualEnd = Math.min(endTimeNanos,
                snapshot.range.getEndElapsedRealtimeNanos());
        if (actualEnd <= actualStart) {
            return RheaTrace3.ExportRequestResult.EMPTY_RANGE;
        }
        boolean partial = actualStart != startTimeNanos || actualEnd != endTimeNanos;
        RheaTrace3.BufferTimeRange requested = new RheaTrace3.BufferTimeRange(
                startTimeNanos, endTimeNanos, 0, snapshot.range.getOverwrittenRecordCount());
        RheaTrace3.BufferTimeRange actual = new RheaTrace3.BufferTimeRange(
                actualStart, actualEnd, 0, snapshot.range.getOverwrittenRecordCount());
        return submitExport(new ExportSpec(false, requested, snapshot.range, actual,
                snapshot.endToken, snapshot.snapshotTimeNanos, partial,
                snapshot.droppedByRateLimit), callback);
    }

    public synchronized RheaTrace3.ExportRequestResult exportAllStackData(
            RheaTrace3.ExportCallback callback) {
        RheaTrace3.ExportRequestResult state = validateExportState();
        if (state != RheaTrace3.ExportRequestResult.ACCEPTED) {
            return state;
        }
        RangeSnapshot snapshot = readRangeSnapshot();
        if (snapshot == null || snapshot.range.isEmpty()) {
            return RheaTrace3.ExportRequestResult.EMPTY_RANGE;
        }
        return submitExport(new ExportSpec(true, null, snapshot.range, snapshot.range,
                snapshot.endToken, snapshot.snapshotTimeNanos, false,
                snapshot.droppedByRateLimit), callback);
    }

    private RheaTrace3.ExportRequestResult validateExportState() {
        if (mode != Mode.ONLINE || onlineConfig == null || traceTokens == null) {
            return RheaTrace3.ExportRequestResult.NOT_INITIALIZED;
        }
        if (!onlineConfig.isEnabled() || !onlineEnabled) {
            return RheaTrace3.ExportRequestResult.DISABLED;
        }
        if (onlineDumpBusy) {
            return RheaTrace3.ExportRequestResult.BUSY;
        }
        if (onlineDir == null || (!onlineDir.isDirectory() && !onlineDir.mkdirs())
                || onlineDir.getUsableSpace() <= 0) {
            return RheaTrace3.ExportRequestResult.STORAGE_UNAVAILABLE;
        }
        return RheaTrace3.ExportRequestResult.ACCEPTED;
    }

    private RangeSnapshot readRangeSnapshot() {
        TraceAbility<?> ability = getSamplingAbility();
        if (ability == null) {
            return null;
        }
        long[] values = ability.getBufferTimeRange();
        if (values == null || values.length < 6 || values[2] <= 0 || values[1] <= values[0]) {
            return null;
        }
        int count = values[2] > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) values[2];
        return new RangeSnapshot(new RheaTrace3.BufferTimeRange(
                values[0], values[1], count, values[3]), values[4],
                SystemClock.elapsedRealtimeNanos(), values[5]);
    }

    private RheaTrace3.ExportRequestResult submitExport(
            ExportSpec spec, RheaTrace3.ExportCallback callback) {
        onlineDumpBusy = true;
        HandlerThreadUtils.getCollectorThreadHandler().post(
                () -> dumpOnlineArtifact(spec, callback));
        return RheaTrace3.ExportRequestResult.ACCEPTED;
    }

    private void dumpOnlineArtifact(ExportSpec spec, RheaTrace3.ExportCallback callback) {
        File artifact = null;
        File tempDir = null;
        RheaTrace3.ExportResult completion = null;
        String baseName = "rhea-stack-" + Process.myPid() + "-" + spec.snapshotTimeNanos;
        try {
            if (onlineDir == null || onlineConfig == null) {
                throw new IOException("online collector is not initialized");
            }
            cleanupOnlineArtifacts();
            tempDir = new File(onlineDir, baseName + ONLINE_TEMP_SUFFIX);
            deleteRecursively(tempDir);
            if (!tempDir.mkdirs()) {
                throw new IOException("cannot create temporary dump directory");
            }
            TraceAbility<?> ability = getSamplingAbility();
            if (ability == null) {
                throw new IOException("sampling collector is unavailable");
            }
            JSONObject extra = getExtra(spec);
            long[] dumpResult = ability.dumpTimeRange(
                    spec.actual.getStartElapsedRealtimeNanos(),
                    spec.actual.getEndElapsedRealtimeNanos(),
                    spec.snapshotEndToken, tempDir.getAbsolutePath(),
                    extra == null ? "" : extra.toString());
            if (dumpResult == null || dumpResult.length < 4) {
                throw new IOException("native dump returned an invalid result");
            }
            int result = (int) dumpResult[0];
            if (result == 11) {
                completion = new RheaTrace3.ExportResult(
                        RheaTrace3.ExportStatus.EMPTY, null, spec.requested, spec.available,
                        RheaTrace3.BufferTimeRange.EMPTY, 0,
                        spec.available.getOverwrittenRecordCount(), spec.droppedByRateLimit,
                        "no stack record in requested range");
                return;
            }
            boolean nativePartial = result == 12;
            if (result != 0 && !nativePartial) {
                throw new IOException("native dump failed with code " + result);
            }
            File sampling = new File(tempDir, "sampling");
            File mapping = new File(tempDir, "sampling-mapping");
            if (!sampling.isFile() || !mapping.isFile() || sampling.length() == 0) {
                throw new IOException("native dump files are missing");
            }
            if (dumpResult[3] <= 0 || dumpResult[3] > Integer.MAX_VALUE
                    || dumpResult[2] <= dumpResult[1]) {
                throw new IOException("native dump metadata is invalid");
            }
            int recordCount = readSamplingRecordCount(sampling);
            if (recordCount != (int) dumpResult[3]) {
                throw new IOException("native record count does not match sampling header");
            }
            RheaTrace3.BufferTimeRange actual = new RheaTrace3.BufferTimeRange(
                    dumpResult[1], dumpResult[2], recordCount,
                    spec.available.getOverwrittenRecordCount());
            boolean partial = spec.partial || nativePartial;
            JSONObject manifest = buildManifest(spec, actual, partial, sampling, mapping);
            File manifestFile = new File(tempDir, "manifest.json");
            writeUtf8(manifestFile, manifest.toString());
            File zipTemp = new File(onlineDir,
                    baseName + ONLINE_ARTIFACT_SUFFIX + ONLINE_TEMP_SUFFIX);
            File zipFinal = new File(onlineDir, baseName + ONLINE_ARTIFACT_SUFFIX);
            deleteRecursively(zipTemp);
            zipFiles(zipTemp, manifestFile, sampling, mapping);
            if (zipTemp.length() > onlineConfig.getMaxArtifactBytes()) {
                throw new IOException("artifact exceeds maxArtifactBytes");
            }
            verifyZipArtifact(zipTemp, recordCount);
            Files.move(zipTemp.toPath(), zipFinal.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            artifact = zipFinal;
            if (!cleanupOnlineArtifacts(artifact) || !artifact.isFile()) {
                Files.deleteIfExists(artifact.toPath());
                artifact = null;
                throw new IOException("artifact exceeds online disk quota");
            }
            completion = new RheaTrace3.ExportResult(
                    partial ? RheaTrace3.ExportStatus.PARTIAL
                            : RheaTrace3.ExportStatus.SUCCESS,
                    artifact, spec.requested, spec.available, actual, recordCount,
                    spec.available.getOverwrittenRecordCount(), spec.droppedByRateLimit, "ok");
        } catch (Throwable throwable) {
            String message = throwable.getMessage() == null
                    ? throwable.getClass().getSimpleName() : throwable.getMessage();
            Log.e(TAG, "online stack export failed", throwable);
            completion = new RheaTrace3.ExportResult(
                    RheaTrace3.ExportStatus.FAILED, artifact, spec.requested, spec.available,
                    RheaTrace3.BufferTimeRange.EMPTY, 0,
                    spec.available.getOverwrittenRecordCount(), spec.droppedByRateLimit, message);
        } finally {
            deleteRecursively(tempDir);
            if (onlineDir != null) {
                deleteRecursively(new File(onlineDir,
                        baseName + ONLINE_ARTIFACT_SUFFIX + ONLINE_TEMP_SUFFIX));
            }
            onlineDumpBusy = false;
            notifyExport(callback, completion);
        }
    }

    public synchronized List<File> getPendingStackFiles() {
        if (onlineDir == null || !onlineDir.isDirectory()) {
            return Collections.emptyList();
        }
        cleanupOnlineArtifacts();
        File[] files = onlineDir.listFiles(
                (dir, name) -> name.endsWith(ONLINE_ARTIFACT_SUFFIX));
        if (files == null || files.length == 0) {
            return Collections.emptyList();
        }
        List<File> result = new ArrayList<>();
        Collections.addAll(result, files);
        Collections.sort(result, Comparator.comparingLong(File::lastModified));
        return result;
    }

    public synchronized boolean deleteStackFile(File artifact) {
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
        if (onlineDumpBusy) {
            HandlerThreadUtils.getCollectorThreadHandler().post(this::stopOnlineTracing);
            return;
        }
        TraceAbility<?> ability = getSamplingAbility();
        if (ability != null && traceTokens != null) {
            ability.destroy();
        }
        traceTokens = null;
        TraceGlobal.setOnlineEnabled(false);
        unregisterLifecycle();
        resetOnlineState();
        // 部分 ART/JNI Hook 无法在进程内安全恢复到调试配置，stop 后保持终止态。
        mode = Mode.ONLINE_STOPPED;
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

    private JSONObject getExtra(ExportSpec spec) {
        try {
            JSONObject params = getExtra();
            if (params == null) {
                params = new JSONObject();
            }
            params.put("selectionType", spec.all ? "ALL" : "RANGE");
            params.put("requestedStartNs", spec.requested == null
                    ? JSONObject.NULL : spec.requested.getStartElapsedRealtimeNanos());
            params.put("requestedEndNs", spec.requested == null
                    ? JSONObject.NULL : spec.requested.getEndElapsedRealtimeNanos());
            params.put("filterStartNs", spec.actual.getStartElapsedRealtimeNanos());
            params.put("filterEndNs", spec.actual.getEndElapsedRealtimeNanos());
            params.put("snapshotTimeNs", spec.snapshotTimeNanos);
            params.put("mappingId", onlineConfig == null ? "" : onlineConfig.getMappingId());
            params.put("appName", onlineAppName);
            params.put("onlineMode", true);
            return params;
        } catch (JSONException e) {
            return null;
        }
    }

    private JSONObject buildManifest(ExportSpec spec, RheaTrace3.BufferTimeRange actual,
                                     boolean partial, File sampling, File mapping)
            throws JSONException, IOException {
        JSONObject manifest = new JSONObject();
        manifest.put("schemaVersion", 1);
        manifest.put("artifactType", "RHEA_STACK");
        manifest.put("samplingFormatVersion", 5);
        manifest.put("byteOrder", "little-endian");
        manifest.put("clock", "ELAPSED_REALTIME_NANOS");
        manifest.put("selectionType", spec.all ? "ALL" : "RANGE");
        manifest.put("requestedStartNs", spec.requested == null
                ? JSONObject.NULL : spec.requested.getStartElapsedRealtimeNanos());
        manifest.put("requestedEndNs", spec.requested == null
                ? JSONObject.NULL : spec.requested.getEndElapsedRealtimeNanos());
        manifest.put("availableStartNs",
                spec.available.getStartElapsedRealtimeNanos());
        manifest.put("availableEndNs",
                spec.available.getEndElapsedRealtimeNanos());
        manifest.put("actualStartNs", actual.getStartElapsedRealtimeNanos());
        manifest.put("actualEndNs", actual.getEndElapsedRealtimeNanos());
        manifest.put("snapshotTimeNs", spec.snapshotTimeNanos);
        manifest.put("partial", partial);
        manifest.put("recordCount", actual.getRecordCount());
        manifest.put("overwrittenRecordCount",
                spec.available.getOverwrittenRecordCount());
        manifest.put("droppedByRateLimit", spec.droppedByRateLimit);
        manifest.put("mappingId", onlineConfig.getMappingId());
        manifest.put("bufferSizeBytes", onlineConfig.getBufferSizeBytes());
        manifest.put("bufferCapacityRecords", getOnlineBufferCapacityRecords());
        manifest.put("minSampleIntervalNs", onlineConfig.getMinSampleIntervalNs());
        manifest.put("foregroundOnly", onlineConfig.isForegroundOnly());
        manifest.put("enableJniHook", onlineConfig.isEnableJniHook());
        manifest.put("enableObjectAllocation", onlineConfig.isEnableObjectAllocation());
        manifest.put("enableWakeup", onlineConfig.isEnableWakeup());
        manifest.put("enableRusage", onlineConfig.isEnableRusage());
        manifest.put("appName", onlineAppName);
        manifest.put("processId", Process.myPid());
        manifest.put("androidApi", Build.VERSION.SDK_INT);
        manifest.put("abi", Build.SUPPORTED_ABIS.length == 0 ? "" : Build.SUPPORTED_ABIS[0]);
        JSONObject files = new JSONObject();
        files.put("sampling", fileInfo(sampling));
        files.put("sampling-mapping", fileInfo(mapping));
        manifest.put("files", files);
        return manifest;
    }

    private static int readSamplingRecordCount(File sampling) throws IOException {
        byte[] header = new byte[24];
        try (RandomAccessFile input = new RandomAccessFile(sampling, "r")) {
            if (input.length() < header.length) {
                throw new IOException("sampling header is incomplete");
            }
            input.readFully(header);
        }
        int count = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getInt(20);
        if (count < 0) {
            throw new IOException("sampling record count is invalid");
        }
        return count;
    }

    private JSONObject fileInfo(File file) throws JSONException, IOException {
        JSONObject info = new JSONObject();
        info.put("size", file.length());
        info.put("sha256", sha256(file));
        return info;
    }

    private static String sha256(File file) throws IOException {
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            return sha256(input);
        }
    }

    private static String sha256(InputStream input) throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is unavailable", e);
        }
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            digest.update(buffer, 0, count);
        }
        StringBuilder result = new StringBuilder(64);
        for (byte value : digest.digest()) {
            result.append(String.format(Locale.US, "%02x", value & 0xff));
        }
        return result.toString();
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

    private static void verifyZipArtifact(File artifact, int expectedRecordCount)
            throws IOException, JSONException {
        Set<String> expected = new HashSet<>();
        expected.add("manifest.json");
        expected.add("sampling.bin");
        expected.add("sampling-mapping.bin");
        try (ZipFile zip = new ZipFile(artifact)) {
            Set<String> actual = new HashSet<>();
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || !actual.add(entry.getName())) {
                    throw new IOException("artifact contains an invalid or duplicate entry");
                }
            }
            if (!expected.equals(actual)) {
                throw new IOException("artifact entries are incomplete");
            }
            JSONObject manifest;
            try (InputStream input = zip.getInputStream(zip.getEntry("manifest.json"))) {
                manifest = new JSONObject(readUtf8(input));
            }
            if (manifest.optInt("recordCount", -1) != expectedRecordCount) {
                throw new IOException("manifest record count does not match native output");
            }
            JSONObject files = manifest.getJSONObject("files");
            verifyZipEntry(zip, files.getJSONObject("sampling"), "sampling.bin");
            verifyZipEntry(zip, files.getJSONObject("sampling-mapping"),
                    "sampling-mapping.bin");
        }
    }

    private static void verifyZipEntry(ZipFile zip, JSONObject info, String name)
            throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null || entry.getSize() != info.optLong("size", -1)) {
            throw new IOException("artifact entry size mismatch: " + name);
        }
        String digest;
        try (InputStream input = new BufferedInputStream(zip.getInputStream(entry))) {
            digest = sha256(input);
        }
        if (!digest.equalsIgnoreCase(info.optString("sha256", ""))) {
            throw new IOException("artifact entry checksum mismatch: " + name);
        }
    }

    private static String readUtf8(InputStream input) throws IOException {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            output.write(buffer, 0, count);
        }
        return new String(output.toByteArray(), Charset.forName("UTF-8"));
    }

    private synchronized void cleanupOnlineArtifacts() {
        cleanupOnlineArtifacts(null);
    }

    private synchronized boolean cleanupOnlineArtifacts(File protectedArtifact) {
        if (onlineDir == null || !onlineDir.isDirectory()) {
            return false;
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
            return true;
        }
        long now = System.currentTimeMillis();
        long ttl = onlineConfig == null ? Long.MAX_VALUE : onlineConfig.getArtifactTtlMs();
        List<File> valid = new ArrayList<>();
        long total = 0;
        for (File file : files) {
            boolean protectedFile = protectedArtifact != null
                    && protectedArtifact.equals(file);
            if (!protectedFile && ttl != Long.MAX_VALUE
                    && now - file.lastModified() > ttl && file.delete()) {
                continue;
            }
            valid.add(file);
            total += file.length();
        }
        long quota = onlineConfig == null ? Long.MAX_VALUE : onlineConfig.getDiskQuotaBytes();
        Collections.sort(valid, Comparator.comparingLong(File::lastModified));
        for (File file : valid) {
            if (total <= quota) {
                break;
            }
            if (protectedArtifact != null && protectedArtifact.equals(file)) {
                continue;
            }
            long length = file.length();
            if (file.delete()) {
                total -= length;
            }
        }
        return total <= quota;
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

    private void notifyExport(RheaTrace3.ExportCallback callback, RheaTrace3.ExportResult result) {
        if (callback != null) {
            try {
                callback.onCompleted(result);
            } catch (Throwable throwable) {
                Log.e(TAG, "online export callback failed", throwable);
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
        ActivityManager.RunningAppProcessInfo processInfo =
                new ActivityManager.RunningAppProcessInfo();
        ActivityManager.getMyMemoryState(processInfo);
        onlineForeground = processInfo.importance
                <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
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
        onlineEnabled = false;
        onlineDumpBusy = false;
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
