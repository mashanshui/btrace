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
package com.bytedance.rheatrace.jank;

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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class JankAnalyzerTest {

    @Test
    public void pointSamplesAreMergedIntoOneTreeWithEstimatedDurations() throws Exception {
        List<Record> records = Arrays.asList(
                Record.point(100, 1000, "A", "B"),
                Record.point(200, 1000, "A", "C"),
                Record.point(350, 1000, "A", "C"));
        File artifact = createArtifact(1000, 100, 300, 0, records,
                mapOf("A", 1L, "B", 2L, "C", 3L));
        try {
            JSONObject report = new JankAnalyzer().analyze(artifact, null).getReport();
            Assert.assertEquals(2, report.getInt("schemaVersion"));
            JSONObject complete = report.getJSONObject("completeStack");
            Assert.assertEquals(1000, complete.getInt("tid"));
            JSONObject a = method(complete.getJSONArray("methods"), "A");
            Assert.assertEquals(200L, a.getLong("durationNs"));
            Assert.assertEquals(200L, a.getLong("estimatedDurationNs"));
            Assert.assertEquals("estimated", a.getString("durationSource"));
            Assert.assertEquals(100L,
                    method(a.getJSONArray("children"), "B").getLong("durationNs"));
            Assert.assertEquals(100L,
                    method(a.getJSONArray("children"), "C").getLong("durationNs"));
            Assert.assertEquals(0L, a.getLong("exactDurationNs"));
        } finally {
            Assert.assertTrue(artifact.delete());
        }
    }

    @Test
    public void durationHookIsClippedAndSharedByEveryFrame() throws Exception {
        List<Record> records = Arrays.asList(
                Record.duration(100, 300, 1000, "A", "B"),
                Record.point(400, 1000, "A"));
        File artifact = createArtifact(1000, 150, 350, 0, records,
                mapOf("A", 1L, "B", 2L));
        try {
            JSONObject report = new JankAnalyzer().analyze(artifact, null).getReport();
            JSONObject a = method(report.getJSONObject("completeStack").getJSONArray("methods"), "A");
            JSONObject b = method(a.getJSONArray("children"), "B");
            Assert.assertEquals(200L, a.getLong("durationNs"));
            Assert.assertEquals(150L, a.getLong("exactDurationNs"));
            Assert.assertEquals(50L, a.getLong("estimatedDurationNs"));
            Assert.assertEquals("mixed", a.getString("durationSource"));
            Assert.assertEquals(150L, b.getLong("durationNs"));
            Assert.assertEquals(150L, b.getLong("exactDurationNs"));
            Assert.assertEquals(0L, b.getLong("estimatedDurationNs"));
            Assert.assertEquals("exact", b.getString("durationSource"));
        } finally {
            Assert.assertTrue(artifact.delete());
        }
    }

    @Test
    public void preRollSeedsStackButDoesNotAddToMethodDuration() throws Exception {
        List<Record> records = Arrays.asList(
                Record.point(100, 1000, "A", "B"),
                Record.point(250, 1000, "A", "B"),
                Record.point(400, 1000, "A", "B"));
        File artifact = createArtifact(1000, 200, 300, 1, records,
                mapOf("A", 1L, "B", 2L));
        try {
            JSONObject report = new JankAnalyzer().analyze(artifact, null).getReport();
            JSONObject a = method(report.getJSONObject("completeStack").getJSONArray("methods"), "A");
            Assert.assertEquals(100L, a.getLong("durationNs"));
            Assert.assertEquals(100L,
                    method(a.getJSONArray("children"), "B").getLong("durationNs"));
            Assert.assertFalse(report.getJSONObject("durationSemantics")
                    .getBoolean("preRollIncludedInDuration"));
        } finally {
            Assert.assertTrue(artifact.delete());
        }
    }

    @Test
    public void missingProcessIdSelectsLongestThreadAndKeepsOtherThreads() throws Exception {
        List<Record> records = Arrays.asList(
                Record.point(100, 10, "A"),
                Record.point(250, 10, "A"),
                Record.point(100, 20, "D", "E"),
                Record.point(150, 20, "D", "F"),
                Record.point(250, 20, "D", "F"));
        File artifact = createArtifact(0, 100, 200, 0, records,
                mapOf("A", 1L, "D", 4L, "E", 5L, "F", 6L));
        try {
            JSONObject report = new JankAnalyzer().analyze(artifact, null).getReport();
            Assert.assertEquals(20, report.getJSONObject("completeStack").getInt("tid"));
            Assert.assertEquals(1, report.getJSONArray("otherThreads").length());
            Assert.assertTrue(report.getJSONArray("warnings").toString().contains("缺少主线程"));
        } finally {
            Assert.assertTrue(artifact.delete());
        }
    }

    @Test
    public void proguardNamesAreAppliedBeforeTreeAggregation() throws Exception {
        List<Record> records = Arrays.asList(
                Record.point(100, 1000, "void a.b()"),
                Record.point(250, 1000, "void a.b()"));
        File artifact = createArtifact(1000, 100, 200, 0, records,
                mapOf("void a.b()", 1L));
        File proguard = File.createTempFile("rhea-jank", ".mapping");
        try (FileOutputStream output = new FileOutputStream(proguard)) {
            output.write("com.example.Real -> a:\n    void doWork() -> b\n"
                    .getBytes(StandardCharsets.UTF_8));
        }
        try {
            JSONObject report = new JankAnalyzer().analyze(artifact, proguard).getReport();
            JSONObject method = method(report.getJSONObject("completeStack")
                    .getJSONArray("methods"), "com.example.Real.doWork()");
            Assert.assertEquals(100L, method.getLong("durationNs"));
        } finally {
            Assert.assertTrue(artifact.delete());
            Assert.assertTrue(proguard.delete());
        }
    }

    private static JSONObject method(JSONArray methods, String name) throws Exception {
        for (int i = 0; i < methods.length(); i++) {
            JSONObject item = methods.getJSONObject(i);
            if (name.equals(item.getString("name"))) {
                return item;
            }
        }
        Assert.fail("method not found: " + name);
        return null;
    }

    private static File createArtifact(int processId, long eventStart, long eventEnd,
                                       long preRollMs, List<Record> records,
                                       Map<String, Long> mapping) throws Exception {
        File artifact = File.createTempFile("rhea-jank-analysis", ".zip");
        byte[] sampling = encodeSampling(processId, records, mapping);
        byte[] mappingBytes = encodeMapping(mapping, 1000, "main");
        JSONObject manifest = new JSONObject()
                .put("schemaVersion", 1)
                .put("samplingFormatVersion", 5)
                .put("eventId", "test-event")
                .put("eventStartElapsedRealtimeNanos", eventStart)
                .put("eventEndElapsedRealtimeNanos", eventEnd)
                .put("preRollMs", preRollMs)
                .put("processId", processId);
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
        buffer.putInt(0);
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
