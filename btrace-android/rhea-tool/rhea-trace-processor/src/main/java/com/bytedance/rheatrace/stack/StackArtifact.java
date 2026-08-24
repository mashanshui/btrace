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
    private static final long MAX_TOTAL_BYTES = 64L * 1024L * 1024L;
    private static final Set<String> REQUIRED = new HashSet<>();
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-fA-F]{64}");

    static {
        REQUIRED.add("manifest.json");
        REQUIRED.add("sampling.bin");
        REQUIRED.add("sampling-mapping.bin");
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

    private static void validateFiles(File root, JSONObject manifest) throws IOException {
        JSONObject files = manifest.optJSONObject("files");
        if (files == null) {
            throw new IOException("manifest 缺少 files 校验信息");
        }
        validateFile(root, files, "sampling", "sampling.bin");
        validateFile(root, files, "sampling-mapping", "sampling-mapping.bin");
    }

    private static void validateFile(File root, JSONObject files, String key, String name)
            throws IOException {
        JSONObject expected = files.optJSONObject(key);
        if (expected == null) {
            throw new IOException("manifest 缺少文件校验信息: " + name);
        }
        File actual = new File(root, name);
        long expectedSize = expected.optLong("size", -1);
        if (expectedSize < 0 || expectedSize != actual.length()) {
            throw new IOException("文件大小校验失败: " + name);
        }
        String expectedHash = expected.optString("sha256", "");
        if (!SHA_256.matcher(expectedHash).matches()) {
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
