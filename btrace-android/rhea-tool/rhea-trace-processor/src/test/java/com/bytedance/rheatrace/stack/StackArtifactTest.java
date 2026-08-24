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

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class StackArtifactTest {

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
}
