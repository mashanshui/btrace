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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class StackAnalyzerTest {

    private static final String PROCESS_ID = "123e4567-e89b-42d3-a456-426614174000";

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
            Assert.assertEquals(PROCESS_ID, report.getString("processId"));
            Assert.assertEquals(1, report.getJSONArray("threads").length());
            Assert.assertEquals("main", report.getJSONObject("renderDefaults")
                    .getString("thread"));
            Assert.assertEquals(2, report.getInt("pointSampleCount"));
            JSONObject thread = report.getJSONArray("threads").getJSONObject(0);
            Assert.assertEquals("main", thread.getString("threadName"));
            Assert.assertTrue(thread.getJSONArray("segments").getJSONObject(0)
                    .isNull("exactDurationNs"));
            JSONObject root = method(thread.getJSONArray("callTree"), "void app.A.run()");
            Assert.assertEquals(2, root.getInt("sampleCount"));
            Assert.assertTrue(root.isNull("exactDurationNs"));
            Assert.assertTrue(root.getLong("estimatedDurationNs") > 0);
        } finally {
            Assert.assertTrue(artifact.delete());
        }
    }

    @Test
    public void pointEstimationUsesNextSampleCapAndClippedLastSample() throws Exception {
        long ms = 1_000_000L;
        List<Record> records = Arrays.asList(
                Record.point(100 * ms, 1000, "A", "B"),
                Record.point(104 * ms, 1000, "A", "B"),
                Record.point(130 * ms, 1000, "A", "C"),
                Record.point(158 * ms, 1000, "A", "C"));
        File artifact = createArtifact(1000, 100 * ms, 160 * ms, records,
                mapOf("A", 1L, "B", 2L, "C", 3L), 5 * ms);
        try {
            JSONObject report = new StackAnalyzer().analyze(artifact, null).getReport();
            JSONObject policy = report.getJSONObject("estimationPolicy");
            Assert.assertEquals("MANIFEST", policy.getString("nominalIntervalSource"));
            Assert.assertEquals(10 * ms, policy.getLong("maxPointDurationNs"));
            JSONArray segments = report.getJSONArray("threads").getJSONObject(0)
                    .getJSONArray("segments");
            Assert.assertEquals(4 * ms,
                    segments.getJSONObject(0).getLong("estimatedDurationNs"));
            Assert.assertEquals("NEXT_SAMPLE",
                    segments.getJSONObject(0).getString("estimateSource"));
            Assert.assertEquals(10 * ms,
                    segments.getJSONObject(1).getLong("estimatedDurationNs"));
            Assert.assertEquals("CAPPED",
                    segments.getJSONObject(1).getString("estimateSource"));
            Assert.assertEquals(10 * ms,
                    segments.getJSONObject(2).getLong("estimatedDurationNs"));
            Assert.assertEquals(2 * ms,
                    segments.getJSONObject(3).getLong("estimatedDurationNs"));
            Assert.assertEquals("LAST_SAMPLE",
                    segments.getJSONObject(3).getString("estimateSource"));
            Assert.assertEquals("ESTIMATED",
                    segments.getJSONObject(3).getString("durationKind"));
            JSONObject root = method(report.getJSONArray("threads").getJSONObject(0)
                    .getJSONArray("callTree"), "A");
            Assert.assertEquals(26 * ms, root.getLong("estimatedDurationNs"));
            Assert.assertEquals(0, root.getLong("estimatedSelfDurationNs"));
        } finally {
            Assert.assertTrue(artifact.delete());
        }
    }

    @Test
    public void rejectsSamplingContainingMultipleThreads() throws Exception {
        long ms = 1_000_000L;
        List<Record> records = Arrays.asList(
                Record.point(100 * ms, 1000, "main.A"),
                Record.point(105 * ms, 2000, "worker.B"),
                Record.point(110 * ms, 2000, "worker.C"),
                Record.point(140 * ms, 1000, "main.D"));
        File artifact = createArtifact(1000, 100 * ms, 160 * ms, records,
                mapOf("main.A", 1L, "worker.B", 2L, "worker.C", 3L,
                        "main.D", 4L), 5 * ms);
        try {
            try {
                new StackAnalyzer().analyze(artifact, null);
                Assert.fail("online artifact containing multiple threads should be rejected");
            } catch (IOException expected) {
                Assert.assertTrue(expected.getMessage().contains("一个线程"));
            }
        } finally {
            Assert.assertTrue(artifact.delete());
        }
    }

    @Test
    public void missingSampleIntervalFallsBackToTenMilliseconds() throws Exception {
        long ms = 1_000_000L;
        File artifact = createArtifact(1000, 100 * ms, 150 * ms,
                Arrays.asList(Record.point(120 * ms, 1000, "A")),
                mapOf("A", 1L));
        try {
            JSONObject report = new StackAnalyzer().analyze(artifact, null).getReport();
            Assert.assertEquals("DEFAULT_10_MS", report.getJSONObject("estimationPolicy")
                    .getString("nominalIntervalSource"));
            JSONObject segment = report.getJSONArray("threads").getJSONObject(0)
                    .getJSONArray("segments").getJSONObject(0);
            Assert.assertEquals(10 * ms, segment.getLong("estimatedDurationNs"));
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
            JSONObject exact = thread.getJSONArray("segments").getJSONObject(0);
            Assert.assertEquals("EXACT", exact.getString("durationKind"));
            Assert.assertEquals("EXACT", exact.getString("estimateSource"));
            Assert.assertEquals(150L, exact.getLong("estimatedDurationNs"));
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
            Assert.assertEquals("estimated", report.getJSONObject("renderDefaults")
                    .getString("flameMetric"));
            new StackHtmlRenderer().write(report, html);
            String text = new String(java.nio.file.Files.readAllBytes(html.toPath()),
                    StandardCharsets.UTF_8);
            Assert.assertTrue(text.contains("method"));
            Assert.assertTrue(text.contains("耗时 (ms)"));
            Assert.assertTrue(text.contains("估算总耗时 (ms)"));
            Assert.assertTrue(text.contains("估算自耗时 (ms)"));
            Assert.assertTrue(text.contains("精确区间 (ms)"));
            Assert.assertTrue(text.contains("未归属估算耗时"));
            Assert.assertTrue(text.contains("不代表 CPU 自耗时"));
            Assert.assertTrue(text.contains("聚合火焰图"));
            Assert.assertTrue(text.contains("采样时间轴"));
            Assert.assertTrue(text.contains("宽度：估算耗时"));
            Assert.assertTrue(text.contains("宽度：样本数"));
            Assert.assertTrue(text.contains("timeline-axis"));
            Assert.assertTrue(text.contains("function buildTimelineSlices"));
            Assert.assertTrue(text.contains("start<=previous.end"));
            Assert.assertTrue(text.contains("previous.lastOrder===order-1"));
            Assert.assertTrue(text.contains("path.concat(identity)"));
            Assert.assertTrue(text.contains("timeline-sample-tick"));
            Assert.assertTrue(text.contains("公共调用前缀按真实时间连续合并"));
            Assert.assertFalse(text.contains("timeline-marker"));
            Assert.assertFalse(text.contains("SINGLE_SAMPLING_DURATION"));
            Assert.assertTrue(text.contains("MIN_CELL_PX=2"));
            Assert.assertTrue(text.contains("MAX_ZOOM=64"));
            Assert.assertTrue(text.contains("function zoomAt"));
            Assert.assertTrue(text.contains("pointerdown"));
            Assert.assertTrue(text.contains("Ctrl+滚轮缩放"));
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
        File callTree = File.createTempFile("rhea-stack-command-tree", ".json");
        File html = File.createTempFile("rhea-stack-command", ".html");
        try {
            StackMain.main(new String[]{"--input", artifact.getAbsolutePath(),
                    "--output", json.getAbsolutePath(), "--html", html.getAbsolutePath(),
                    "--call-tree-output", callTree.getAbsolutePath(),
                    "--thread", "main", "--sort", "duration"});
            Assert.assertEquals("RHEA_STACK_REPORT",
                    new JSONObject(new String(java.nio.file.Files.readAllBytes(json.toPath()),
                            StandardCharsets.UTF_8)).getString("artifactType"));
            Assert.assertEquals("RHEA_STACK_CALL_TREE",
                    new JSONObject(new String(java.nio.file.Files.readAllBytes(callTree.toPath()),
                            StandardCharsets.UTF_8)).getString("artifactType"));
            Assert.assertTrue(html.length() > 0);
        } finally {
            Assert.assertTrue(artifact.delete());
            Assert.assertTrue(json.delete());
            Assert.assertTrue(callTree.delete());
            Assert.assertTrue(html.delete());
        }
    }

    @Test
    public void callTreeJsonMatchesFullReportAndContainsMainThread() throws Exception {
        File artifact = createArtifact(1000, 100, 300,
                Arrays.asList(
                        Record.point(150, 1000, "A", "main.Child"),
                        Record.point(160, 1000, "A", "main.Other")),
                mapOf("A", 1L, "main.Child", 2L, "main.Other", 3L));
        try {
            StackAnalysisResult result = new StackAnalyzer().analyze(artifact, null);
            JSONObject report = result.getReport();
            String callTreeText = result.getCallTreeJson();
            Assert.assertEquals(callTreeText,
                    new StackAnalyzer().analyzeCallTree(artifact, null));
            Assert.assertTrue(callTreeText.startsWith("{"));
            Assert.assertTrue(callTreeText.contains("\n  \"schemaVersion\""));
            JSONObject callTree = new JSONObject(callTreeText);
            Assert.assertEquals("RHEA_STACK_CALL_TREE", callTree.getString("artifactType"));
            Assert.assertFalse(callTree.has("renderDefaults"));
            Assert.assertEquals(1, callTree.getJSONArray("threads").length());
            for (int i = 0; i < report.getJSONArray("threads").length(); i++) {
                JSONObject fullThread = report.getJSONArray("threads").getJSONObject(i);
                JSONObject treeThread = thread(callTree.getJSONArray("threads"),
                        fullThread.getInt("tid"));
                Assert.assertFalse(treeThread.has("segments"));
                Assert.assertEquals(fullThread.getJSONArray("callTree").toString(),
                        treeThread.getJSONArray("callTree").toString());
                assertEstimatedDurationFields(treeThread.getJSONArray("callTree"));
            }
        } finally {
            Assert.assertTrue(artifact.delete());
        }
    }

    @Test
    public void standaloneCallTreeParserSkipsPerfettoAndReturnsFormattedJson() throws Exception {
        File artifact = createArtifact(1000, 100, 300,
                Arrays.asList(Record.point(150, 1000, "A")), mapOf("A", 1L));
        try {
            String text = new StackAnalyzer().analyzeCallTree(artifact, null);
            JSONObject callTree = new JSONObject(text);
            Assert.assertEquals("RHEA_STACK_CALL_TREE", callTree.getString("artifactType"));
            Assert.assertEquals(1, callTree.getJSONArray("threads").length());
            Assert.assertFalse(callTree.getJSONArray("threads").getJSONObject(0)
                    .has("segments"));
            assertEstimatedDurationFields(callTree.getJSONArray("threads")
                    .getJSONObject(0).getJSONArray("callTree"));
        } finally {
            Assert.assertTrue(artifact.delete());
        }
    }

    @Test
    public void analyzeStackCommandUsesDefaultCallTreeFileName() throws Exception {
        File artifact = createArtifact(1000, 100, 300,
                Arrays.asList(Record.point(150, 1000, "A")), mapOf("A", 1L));
        File json = File.createTempFile("rhea-stack-default", ".json");
        File callTree = new File(json.getParentFile(),
                json.getName().substring(0, json.getName().length() - 5)
                        + ".call-tree.json");
        try {
            Assert.assertTrue(callTree.delete() || !callTree.exists());
            StackMain.main(new String[]{"--input", artifact.getAbsolutePath(),
                    "--output", json.getAbsolutePath()});
            Assert.assertTrue(callTree.isFile());
            Assert.assertEquals("RHEA_STACK_CALL_TREE",
                    new JSONObject(new String(java.nio.file.Files.readAllBytes(callTree.toPath()),
                            StandardCharsets.UTF_8)).getString("artifactType"));
        } finally {
            Assert.assertTrue(artifact.delete());
            Assert.assertTrue(json.delete());
            Assert.assertTrue(callTree.delete());
        }
    }

    @Test
    public void analyzeStackCommandRejectsOutputPathConflict() throws Exception {
        File artifact = createArtifact(1000, 100, 300,
                Arrays.asList(Record.point(150, 1000, "A")), mapOf("A", 1L));
        try {
            try {
                StackMain.main(new String[]{"--input", artifact.getAbsolutePath(),
                        "--output", artifact.getAbsolutePath()});
                Assert.fail("input/output path conflict should be rejected");
            } catch (java.io.IOException expected) {
                Assert.assertTrue(expected.getMessage().contains("不能使用同一路径"));
            }
        } finally {
            Assert.assertTrue(artifact.delete());
        }
    }

    @Test
    public void rejectsSamplingProcessIdMismatch() throws Exception {
        File artifact = createArtifact(PROCESS_ID,
                "223e4567-e89b-42d3-a456-426614174000", 100, 300,
                Arrays.asList(Record.point(150, 1000, "A")), mapOf("A", 1L), null);
        try {
            try {
                new StackAnalyzer().analyze(artifact, null);
                Assert.fail("manifest and sampling processId mismatch should be rejected");
            } catch (IOException expected) {
                Assert.assertTrue(expected.getMessage().contains("processId"));
            }
        } finally {
            Assert.assertTrue(artifact.delete());
        }
    }

    @Test
    public void streamParserReturnsFullReportAndDoesNotCloseCallerInput() throws Exception {
        File artifact = createArtifact(1000, 100, 300,
                Arrays.asList(Record.point(150, 1000, "A", "B")),
                mapOf("A", 1L, "B", 2L));
        File temporaryDirectory = java.nio.file.Files.createTempDirectory(
                "rhea-stack-parser-test-").toFile();
        CloseTrackingInputStream input = new CloseTrackingInputStream(
                java.nio.file.Files.readAllBytes(artifact.toPath()));
        try {
            JSONObject expected = new StackAnalyzer().analyze(artifact, null).getReport();
            StackAnalyzer parser = new StackAnalyzer();
            JSONObject actual = new JSONObject(parser.parseUploadedArtifact(
                    input, null, temporaryDirectory));

            Assert.assertTrue(expected.similar(actual));
            Assert.assertEquals("RHEA_STACK_REPORT", actual.getString("artifactType"));
            JSONObject thread = actual.getJSONArray("threads").getJSONObject(0);
            Assert.assertTrue(thread.has("segments"));
            Assert.assertTrue(thread.has("callTree"));
            Assert.assertFalse(input.closed);
            assertDirectoryEmpty(temporaryDirectory);
        } finally {
            input.close();
            Assert.assertTrue(artifact.delete());
            Assert.assertTrue(temporaryDirectory.delete());
        }
    }

    @Test
    public void streamParserUsesOptionalProguardMapping() throws Exception {
        File artifact = createArtifact(1000, 100, 300,
                Arrays.asList(Record.point(150, 1000, "void a.b.a()")),
                mapOf("void a.b.a()", 1L));
        File proguardMapping = File.createTempFile("rhea-stack-proguard", ".txt");
        try {
            java.nio.file.Files.write(proguardMapping.toPath(), (
                    "com.example.RealClass -> a.b:\n"
                            + "    void work() -> a\n").getBytes(StandardCharsets.UTF_8));
            StackParser parser = new StackAnalyzer();
            JSONObject report;
            try (InputStream input = new java.io.FileInputStream(artifact)) {
                report = new JSONObject(parser.parse(input, proguardMapping));
            }
            JSONObject root = report.getJSONArray("threads").getJSONObject(0)
                    .getJSONArray("callTree").getJSONObject(0);
            Assert.assertEquals("com.example.RealClass.work()", root.getString("method"));
            Assert.assertTrue(proguardMapping.isFile());
        } finally {
            Assert.assertTrue(artifact.delete());
            Assert.assertTrue(proguardMapping.delete());
        }
    }

    @Test
    public void streamParserResolvesV3BuildIdAfterValidationAndPreservesSourceManifest()
            throws Exception {
        long start = 9_007_199_254_740_992L;
        File artifact = createJankArtifact(1000, start, start + 300,
                Arrays.asList(Record.point(start + 150, 1000, "void a.b.a()")),
                mapOf("void a.b.a()", 1L));
        File proguardMapping = File.createTempFile("rhea-stack-proguard-resolver", ".txt");
        File temporaryDirectory = java.nio.file.Files.createTempDirectory(
                "rhea-stack-parser-resolver-").toFile();
        java.nio.file.Files.write(proguardMapping.toPath(), (
                "com.example.RealClass -> a.b:\n"
                        + "    void work() -> a\n").getBytes(StandardCharsets.UTF_8));
        byte[] upload = java.nio.file.Files.readAllBytes(artifact.toPath());
        CloseTrackingInputStream input = new CloseTrackingInputStream(upload);
        AtomicInteger calls = new AtomicInteger();
        try {
            StackAnalyzer parser = new StackAnalyzer();
            JSONObject report = new JSONObject(parser.parseUploadedArtifactWithMappingResolver(
                    input, metadata -> {
                        Assert.assertEquals(3, metadata.getSchemaVersion());
                        Assert.assertEquals("RHEA_JANK", metadata.getArtifactType());
                        Assert.assertEquals("com.example.app", metadata.getAppId());
                        Assert.assertEquals("release-1", metadata.getMappingId());
                        calls.incrementAndGet();
                        return proguardMapping;
                    }, temporaryDirectory));

            Assert.assertEquals(1, calls.get());
            Assert.assertFalse(input.closed);
            Assert.assertTrue(proguardMapping.isFile());
            Assert.assertEquals(start,
                    report.getJSONObject("sourceManifest").getLong("messageStartNs"));
            Assert.assertEquals(start + 300,
                    report.getJSONObject("sourceManifest").getLong("messageEndNs"));
            Assert.assertEquals("release-1",
                    report.getJSONObject("sourceManifest").getString("buildId"));
            Assert.assertEquals(23, report.getJSONObject("sourceManifest").length());
            // sourceManifest is the complete v3 object; verify the nested file metadata too.
            JSONObject sourceManifest = report.getJSONObject("sourceManifest");
            for (String key : new String[]{
                    "schemaVersion", "artifactType", "eventId", "occurredAt", "sessionId",
                    "anonymousDeviceId", "packageName", "appVersion", "versionCode", "buildId",
                    "environment", "channel", "osVersion", "deviceModel", "scene",
                    "messageStartNs", "messageEndNs", "thresholdNs", "minSampleIntervalNs",
                    "attemptedSampleCount", "processId", "threadScope", "files"}) {
                Assert.assertTrue("missing sourceManifest field: " + key,
                        sourceManifest.has(key));
            }
            Assert.assertTrue(sourceManifest.getJSONObject("files")
                    .getJSONObject("sampling").getLong("size") > 0);
            Assert.assertEquals(64, sourceManifest.getJSONObject("files")
                    .getJSONObject("sampling").getString("sha256").length());
            JSONObject root = report.getJSONArray("threads").getJSONObject(0)
                    .getJSONArray("callTree").getJSONObject(0);
            Assert.assertEquals("com.example.RealClass.work()", root.getString("method"));
            assertDirectoryEmpty(temporaryDirectory);
        } finally {
            input.close();
            Assert.assertTrue(artifact.delete());
            Assert.assertTrue(proguardMapping.delete());
            Assert.assertTrue(temporaryDirectory.delete());
        }
    }

    @Test
    public void streamParserMappingResolverIsNotCalledForInvalidArtifact() throws Exception {
        File temporaryDirectory = java.nio.file.Files.createTempDirectory(
                "rhea-stack-parser-resolver-failure-").toFile();
        AtomicInteger calls = new AtomicInteger();
        try {
            try {
                new StackAnalyzer().parseUploadedArtifactWithMappingResolver(
                        new ByteArrayInputStream("not-a-zip".getBytes(StandardCharsets.UTF_8)),
                        metadata -> {
                            calls.incrementAndGet();
                            return null;
                        }, temporaryDirectory);
                Assert.fail("invalid ZIP should be rejected");
            } catch (IOException expected) {
                Assert.assertNotNull(expected.getMessage());
            }
            Assert.assertEquals(0, calls.get());
            assertDirectoryEmpty(temporaryDirectory);
        } finally {
            Assert.assertTrue(temporaryDirectory.delete());
        }
    }

    @Test
    public void streamParserMappingResolverUsesV1MappingId() throws Exception {
        File artifact = createArtifact(1000, 100, 300,
                Arrays.asList(Record.point(150, 1000, "A")), mapOf("A", 1L));
        AtomicInteger calls = new AtomicInteger();
        try (InputStream input = new java.io.FileInputStream(artifact)) {
            JSONObject report = new JSONObject(new StackAnalyzer().parseWithMappingResolver(
                    input, metadata -> {
                        Assert.assertEquals(1, metadata.getSchemaVersion());
                        Assert.assertEquals("RHEA_STACK", metadata.getArtifactType());
                        Assert.assertEquals("app", metadata.getAppId());
                        Assert.assertEquals("mapping-1", metadata.getMappingId());
                        calls.incrementAndGet();
                        return null;
                    }));
            Assert.assertEquals(1, calls.get());
            Assert.assertFalse(report.has("sourceManifest"));
        } finally {
            Assert.assertTrue(artifact.delete());
        }
    }

    @Test
    public void streamParserMappingResolverSupportsNullAndPropagatesFailures() throws Exception {
        File artifact = createJankArtifact(1000, 100, 400,
                Arrays.asList(Record.point(150, 1000, "A")), mapOf("A", 1L));
        byte[] upload = java.nio.file.Files.readAllBytes(artifact.toPath());
        try {
            JSONObject withoutMapping = new JSONObject(new StackAnalyzer()
                    .parseWithMappingResolver(new ByteArrayInputStream(upload), metadata -> null));
            Assert.assertEquals("A", withoutMapping.getJSONArray("threads").getJSONObject(0)
                    .getJSONArray("callTree").getJSONObject(0).getString("method"));

            try {
                new StackAnalyzer().parseWithMappingResolver(
                        new ByteArrayInputStream(upload), metadata -> {
                            throw new IOException("mapping registry unavailable");
                        });
                Assert.fail("resolver IOException should propagate");
            } catch (IOException expected) {
                Assert.assertTrue(expected.getMessage().contains("mapping registry"));
            }

            try {
                new StackAnalyzer().parseWithMappingResolver(
                        new ByteArrayInputStream(upload),
                        metadata -> new File("rhea-missing-resolved-mapping-" + System.nanoTime()));
                Assert.fail("unreadable mapping should be rejected");
            } catch (IOException expected) {
                Assert.assertTrue(expected.getMessage().contains("mapping"));
            }
        } finally {
            Assert.assertTrue(artifact.delete());
        }
    }

    @Test
    public void streamParserCleansTemporaryFileWhenParsingFails() throws Exception {
        File temporaryDirectory = java.nio.file.Files.createTempDirectory(
                "rhea-stack-parser-failure-").toFile();
        try {
            try {
                new StackAnalyzer().parseUploadedArtifact(
                        new ByteArrayInputStream("not-a-zip".getBytes(StandardCharsets.UTF_8)),
                        null, temporaryDirectory);
                Assert.fail("invalid ZIP should be rejected");
            } catch (IOException expected) {
                Assert.assertNotNull(expected.getMessage());
            }
            assertDirectoryEmpty(temporaryDirectory);
        } finally {
            Assert.assertTrue(temporaryDirectory.delete());
        }
    }

    @Test
    public void streamParserRejectsNullAndOversizeInput() throws Exception {
        StackParser parser = new StackAnalyzer();
        try {
            parser.parse(null);
            Assert.fail("null input should be rejected");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("artifactInput"));
        }

        try {
            parser.parse(new FixedLengthInputStream(StackArtifact.MAX_TOTAL_BYTES + 1));
            Assert.fail("oversize input should be rejected");
        } catch (IOException expected) {
            Assert.assertTrue(expected.getMessage().contains("超过大小限制"));
        }
    }

    @Test
    public void streamParserReportsMissingMappingAsIoFailure() throws Exception {
        File missing = new File(System.getProperty("java.io.tmpdir"),
                "rhea-missing-mapping-" + System.nanoTime() + ".txt");
        try {
            new StackAnalyzer().parse(new ByteArrayInputStream(new byte[0]), missing);
            Assert.fail("missing mapping should be rejected");
        } catch (IOException expected) {
            Assert.assertTrue(expected.getMessage().contains("mapping"));
        }
    }

    @Test
    public void streamParserSupportsConcurrentRequests() throws Exception {
        File artifact = createArtifact(1000, 100, 300,
                Arrays.asList(Record.point(150, 1000, "A")), mapOf("A", 1L));
        byte[] upload = java.nio.file.Files.readAllBytes(artifact.toPath());
        StackParser parser = new StackAnalyzer();
        ExecutorService executor = Executors.newFixedThreadPool(4);
        List<Future<String>> results = new ArrayList<>();
        try {
            for (int i = 0; i < 8; i++) {
                results.add(executor.submit(new Callable<String>() {
                    @Override
                    public String call() throws Exception {
                        return parser.parse(new ByteArrayInputStream(upload));
                    }
                }));
            }
            for (Future<String> result : results) {
                JSONObject report = new JSONObject(result.get());
                Assert.assertEquals("RHEA_STACK_REPORT", report.getString("artifactType"));
                Assert.assertEquals("A", report.getJSONArray("threads").getJSONObject(0)
                        .getJSONArray("callTree").getJSONObject(0).getString("method"));
            }
        } finally {
            executor.shutdownNow();
            Assert.assertTrue(artifact.delete());
        }
    }

    @Test
    public void analyzesJankV3WithCompatibleReportFields() throws Exception {
        File artifact = createJankArtifact(1000, 100, 400,
                Arrays.asList(Record.point(150, 1000, "A", "B")),
                mapOf("A", 1L, "B", 2L));
        try {
            JSONObject report = new StackAnalyzer().analyze(artifact, null).getReport();
            Assert.assertEquals("RANGE", report.getString("selectionType"));
            Assert.assertEquals("com.example.app", report.getString("appName"));
            Assert.assertEquals("release-1", report.getString("mappingId"));
            Assert.assertEquals(PROCESS_ID, report.getString("processId"));
            Assert.assertEquals("main",
                    report.getJSONArray("threads").getJSONObject(0).getString("threadName"));
            Assert.assertEquals(100L, report.getLong("requestedStartNs"));
            Assert.assertEquals(400L, report.getLong("requestedEndNs"));
            Assert.assertEquals("A", report.getJSONArray("threads").getJSONObject(0)
                    .getJSONArray("callTree").getJSONObject(0).getString("method"));
            Assert.assertTrue(report.has("sourceManifest"));
            JSONObject callTree = new JSONObject(new StackAnalyzer()
                    .analyzeCallTree(artifact, null));
            Assert.assertFalse(callTree.has("sourceManifest"));
        } finally {
            Assert.assertTrue(artifact.delete());
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

    private static JSONObject thread(JSONArray threads, int tid) throws Exception {
        for (int i = 0; i < threads.length(); i++) {
            JSONObject item = threads.getJSONObject(i);
            if (item.getInt("tid") == tid) {
                return item;
            }
        }
        Assert.fail("thread not found: " + tid);
        return null;
    }

    private static void assertEstimatedDurationFields(JSONArray nodes) throws Exception {
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject node = nodes.getJSONObject(i);
            Assert.assertTrue(node.has("estimatedDurationNs"));
            Assert.assertTrue(node.has("estimatedSelfDurationNs"));
            assertEstimatedDurationFields(node.getJSONArray("children"));
        }
    }

    private static File createArtifact(int ignoredProcessId, long eventStart, long eventEnd,
                                       List<Record> records,
                                       Map<String, Long> mapping) throws Exception {
        return createArtifact(PROCESS_ID, eventStart, eventEnd, records, mapping, null);
    }

    private static File createArtifact(int ignoredProcessId, long eventStart, long eventEnd,
                                       List<Record> records, Map<String, Long> mapping,
                                       Long minSampleIntervalNs) throws Exception {
        return createArtifact(PROCESS_ID, eventStart, eventEnd, records, mapping,
                minSampleIntervalNs);
    }

    private static File createArtifact(String processId, long eventStart, long eventEnd,
                                       List<Record> records, Map<String, Long> mapping,
                                       Long minSampleIntervalNs) throws Exception {
        return createArtifact(processId, processId, eventStart, eventEnd, records, mapping,
                minSampleIntervalNs);
    }

    private static File createArtifact(String processId, String samplingProcessId,
                                       long eventStart, long eventEnd, List<Record> records,
                                       Map<String, Long> mapping,
                                       Long minSampleIntervalNs) throws Exception {
        File artifact = File.createTempFile("rhea-stack-analysis", ".zip");
        byte[] sampling = encodeSampling(samplingProcessId, records, mapping);
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
                .put("mappingId", "mapping-1")
                .put("processId", processId)
                .put("threadScope", "main")
                .put("files", new JSONObject()
                        .put("sampling", fileInfo(sampling))
                        .put("sampling-mapping", fileInfo(mappingBytes)));
        if (minSampleIntervalNs != null) {
            manifest.put("minSampleIntervalNs", minSampleIntervalNs);
        }
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(artifact))) {
            put(zip, "manifest.json", manifest.toString().getBytes(StandardCharsets.UTF_8));
            put(zip, "sampling.bin", sampling);
            put(zip, "sampling-mapping.bin", mappingBytes);
        }
        return artifact;
    }

    private static File createJankArtifact(int ignoredProcessId, long eventStart, long eventEnd,
                                           List<Record> records,
                                           Map<String, Long> mapping) throws Exception {
        return createJankArtifact(PROCESS_ID, eventStart, eventEnd, records, mapping);
    }

    private static File createJankArtifact(String processId, long eventStart, long eventEnd,
                                           List<Record> records,
                                           Map<String, Long> mapping) throws Exception {
        File artifact = File.createTempFile("rhea-jank-analysis", ".zip");
        byte[] sampling = encodeSampling(processId, records, mapping);
        byte[] mappingBytes = encodeMapping(mapping, 1000, "main");
        JSONObject manifest = new JSONObject()
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
                .put("messageStartNs", eventStart)
                .put("messageEndNs", eventEnd)
                .put("thresholdNs", 100L)
                .put("minSampleIntervalNs", 5L)
                .put("attemptedSampleCount", 3L)
                .put("processId", processId)
                .put("threadScope", "main")
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

    private static byte[] encodeSampling(int ignoredProcessId, List<Record> records,
                                         Map<String, Long> mapping) {
        return encodeSampling(PROCESS_ID, records, mapping);
    }

    private static byte[] encodeSampling(String processId, List<Record> records,
                                         Map<String, Long> mapping) {
        byte[] extra = ("{\"processId\":\"" + processId
                + "\",\"threadScope\":\"main\"}")
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

    private static void assertDirectoryEmpty(File directory) {
        File[] children = directory.listFiles();
        Assert.assertNotNull(children);
        Assert.assertEquals(0, children.length);
    }

    private static final class CloseTrackingInputStream extends ByteArrayInputStream {
        boolean closed;

        CloseTrackingInputStream(byte[] buffer) {
            super(buffer);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }

    private static final class FixedLengthInputStream extends InputStream {
        private long remaining;

        FixedLengthInputStream(long length) {
            remaining = length;
        }

        @Override
        public int read() {
            if (remaining <= 0) {
                return -1;
            }
            remaining--;
            return 0;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            if (remaining <= 0) {
                return -1;
            }
            int count = (int) Math.min(remaining, length);
            remaining -= count;
            return count;
        }
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
