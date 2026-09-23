/*
 * Copyright (C) 2021 ByteDance Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.bytedance.rheatrace.stack;

import org.junit.Assert;
import org.junit.Test;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class StackArtifactTest {

    private static final String PROCESS_ID = "123e4567-e89b-42d3-a456-426614174000";

    @Test
    public void opensValidArtifact() throws Exception {
        File valid = File.createTempFile("rhea-stack", ".zip");
        try {
            byte[] sampling = "sample".getBytes(StandardCharsets.UTF_8);
            byte[] mapping = "mapping".getBytes(StandardCharsets.UTF_8);
            JSONObject files = new JSONObject()
                    .put("sampling", fileInfo(sampling))
                    .put("sampling-mapping", fileInfo(mapping));
            JSONObject manifest = new JSONObject()
                    .put("schemaVersion", 1)
                    .put("artifactType", "RHEA_STACK")
                    .put("samplingFormatVersion", 5)
                    .put("byteOrder", "little-endian")
                    .put("clock", "ELAPSED_REALTIME_NANOS")
                    .put("selectionType", "ALL")
                    .put("availableStartNs", 1)
                    .put("availableEndNs", 2)
                    .put("actualStartNs", 1)
                    .put("actualEndNs", 2)
                    .put("recordCount", 1)
                    .put("processId", PROCESS_ID)
                    .put("threadScope", "main")
                    .put("files", files);
            try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(valid))) {
                put(zip, "manifest.json", manifest.toString());
                put(zip, "sampling.bin", sampling);
                put(zip, "sampling-mapping.bin", mapping);
            }
            try (StackArtifact artifact = StackArtifact.open(valid)) {
                Assert.assertEquals("ALL",
                        artifact.getManifest().getString("selectionType"));
            }
        } finally {
            Assert.assertTrue(valid.delete());
        }
    }

    @Test(expected = java.io.IOException.class)
    public void rejectsManifestWithoutChecksums() throws Exception {
        File invalid = File.createTempFile("rhea-stack", ".zip");
        try {
            try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(invalid))) {
                put(zip, "manifest.json", "{\"schemaVersion\":1,"
                        + "\"artifactType\":\"RHEA_STACK\",\"samplingFormatVersion\":5,"
                        + "\"byteOrder\":\"little-endian\","
                        + "\"clock\":\"ELAPSED_REALTIME_NANOS\","
                        + "\"selectionType\":\"ALL\",\"actualStartNs\":1,"
                        + "\"actualEndNs\":2,\"recordCount\":1}");
                put(zip, "sampling.bin", "sample");
                put(zip, "sampling-mapping.bin", "mapping");
            }
            StackArtifact.open(invalid);
        } finally {
            invalid.delete();
        }
    }

    @Test(expected = java.io.IOException.class)
    public void rejectsUnexpectedZipEntry() throws Exception {
        File invalid = File.createTempFile("rhea-stack", ".zip");
        try {
            try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(invalid))) {
                put(zip, "../escape", "bad");
            }
            StackArtifact.open(invalid);
        } finally {
            invalid.delete();
        }
    }

    @Test
    public void opensValidJankV3Artifact() throws Exception {
        File valid = File.createTempFile("rhea-jank-v3", ".zip");
        byte[] sampling = "sample".getBytes(StandardCharsets.UTF_8);
        byte[] mapping = "mapping".getBytes(StandardCharsets.UTF_8);
        try {
            writeArtifact(valid, validJankManifest(sampling, mapping), sampling, mapping);
            try (StackArtifact artifact = StackArtifact.open(valid)) {
                Assert.assertEquals(3, artifact.getManifest().getInt("schemaVersion"));
                Assert.assertEquals("RHEA_JANK",
                        artifact.getManifest().getString("artifactType"));
                Assert.assertEquals("jank-1", artifact.getManifest().getString("eventId"));
            }
        } finally {
            Assert.assertTrue(valid.delete());
        }
    }

    @Test(expected = java.io.IOException.class)
    public void rejectsStackV1NumericProcessId() throws Exception {
        File invalid = File.createTempFile("rhea-stack-v1-process", ".zip");
        byte[] sampling = "sample".getBytes(StandardCharsets.UTF_8);
        byte[] mapping = "mapping".getBytes(StandardCharsets.UTF_8);
        try {
            writeArtifact(invalid, validManifest(sampling, mapping)
                    .put("processId", 123L), sampling, mapping);
            StackArtifact.open(invalid);
        } finally {
            Assert.assertTrue(invalid.delete());
        }
    }

    @Test(expected = java.io.IOException.class)
    public void rejectsStackV1NonCanonicalProcessId() throws Exception {
        File invalid = File.createTempFile("rhea-stack-v1-process-format", ".zip");
        byte[] sampling = "sample".getBytes(StandardCharsets.UTF_8);
        byte[] mapping = "mapping".getBytes(StandardCharsets.UTF_8);
        try {
            writeArtifact(invalid, validManifest(sampling, mapping)
                    .put("processId", "123E4567-E89B-42D3-A456-426614174000"),
                    sampling, mapping);
            StackArtifact.open(invalid);
        } finally {
            Assert.assertTrue(invalid.delete());
        }
    }

    @Test(expected = java.io.IOException.class)
    public void rejectsJankV3MissingRequiredField() throws Exception {
        verifyInvalidJankManifest("sessionId", null);
    }

    @Test(expected = java.io.IOException.class)
    public void rejectsJankV3FloatingPointInteger() throws Exception {
        verifyInvalidJankManifest("occurredAt", 1000.5d);
    }

    @Test(expected = java.io.IOException.class)
    public void rejectsJankV3ThresholdLongerThanMessage() throws Exception {
        verifyInvalidJankManifest("thresholdNs", 301L);
    }

    @Test(expected = java.io.IOException.class)
    public void rejectsJankV3NumericProcessId() throws Exception {
        verifyInvalidJankManifest("processId", 123L);
    }

    @Test(expected = java.io.IOException.class)
    public void rejectsJankV3NonMainThreadScope() throws Exception {
        verifyInvalidJankManifest("threadScope", "all");
    }

    @Test(expected = java.io.IOException.class)
    public void rejectsJankV3UnknownField() throws Exception {
        verifyInvalidJankManifest("unknownField", "value");
    }

    @Test(expected = java.io.IOException.class)
    public void rejectsJankLegacyAppIdField() throws Exception {
        verifyInvalidJankManifest("appId", "com.example.app");
    }

    @Test(expected = java.io.IOException.class)
    public void rejectsLegacyJankV2Manifest() throws Exception {
        File invalid = File.createTempFile("rhea-jank-v2", ".zip");
        byte[] sampling = "sample".getBytes(StandardCharsets.UTF_8);
        byte[] mapping = "mapping".getBytes(StandardCharsets.UTF_8);
        try {
            JSONObject manifest = validJankManifest(sampling, mapping)
                    .put("schemaVersion", 2);
            writeArtifact(invalid, manifest, sampling, mapping);
            StackArtifact.open(invalid);
        } finally {
            invalid.delete();
        }
    }

    @Test(expected = java.io.IOException.class)
    public void rejectsDuplicateZipEntry() throws Exception {
        File invalid = File.createTempFile("rhea-stack-duplicate", ".zip");
        try {
            writeStoredZip(invalid, "manifest.json", "manifest.json");
            StackArtifact.open(invalid);
        } finally {
            invalid.delete();
        }
    }

    @Test(expected = java.io.IOException.class)
    public void rejectsMissingRequiredEntry() throws Exception {
        File invalid = File.createTempFile("rhea-stack-missing", ".zip");
        byte[] sampling = "sample".getBytes(StandardCharsets.UTF_8);
        byte[] mapping = "mapping".getBytes(StandardCharsets.UTF_8);
        try {
            try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(invalid))) {
                put(zip, "manifest.json", validManifest(sampling, mapping).toString());
                put(zip, "sampling.bin", sampling);
            }
            StackArtifact.open(invalid);
        } finally {
            invalid.delete();
        }
    }

    @Test(expected = java.io.IOException.class)
    public void rejectsChecksumMismatch() throws Exception {
        File invalid = File.createTempFile("rhea-stack-checksum", ".zip");
        byte[] sampling = "sample".getBytes(StandardCharsets.UTF_8);
        byte[] mapping = "mapping".getBytes(StandardCharsets.UTF_8);
        try {
            try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(invalid))) {
                put(zip, "manifest.json", validManifest(sampling, mapping).toString());
                put(zip, "sampling.bin", "tamper".getBytes(StandardCharsets.UTF_8));
                put(zip, "sampling-mapping.bin", mapping);
            }
            StackArtifact.open(invalid);
        } finally {
            invalid.delete();
        }
    }

    @Test(expected = java.io.IOException.class)
    public void rejectsUnsupportedArtifactVersion() throws Exception {
        File invalid = File.createTempFile("rhea-stack-version", ".zip");
        byte[] sampling = "sample".getBytes(StandardCharsets.UTF_8);
        byte[] mapping = "mapping".getBytes(StandardCharsets.UTF_8);
        try {
            JSONObject manifest = validManifest(sampling, mapping).put("schemaVersion", 3);
            try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(invalid))) {
                put(zip, "manifest.json", manifest.toString());
                put(zip, "sampling.bin", sampling);
                put(zip, "sampling-mapping.bin", mapping);
            }
            StackArtifact.open(invalid);
        } finally {
            invalid.delete();
        }
    }

    private static void put(ZipOutputStream zip, String name, String content) throws Exception {
        put(zip, name, content.getBytes(StandardCharsets.UTF_8));
    }

    private static void put(ZipOutputStream zip, String name, byte[] content) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content);
        zip.closeEntry();
    }

    private static JSONObject fileInfo(byte[] content) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
        StringBuilder hash = new StringBuilder(64);
        for (byte value : digest) {
            hash.append(String.format("%02x", value & 0xff));
        }
        return new JSONObject().put("size", content.length).put("sha256", hash.toString());
    }

    private static JSONObject validManifest(byte[] sampling, byte[] mapping) throws Exception {
        return new JSONObject()
                .put("schemaVersion", 1)
                .put("artifactType", "RHEA_STACK")
                .put("samplingFormatVersion", 5)
                .put("byteOrder", "little-endian")
                .put("clock", "ELAPSED_REALTIME_NANOS")
                .put("selectionType", "ALL")
                .put("availableStartNs", 1)
                .put("availableEndNs", 2)
                .put("actualStartNs", 1)
                .put("actualEndNs", 2)
                .put("recordCount", 1)
                .put("processId", PROCESS_ID)
                .put("threadScope", "main")
                .put("files", new JSONObject()
                        .put("sampling", fileInfo(sampling))
                        .put("sampling-mapping", fileInfo(mapping)));
    }

    private static JSONObject validJankManifest(byte[] sampling, byte[] mapping)
            throws Exception {
        return new JSONObject()
                .put("schemaVersion", 3)
                .put("artifactType", "RHEA_JANK")
                .put("eventId", "jank-1")
                .put("occurredAt", 1000L)
                .put("sessionId", "session-1")
                .put("anonymousDeviceId", "device-anonymous")
                .put("packageName", "com.example.app")
                .put("appVersion", "1.0")
                .put("versionCode", 1L)
                .put("buildId", "release-1")
                .put("environment", "production")
                .put("channel", "official")
                .put("osVersion", "15")
                .put("deviceModel", "Pixel")
                .put("scene", "home")
                .put("messageStartNs", 100L)
                .put("messageEndNs", 400L)
                .put("thresholdNs", 200L)
                .put("minSampleIntervalNs", 5L)
                .put("attemptedSampleCount", 3L)
                .put("processId", PROCESS_ID)
                .put("threadScope", "main")
                .put("files", new JSONObject()
                        .put("sampling", fileInfo(sampling))
                        .put("sampling-mapping", fileInfo(mapping)));
    }

    private static void verifyInvalidJankManifest(String key, Object value) throws Exception {
        File invalid = File.createTempFile("rhea-jank-invalid", ".zip");
        byte[] sampling = "sample".getBytes(StandardCharsets.UTF_8);
        byte[] mapping = "mapping".getBytes(StandardCharsets.UTF_8);
        try {
            JSONObject manifest = validJankManifest(sampling, mapping);
            if (value == null) {
                manifest.remove(key);
            } else {
                manifest.put(key, value);
            }
            writeArtifact(invalid, manifest, sampling, mapping);
            StackArtifact.open(invalid);
        } finally {
            invalid.delete();
        }
    }

    private static void writeArtifact(File target, JSONObject manifest,
                                      byte[] sampling, byte[] mapping) throws Exception {
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(target))) {
            put(zip, "manifest.json", manifest.toString());
            put(zip, "sampling.bin", sampling);
            put(zip, "sampling-mapping.bin", mapping);
        }
    }

    /** ZipOutputStream 禁止重复名称，因此按 ZIP Stored 格式生成重复条目的安全回归样本。 */
    private static void writeStoredZip(File target, String... names) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        List<Integer> offsets = new ArrayList<>();
        List<byte[]> encodedNames = new ArrayList<>();
        List<byte[]> contents = new ArrayList<>();
        List<Long> checksums = new ArrayList<>();
        for (String name : names) {
            byte[] encodedName = name.getBytes(StandardCharsets.UTF_8);
            byte[] content = "{}".getBytes(StandardCharsets.UTF_8);
            CRC32 checksum = new CRC32();
            checksum.update(content);
            offsets.add(output.size());
            encodedNames.add(encodedName);
            contents.add(content);
            checksums.add(checksum.getValue());

            writeInt(output, 0x04034b50);
            writeShort(output, 20);
            writeShort(output, 0);
            writeShort(output, 0);
            writeShort(output, 0);
            writeShort(output, 0);
            writeInt(output, checksum.getValue());
            writeInt(output, content.length);
            writeInt(output, content.length);
            writeShort(output, encodedName.length);
            writeShort(output, 0);
            output.write(encodedName);
            output.write(content);
        }

        int centralOffset = output.size();
        for (int i = 0; i < names.length; i++) {
            byte[] encodedName = encodedNames.get(i);
            byte[] content = contents.get(i);
            writeInt(output, 0x02014b50);
            writeShort(output, 20);
            writeShort(output, 20);
            writeShort(output, 0);
            writeShort(output, 0);
            writeShort(output, 0);
            writeShort(output, 0);
            writeInt(output, checksums.get(i));
            writeInt(output, content.length);
            writeInt(output, content.length);
            writeShort(output, encodedName.length);
            writeShort(output, 0);
            writeShort(output, 0);
            writeShort(output, 0);
            writeShort(output, 0);
            writeInt(output, 0);
            writeInt(output, offsets.get(i));
            output.write(encodedName);
        }
        int centralSize = output.size() - centralOffset;
        writeInt(output, 0x06054b50);
        writeShort(output, 0);
        writeShort(output, 0);
        writeShort(output, names.length);
        writeShort(output, names.length);
        writeInt(output, centralSize);
        writeInt(output, centralOffset);
        writeShort(output, 0);
        try (FileOutputStream stream = new FileOutputStream(target)) {
            output.writeTo(stream);
        }
    }

    private static void writeShort(ByteArrayOutputStream output, int value) {
        output.write(value & 0xff);
        output.write((value >>> 8) & 0xff);
    }

    private static void writeInt(ByteArrayOutputStream output, long value) {
        output.write((int) value & 0xff);
        output.write((int) (value >>> 8) & 0xff);
        output.write((int) (value >>> 16) & 0xff);
        output.write((int) (value >>> 24) & 0xff);
    }
}
