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

import com.bytedance.rheatrace.trace.SamplingTraceDecoder;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assert;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class StackAnalyzerTest {

    @Test
    public void pointSamplesHaveNoInventedDurationAndMergeIntoCallTree() throws Exception {
        List<Record> records = Arrays.asList(
                Record.point(100, 1000, "void app.A.run()", "void app.B.work()"),
                Record.point(200, 1000, "void app.A.run()", "void app.C.work()"));
        File artifact = createArtifact(1000, 100, 300, records,
                mapOf("void app.A.run()", 1L, "void app.B.work()", 2L,
                        "void app.C.work()", 3L));
        try {
            JSONObject report = new StackAnalyzer().analyze(artifact, null).getReport();
            Assert.assertEquals(2, report.getInt("pointSampleCount"));
            JSONObject thread = report.getJSONArray("threads").getJSONObject(0);
            Assert.assertTrue(thread.getJSONArray("segments").getJSONObject(0)
                    .isNull("exactDurationNs"));
            JSONObject root = method(thread.getJSONArray("callTree"), "void app.A.run()");
            Assert.assertEquals(2, root.getInt("sampleCount"));
            Assert.assertTrue(root.isNull("exactDurationNs"));
        } finally {
            Assert.assertTrue(artifact.delete());
        }
    }

    @Test
    public void durationHookIsClippedAndPointStartIsNotDuplicated() throws Exception {
        List<Record> records = Arrays.asList(
                Record.duration(100, 300, 1000, "A", "B"),
                Record.point(320, 1000, "A"));
        File artifact = createArtifact(1000, 150, 350, records,
                mapOf("A", 1L, "B", 2L));
        try {
            JSONObject report = new StackAnalyzer().analyze(artifact, null).getReport();
            Assert.assertEquals(1, report.getInt("exactRecordCount"));
            Assert.assertEquals(1, report.getInt("pointSampleCount"));
            Assert.assertEquals(150L, report.getLong("exactCoveredDurationNs"));
            JSONObject thread = report.getJSONArray("threads").getJSONObject(0);
            JSONObject a = method(thread.getJSONArray("callTree"), "A");
            Assert.assertEquals(150L, a.getLong("exactDurationNs"));
            Assert.assertEquals(0L, a.getLong("selfDurationNs"));
            Assert.assertEquals(150L,
                    method(a.getJSONArray("children"), "B").getLong("selfDurationNs"));
        } finally {
            Assert.assertTrue(artifact.delete());
        }
    }

    @Test
    public void htmlIsSelfContainedAndUsesTreeTableColumns() throws Exception {
        File artifact = createArtifact(1000, 100, 300,
                Arrays.asList(Record.point(150, 1000, "void app.A.run()")),
                mapOf("void app.A.run()", 1L));
        File html = File.createTempFile("rhea-stack-report", ".html");
        try {
            JSONObject report = new StackAnalyzer().analyze(artifact, null).getReport();
            Assert.assertEquals("flame", report.getJSONObject("renderDefaults")
                    .getString("view"));
            Assert.assertEquals("samples", report.getJSONObject("renderDefaults")
                    .getString("flameMetric"));
            new StackHtmlRenderer().write(report, html);
            String text = new String(java.nio.file.Files.readAllBytes(html.toPath()),
                    StandardCharsets.UTF_8);
            Assert.assertTrue(text.contains("method"));
            Assert.assertTrue(text.contains("耗时 (ms)"));
            Assert.assertTrue(text.contains("聚合火焰图"));
            Assert.assertTrue(text.contains("宽度：样本数"));
            Assert.assertTrue(text.contains("flame-cell"));
            Assert.assertTrue(text.contains("function flame"));
            Assert.assertTrue(text.contains("put(child,cx,childWidth,depth+1)"));
            Assert.assertTrue(text.contains("application/json"));
            Assert.assertFalse(text.contains("https://"));
        } finally {
            Assert.assertTrue(artifact.delete());
            Assert.assertTrue(html.delete());
        }
    }

    @Test
    public void analyzeStackCommandWritesJsonAndHtml() throws Exception {
        File artifact = createArtifact(1000, 100, 300,
                Arrays.asList(Record.point(150, 1000, "A")), mapOf("A", 1L));
        File json = File.createTempFile("rhea-stack-command", ".json");
        File html = File.createTempFile("rhea-stack-command", ".html");
        try {
            StackMain.main(new String[]{"--input", artifact.getAbsolutePath(),
                    "--output", json.getAbsolutePath(), "--html", html.getAbsolutePath(),
                    "--thread", "main", "--sort", "duration"});
            Assert.assertEquals("RHEA_STACK_REPORT",
                    new JSONObject(new String(java.nio.file.Files.readAllBytes(json.toPath()),
                            StandardCharsets.UTF_8)).getString("artifactType"));
            Assert.assertTrue(html.length() > 0);
        } finally {
            Assert.assertTrue(artifact.delete());
            Assert.assertTrue(json.delete());
            Assert.assertTrue(html.delete());
        }
    }

    @Test
    public void samplingDecoderRejectsInvalidMagic() throws Exception {
        Map<String, Long> mapping = mapOf("A", 1L);
        byte[] samplingBytes = encodeSampling(1000,
                Arrays.asList(Record.point(150, 1000, "A")), mapping);
        samplingBytes[0] = 0;
        File sampling = File.createTempFile("rhea-invalid-sampling", ".bin");
        File mappingFile = File.createTempFile("rhea-valid-mapping", ".bin");
        try {
            java.nio.file.Files.write(sampling.toPath(), samplingBytes);
            java.nio.file.Files.write(mappingFile.toPath(),
                    encodeMapping(mapping, 1000, "main"));
            try {
                SamplingTraceDecoder.decodeDetailed(sampling, mappingFile, "app", null);
                Assert.fail("invalid magic should be rejected");
            } catch (java.io.IOException expected) {
                Assert.assertTrue(expected.getMessage().contains("magic"));
            }
        } finally {
            Assert.assertTrue(sampling.delete());
            Assert.assertTrue(mappingFile.delete());
        }
    }

    private static JSONObject method(JSONArray methods, String name) throws Exception {
        for (int i = 0; i < methods.length(); i++) {
            JSONObject item = methods.getJSONObject(i);
            if (name.equals(item.getString("method"))) {
                return item;
            }
        }
        Assert.fail("method not found: " + name);
        return null;
    }

    private static File createArtifact(int processId, long eventStart, long eventEnd,
                                       List<Record> records,
                                       Map<String, Long> mapping) throws Exception {
        File artifact = File.createTempFile("rhea-stack-analysis", ".zip");
        byte[] sampling = encodeSampling(processId, records, mapping);
        byte[] mappingBytes = encodeMapping(mapping, 1000, "main");
        JSONObject manifest = new JSONObject()
                .put("schemaVersion", 1)
                .put("artifactType", "RHEA_STACK")
                .put("samplingFormatVersion", 5)
                .put("byteOrder", "little-endian")
                .put("clock", "ELAPSED_REALTIME_NANOS")
                .put("selectionType", "RANGE")
                .put("requestedStartNs", eventStart)
                .put("requestedEndNs", eventEnd)
                .put("availableStartNs", eventStart)
                .put("availableEndNs", eventEnd)
                .put("actualStartNs", eventStart)
                .put("actualEndNs", eventEnd)
                .put("recordCount", records.size())
                .put("appName", "app")
                .put("processId", processId)
                .put("files", new JSONObject()
                        .put("sampling", fileInfo(sampling))
                        .put("sampling-mapping", fileInfo(mappingBytes)));
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(artifact))) {
            put(zip, "manifest.json", manifest.toString().getBytes(StandardCharsets.UTF_8));
            put(zip, "sampling.bin", sampling);
            put(zip, "sampling-mapping.bin", mappingBytes);
        }
        return artifact;
    }

    private static byte[] encodeSampling(int processId, List<Record> records,
                                         Map<String, Long> mapping) {
        byte[] extra = ("{\"processId\":" + processId + "}")
                .getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(64 * 1024).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(0x01020304);
        buffer.putInt(0);
        buffer.putInt(5);
        buffer.putLong(0);
        buffer.putInt(records.size());
        buffer.putInt(extra.length);
        buffer.put(extra);
        for (Record record : records) {
            buffer.putShort((short) record.type);
            buffer.putShort((short) record.tid);
            buffer.putInt(0);
            buffer.putLong(record.time);
            buffer.putLong(record.endTime);
            buffer.putLong(record.time);
            buffer.putLong(record.endTime);
            buffer.putLong(0);
            buffer.putLong(0);
            buffer.putInt(0);
            buffer.putInt(0);
            buffer.putInt(0);
            buffer.putInt(record.names.length);
            buffer.putInt(record.names.length);
            for (int i = record.names.length - 1; i >= 0; i--) {
                buffer.putLong(mapping.get(record.names[i]));
            }
        }
        byte[] result = new byte[buffer.position()];
        buffer.flip();
        buffer.get(result);
        return result;
    }

    private static byte[] encodeMapping(Map<String, Long> mapping, int tid, String threadName) {
        int size = 8 + 4 + 4;
        for (Map.Entry<String, Long> entry : mapping.entrySet()) {
            size += 8 + 2 + entry.getKey().getBytes(StandardCharsets.UTF_8).length;
        }
        byte[] thread = threadName.getBytes(StandardCharsets.UTF_8);
        size += 2 + 1 + thread.length;
        ByteBuffer buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putLong(0);
        buffer.putInt(1);
        buffer.putInt(mapping.size());
        for (Map.Entry<String, Long> entry : mapping.entrySet()) {
            byte[] name = entry.getKey().getBytes(StandardCharsets.UTF_8);
            buffer.putLong(entry.getValue());
            buffer.putShort((short) name.length);
            buffer.put(name);
        }
        buffer.putShort((short) tid);
        buffer.put((byte) thread.length);
        buffer.put(thread);
        return buffer.array();
    }

    private static void put(ZipOutputStream zip, String name, byte[] bytes) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }

    private static Map<String, Long> mapOf(Object... values) {
        Map<String, Long> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) {
            result.put((String) values[i], (Long) values[i + 1]);
        }
        return result;
    }

    private static JSONObject fileInfo(byte[] content) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
        StringBuilder hash = new StringBuilder(64);
        for (byte value : digest) {
            hash.append(String.format("%02x", value & 0xff));
        }
        return new JSONObject().put("size", content.length).put("sha256", hash.toString());
    }

    private static final class Record {
        final long time;
        final long endTime;
        final int tid;
        final int type;
        final String[] names;

        private Record(long time, long endTime, int tid, int type, String[] names) {
            this.time = time;
            this.endTime = endTime;
            this.tid = tid;
            this.type = type;
            this.names = names;
        }

        static Record point(long time, int tid, String... names) {
            return new Record(time, 0, tid, 5, names);
        }

        static Record duration(long start, long end, int tid, String... names) {
            return new Record(start, end, tid, 4, names);
        }
    }
}
