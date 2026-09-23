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
package com.bytedance.rheatrace.stack;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** 堆栈采集 ZIP 的安全解包器。 */
public final class StackArtifact implements Closeable {
    static final long MAX_TOTAL_BYTES = 64L * 1024L * 1024L;
    private static final Set<String> REQUIRED = new HashSet<>();
    private static final Set<String> JANK_V3_FIELDS = new HashSet<>();
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-fA-F]{64}");
    private static final Pattern LOWER_SHA_256 = Pattern.compile("[0-9a-f]{64}");

    static {
        REQUIRED.add("manifest.json");
        REQUIRED.add("sampling.bin");
        REQUIRED.add("sampling-mapping.bin");
        JANK_V3_FIELDS.add("schemaVersion");
        JANK_V3_FIELDS.add("artifactType");
        JANK_V3_FIELDS.add("eventId");
        JANK_V3_FIELDS.add("occurredAt");
        JANK_V3_FIELDS.add("sessionId");
        JANK_V3_FIELDS.add("anonymousDeviceId");
        JANK_V3_FIELDS.add("packageName");
        JANK_V3_FIELDS.add("appVersion");
        JANK_V3_FIELDS.add("versionCode");
        JANK_V3_FIELDS.add("buildId");
        JANK_V3_FIELDS.add("environment");
        JANK_V3_FIELDS.add("channel");
        JANK_V3_FIELDS.add("osVersion");
        JANK_V3_FIELDS.add("deviceModel");
        JANK_V3_FIELDS.add("scene");
        JANK_V3_FIELDS.add("messageStartNs");
        JANK_V3_FIELDS.add("messageEndNs");
        JANK_V3_FIELDS.add("thresholdNs");
        JANK_V3_FIELDS.add("minSampleIntervalNs");
        JANK_V3_FIELDS.add("attemptedSampleCount");
        JANK_V3_FIELDS.add("processId");
        JANK_V3_FIELDS.add("threadScope");
        JANK_V3_FIELDS.add("files");
    }

    private final File root;
    private final JSONObject manifest;

    private StackArtifact(File root, JSONObject manifest) {
        this.root = root;
        this.manifest = manifest;
    }

    public static StackArtifact open(File input) throws IOException {
        if (input == null || !input.isFile()) {
            throw new IOException("堆栈产物不存在: " + input);
        }
        if (input.length() > MAX_TOTAL_BYTES) {
            throw new IOException("堆栈产物超过大小限制: " + input.length());
        }
        File root = Files.createTempDirectory("rhea-stack-").toFile();
        long total = 0;
        Set<String> seen = new HashSet<>();
        try (ZipFile zip = new ZipFile(input)) {
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || !REQUIRED.contains(name) || name.contains("..")
                        || name.startsWith("/") || name.indexOf('\\') >= 0) {
                    throw new IOException("不支持或不安全的 ZIP 条目: " + name);
                }
                if (!seen.add(name)) {
                    throw new IOException("ZIP 条目重复: " + name);
                }
                long declared = entry.getSize();
                if (declared > MAX_TOTAL_BYTES) {
                    throw new IOException("ZIP 条目过大: " + name);
                }
                File output = new File(root, name);
                try (InputStream source = new BufferedInputStream(zip.getInputStream(entry));
                     BufferedOutputStream target = new BufferedOutputStream(new FileOutputStream(output))) {
                    byte[] buffer = new byte[8192];
                    int count;
                    long entryBytes = 0;
                    while ((count = source.read(buffer)) != -1) {
                        entryBytes += count;
                        total += count;
                        if (entryBytes > MAX_TOTAL_BYTES || total > MAX_TOTAL_BYTES) {
                            throw new IOException("ZIP 解压后超过大小限制");
                        }
                        target.write(buffer, 0, count);
                    }
                }
            }
        } catch (Throwable throwable) {
            deleteRecursively(root);
            if (throwable instanceof IOException) {
                throw (IOException) throwable;
            }
            throw new IOException("读取堆栈产物失败", throwable);
        }
        if (!seen.containsAll(REQUIRED)) {
            deleteRecursively(root);
            throw new IOException("堆栈产物缺少必需文件");
        }
        try {
            String text = new String(Files.readAllBytes(new File(root, "manifest.json").toPath()),
                    StandardCharsets.UTF_8);
            JSONObject manifest = new JSONObject(text);
            validateManifest(manifest);
            validateFiles(root, manifest);
            return new StackArtifact(root, manifest);
        } catch (JSONException | IOException throwable) {
            deleteRecursively(root);
            if (throwable instanceof IOException) {
                throw (IOException) throwable;
            }
            throw new IOException("manifest.json 格式错误", throwable);
        }
    }

    private static void validateManifest(JSONObject manifest) throws IOException {
        Object version = manifest.opt("schemaVersion");
        String artifactType = manifest.optString("artifactType", "");
        if (isIntegral(version) && ((Number) version).longValue() == 1
                && "RHEA_STACK".equals(artifactType)) {
            validateStackV1Manifest(manifest);
            return;
        }
        if (isIntegral(version) && ((Number) version).longValue() == 3
                && "RHEA_JANK".equals(artifactType)) {
            validateJankManifest(manifest);
            return;
        }
        throw new IOException("不支持的堆栈产物版本");
    }

    private static void validateStackV1Manifest(JSONObject manifest) throws IOException {
        if (manifest.optInt("schemaVersion", -1) != 1
                || !"RHEA_STACK".equals(manifest.optString("artifactType", ""))) {
            throw new IOException("不支持的堆栈产物版本");
        }
        if (manifest.optInt("samplingFormatVersion", -1) != 5) {
            throw new IOException("不支持的 samplingFormatVersion");
        }
        if (!"ELAPSED_REALTIME_NANOS".equals(manifest.optString("clock", ""))) {
            throw new IOException("不支持的时间基准");
        }
        if (!"little-endian".equals(manifest.optString("byteOrder", ""))) {
            throw new IOException("不支持的字节序");
        }
        requireUuidV4(manifest, "processId");
        requireMainThreadScope(manifest);
        if (manifest.optInt("recordCount", 0) <= 0) {
            throw new IOException("manifest recordCount 无效");
        }
        String selection = manifest.optString("selectionType", "");
        if (!"RANGE".equals(selection) && !"ALL".equals(selection)) {
            throw new IOException("manifest selectionType 无效");
        }
        long actualStart = manifest.optLong("actualStartNs", -1);
        long actualEnd = manifest.optLong("actualEndNs", -1);
        if (actualStart < 0 || actualEnd <= actualStart) {
            throw new IOException("manifest 实际时间范围无效");
        }
        long availableStart = manifest.optLong("availableStartNs", -1);
        long availableEnd = manifest.optLong("availableEndNs", -1);
        if (availableStart < 0 || availableEnd <= availableStart
                || actualStart < availableStart || actualEnd > availableEnd) {
            throw new IOException("manifest 可用时间范围无效");
        }
        if ("RANGE".equals(selection)) {
            if (manifest.isNull("requestedStartNs") || manifest.isNull("requestedEndNs")
                    || manifest.optLong("requestedEndNs", -1)
                    <= manifest.optLong("requestedStartNs", -1)) {
                throw new IOException("manifest 请求时间范围无效");
            }
            if (actualStart < manifest.optLong("requestedStartNs", -1)
                    || actualEnd > manifest.optLong("requestedEndNs", -1)) {
                throw new IOException("manifest 实际范围超出请求范围");
            }
        }
    }

    private static void validateJankManifest(JSONObject manifest) throws IOException {
        java.util.Iterator<String> keys = manifest.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!JANK_V3_FIELDS.contains(key)) {
                throw new IOException("manifest 卡顿协议包含未约定字段: " + key);
            }
        }
        if (manifest.length() != JANK_V3_FIELDS.size()) {
            throw new IOException("manifest 卡顿协议缺少必填字段");
        }
        requireString(manifest, "eventId", 128);
        requirePositiveLong(manifest, "occurredAt");
        requireString(manifest, "sessionId", 128);
        requireString(manifest, "anonymousDeviceId", 256);
        requireString(manifest, "packageName", 255);
        requireString(manifest, "appVersion", 128);
        requireNonNegativeLong(manifest, "versionCode");
        requireString(manifest, "buildId", 256);
        requireString(manifest, "environment", 64);
        requireString(manifest, "channel", 128);
        requireString(manifest, "osVersion", 64);
        requireString(manifest, "deviceModel", 256);
        requireString(manifest, "scene", 128);
        long start = requireNonNegativeLong(manifest, "messageStartNs");
        long end = requirePositiveLong(manifest, "messageEndNs");
        if (end <= start) {
            throw new IOException("manifest 消息时间范围无效");
        }
        long threshold = requirePositiveLong(manifest, "thresholdNs");
        if (threshold > end - start) {
            throw new IOException("manifest thresholdNs 超过消息耗时");
        }
        requirePositiveLong(manifest, "minSampleIntervalNs");
        requireNonNegativeLong(manifest, "attemptedSampleCount");
        requireUuidV4(manifest, "processId");
        requireMainThreadScope(manifest);
        if (!(manifest.opt("files") instanceof JSONObject)) {
            throw new IOException("manifest 缺少 files 校验信息");
        }
    }

    private static void requireString(JSONObject manifest, String key, int maxLength)
            throws IOException {
        Object value = manifest.opt(key);
        if (!(value instanceof String)) {
            throw new IOException("manifest 字段类型无效: " + key);
        }
        String text = (String) value;
        if (text.trim().isEmpty() || text.length() > maxLength) {
            throw new IOException("manifest 字段内容无效: " + key);
        }
    }

    /** 校验 manifest 中的进程身份为 canonical UUID v4。 */
    private static void requireUuidV4(JSONObject manifest, String key)
            throws IOException {
        if (!ProcessIdentity.isUuidV4(manifest.opt(key))) {
            throw new IOException("manifest 字段必须为 UUID v4: " + key);
        }
    }

    /** 校验线上产物只声明主线程采样范围。 */
    private static void requireMainThreadScope(JSONObject manifest)
            throws IOException {
        if (!"main".equals(manifest.optString("threadScope", ""))) {
            throw new IOException("manifest threadScope 必须为 main");
        }
    }

    private static long requirePositiveLong(JSONObject manifest, String key)
            throws IOException {
        long value = requireIntegralLong(manifest, key);
        if (value <= 0) {
            throw new IOException("manifest 字段必须为正数: " + key);
        }
        return value;
    }

    private static long requireNonNegativeLong(JSONObject manifest, String key)
            throws IOException {
        long value = requireIntegralLong(manifest, key);
        if (value < 0) {
            throw new IOException("manifest 字段不能为负数: " + key);
        }
        return value;
    }

    private static long requireIntegralLong(JSONObject manifest, String key)
            throws IOException {
        Object value = manifest.opt(key);
        if (!isIntegral(value)) {
            throw new IOException("manifest 字段必须为整数: " + key);
        }
        return ((Number) value).longValue();
    }

    private static boolean isIntegral(Object value) {
        return value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long;
    }

    private static void validateFiles(File root, JSONObject manifest) throws IOException {
        JSONObject files = manifest.optJSONObject("files");
        if (files == null) {
            throw new IOException("manifest 缺少 files 校验信息");
        }
        boolean strictJank = manifest.optInt("schemaVersion", -1) == 3
                && "RHEA_JANK".equals(manifest.optString("artifactType", ""));
        if (strictJank && (files.length() != 2
                || !files.has("sampling") || !files.has("sampling-mapping"))) {
            throw new IOException("manifest files 字段无效");
        }
        validateFile(root, files, "sampling", "sampling.bin", strictJank);
        validateFile(root, files, "sampling-mapping", "sampling-mapping.bin", strictJank);
    }

    private static void validateFile(File root, JSONObject files, String key, String name,
                                     boolean strictJank)
            throws IOException {
        JSONObject expected = files.optJSONObject(key);
        if (expected == null) {
            throw new IOException("manifest 缺少文件校验信息: " + name);
        }
        if (strictJank && (expected.length() != 2
                || !expected.has("size") || !expected.has("sha256"))) {
            throw new IOException("manifest 文件校验字段无效: " + name);
        }
        File actual = new File(root, name);
        Object sizeValue = expected.opt("size");
        if (strictJank && !isIntegral(sizeValue)) {
            throw new IOException("文件大小必须为整数: " + name);
        }
        long expectedSize = expected.optLong("size", -1);
        if (expectedSize < 0 || expectedSize != actual.length()) {
            throw new IOException("文件大小校验失败: " + name);
        }
        String expectedHash = expected.optString("sha256", "");
        Pattern hashPattern = strictJank ? LOWER_SHA_256 : SHA_256;
        if (!hashPattern.matcher(expectedHash).matches()) {
            throw new IOException("文件 SHA-256 格式无效: " + name);
        }
        if (!expectedHash.equalsIgnoreCase(sha256(actual))) {
            throw new IOException("文件 SHA-256 校验失败: " + name);
        }
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
                result.append(String.format("%02x", value & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 不可用", e);
        }
    }

    public JSONObject getManifest() {
        return manifest;
    }

    public File getSamplingFile() {
        return new File(root, "sampling.bin");
    }

    public File getMappingFile() {
        return new File(root, "sampling-mapping.bin");
    }

    @Override
    public void close() {
        deleteRecursively(root);
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
}
