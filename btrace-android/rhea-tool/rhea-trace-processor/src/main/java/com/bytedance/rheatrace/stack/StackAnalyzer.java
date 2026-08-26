/*
 * Copyright (C) 2021 ByteDance Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.bytedance.rheatrace.stack;

import com.bytedance.rheatrace.perfetto.Trace;
import com.bytedance.rheatrace.trace.CallNode;
import com.bytedance.rheatrace.trace.SamplingTraceDecoder;
import com.bytedance.rheatrace.trace.StackList;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 将通用线上堆栈产物解析为时间段列表和前缀合并调用树。 */
public final class StackAnalyzer {

    private static final long DEFAULT_SAMPLE_INTERVAL_NS = 10_000_000L;
    private static final int ESTIMATE_CAP_MULTIPLIER = 2;

    private static final Pattern SOURCE_POSITION = Pattern.compile(
            ".*\\(([^():]+\\.(?:java|kt)):(\\d+)\\)$");

    private static final class Interval {
        final long start;
        final long end;

        Interval(long start, long end) {
            this.start = start;
            this.end = end;
        }
    }

    private static final class Segment {
        final long start;
        final Long end;
        final int type;
        final List<String> frames;
        long estimatedEnd;
        String durationKind;
        String estimateSource;

        Segment(long start, Long end, int type, List<String> frames) {
            this.start = start;
            this.end = end;
            this.type = type;
            this.frames = frames;
            if (exact()) {
                estimatedEnd = end;
                durationKind = "EXACT";
                estimateSource = "EXACT";
            }
        }

        boolean exact() {
            return end != null && end > start;
        }

        long estimatedDuration() {
            return Math.max(0, estimatedEnd - start);
        }
    }

    private static final class TreeNode {
        final String method;
        final Map<String, TreeNode> children = new LinkedHashMap<>();
        final List<Interval> exactIntervals = new ArrayList<>();
        final List<Interval> estimatedIntervals = new ArrayList<>();
        final Set<String> eventTypes = new LinkedHashSet<>();
        int sampleCount;

        TreeNode(String method) {
            this.method = method;
        }

        void add(List<String> frames, int index, Segment segment) {
            sampleCount++;
            eventTypes.add(CallNode.getType(segment.type));
            if (segment.exact()) {
                exactIntervals.add(new Interval(segment.start, segment.end));
            }
            if (segment.estimatedEnd > segment.start) {
                estimatedIntervals.add(new Interval(segment.start, segment.estimatedEnd));
            }
            if (index + 1 < frames.size()) {
                String childName = frames.get(index + 1);
                TreeNode child = children.get(childName);
                if (child == null) {
                    child = new TreeNode(childName);
                    children.put(childName, child);
                }
                child.add(frames, index + 1, segment);
            }
        }
    }

    private static final class ThreadData {
        final int tid;
        final String name;
        final List<Segment> segments = new ArrayList<>();
        final Map<String, TreeNode> roots = new LinkedHashMap<>();

        ThreadData(int tid, String name) {
            this.tid = tid;
            this.name = name;
        }

        void add(Segment segment) {
            if (segment.frames.isEmpty()) {
                return;
            }
            segments.add(segment);
        }

        void buildTree() {
            roots.clear();
            for (Segment segment : segments) {
                if (segment.frames.isEmpty()) {
                    continue;
                }
                String rootName = segment.frames.get(0);
                TreeNode root = roots.get(rootName);
                if (root == null) {
                    root = new TreeNode(rootName);
                    roots.put(rootName, root);
                }
                root.add(segment.frames, 0, segment);
            }
        }
    }

    private static final class AnalysisData {
        final JSONObject manifest;
        final long windowStart;
        final long windowEnd;
        final int processId;
        final int pointCount;
        final int exactCount;
        final List<Interval> allExact;
        final List<Interval> allEstimated;
        final List<ThreadData> threads;
        final JSONArray warnings;
        final JSONObject estimationPolicy;

        AnalysisData(JSONObject manifest, long windowStart, long windowEnd,
                     int processId,
                     int pointCount, int exactCount, List<Interval> allExact,
                     List<Interval> allEstimated, List<ThreadData> threads,
                     JSONArray warnings, JSONObject estimationPolicy) {
            this.manifest = manifest;
            this.windowStart = windowStart;
            this.windowEnd = windowEnd;
            this.processId = processId;
            this.pointCount = pointCount;
            this.exactCount = exactCount;
            this.allExact = allExact;
            this.allEstimated = allEstimated;
            this.threads = threads;
            this.warnings = warnings;
            this.estimationPolicy = estimationPolicy;
        }
    }

    private static final class ParsedAnalysis {
        final AnalysisData data;
        final Trace trace;

        ParsedAnalysis(AnalysisData data, Trace trace) {
            this.data = data;
            this.trace = trace;
        }
    }

    public StackAnalysisResult analyze(StackAnalysisRequest request) throws IOException {
        ParsedAnalysis parsed = parse(request, true);
        try {
            JSONObject report = buildReport(parsed.data, request);
            String callTreeJson = buildCallTreeReport(parsed.data).toString(2);
            return new StackAnalysisResult(report, callTreeJson, parsed.trace);
        } catch (JSONException e) {
            throw new IOException("生成堆栈报告失败", e);
        }
    }

    /** 只解析并返回聚合调用树 JSON，不构建 Perfetto Trace。 */
    public String analyzeCallTree(StackAnalysisRequest request) throws IOException {
        ParsedAnalysis parsed = parse(request, false);
        try {
            return buildCallTreeReport(parsed.data).toString(2);
        } catch (JSONException e) {
            throw new IOException("生成聚合调用树 JSON 失败", e);
        }
    }

    public StackAnalysisResult analyze(File artifact, File proguardMapping) throws IOException {
        return analyze(StackAnalysisRequest.builder(artifact)
                .setProguardMapping(proguardMapping).build());
    }

    public String analyzeCallTree(File artifact, File proguardMapping) throws IOException {
        return analyzeCallTree(StackAnalysisRequest.builder(artifact)
                .setProguardMapping(proguardMapping).build());
    }

    private ParsedAnalysis parse(StackAnalysisRequest request, boolean buildTrace)
            throws IOException {
        try (StackArtifact artifact = StackArtifact.open(request.getInput())) {
            JSONObject manifest = artifact.getManifest();
            String appName = manifest.optString("appName", "online");
            SamplingTraceDecoder.DecodedSampling decoded = SamplingTraceDecoder.decodeDetailed(
                    artifact.getSamplingFile(), artifact.getMappingFile(), appName,
                    request.getProguardMapping(), buildTrace);
            if (decoded.getFormatVersion() != manifest.getInt("samplingFormatVersion")) {
                throw new IOException("manifest 与 sampling 格式版本不一致");
            }
            if (decoded.getRawRecordCount() != manifest.getInt("recordCount")) {
                throw new IOException("manifest 与 sampling 记录数不一致");
            }
            return new ParsedAnalysis(buildAnalysisData(manifest, decoded),
                    decoded.getTrace());
        } catch (JSONException e) {
            throw new IOException("生成堆栈报告失败", e);
        }
    }

    private AnalysisData buildAnalysisData(JSONObject manifest,
                                           SamplingTraceDecoder.DecodedSampling decoded)
            throws JSONException {
        long windowStart = manifest.getLong("actualStartNs");
        long windowEnd = manifest.getLong("actualEndNs");
        long configuredInterval = manifest.optLong("minSampleIntervalNs", 0);
        boolean intervalFromManifest = configuredInterval > 0;
        long nominalInterval = intervalFromManifest
                ? configuredInterval : DEFAULT_SAMPLE_INTERVAL_NS;
        long maxPointDuration = nominalInterval > Long.MAX_VALUE / ESTIMATE_CAP_MULTIPLIER
                ? Long.MAX_VALUE : nominalInterval * ESTIMATE_CAP_MULTIPLIER;
        int processId = decoded.getExtra().optInt(
                "processId", manifest.optInt("processId", 0));

        Set<String> exactStarts = new LinkedHashSet<>();
        for (StackList item : decoded.getItems()) {
            if (isExact(item)) {
                exactStarts.add(exactKey(item.getTid(), item.getType(),
                        item.getNanoTime() - item.getDuration(), item.getNames()));
            }
        }

        Map<Integer, ThreadData> byThread = new LinkedHashMap<>();
        int pointCount = 0;
        int exactCount = 0;
        List<Interval> allExact = new ArrayList<>();
        for (StackList item : decoded.getItems()) {
            List<String> frames = item.getNames();
            if (frames.isEmpty()) {
                continue;
            }
            Segment segment;
            if (isExact(item)) {
                long start = Math.max(windowStart, item.getNanoTime() - item.getDuration());
                long end = Math.min(windowEnd, item.getNanoTime());
                if (end <= start) {
                    continue;
                }
                segment = new Segment(start, end, item.getType(), frames);
                exactCount++;
                allExact.add(new Interval(start, end));
            } else {
                long time = item.getNanoTime();
                if (time < windowStart || time >= windowEnd
                        || exactStarts.contains(exactKey(
                        item.getTid(), item.getType(), time, frames))) {
                    continue;
                }
                segment = new Segment(time, null, item.getType(), frames);
                pointCount++;
            }
            ThreadData thread = byThread.get(item.getTid());
            if (thread == null) {
                String name = item.getTid() == processId
                        ? "main" : decoded.getThreadNames().get(item.getTid());
                if (name == null || name.trim().isEmpty()) {
                    name = item.getTid() == processId ? "main" : "Thread-" + item.getTid();
                }
                thread = new ThreadData(item.getTid(), name.trim());
                byThread.put(item.getTid(), thread);
            }
            thread.add(segment);
        }

        List<ThreadData> threads = new ArrayList<>(byThread.values());
        threads.sort((left, right) -> {
            if (left.tid == processId) return -1;
            if (right.tid == processId) return 1;
            return Integer.compare(left.tid, right.tid);
        });

        List<Interval> allEstimated = new ArrayList<>();
        for (ThreadData thread : threads) {
            thread.segments.sort(Comparator.comparingLong(segment -> segment.start));
            estimatePointDurations(thread.segments, windowEnd,
                    nominalInterval, maxPointDuration);
            thread.buildTree();
            for (Segment segment : thread.segments) {
                if (segment.estimatedEnd > segment.start) {
                    allEstimated.add(new Interval(segment.start, segment.estimatedEnd));
                }
            }
        }

        JSONArray warnings = new JSONArray();
        if (threads.isEmpty()) {
            warnings.put("导出窗口内没有可解析堆栈");
        }
        if (pointCount > 0) {
            warnings.put("点采样只表示该时刻观察到的调用栈，耗时显示为 --");
        }
        if (manifest.optBoolean("partial", false)) {
            warnings.put("请求时间范围只有部分数据仍保留在环形缓冲区");
        }
        if (manifest.optLong("overwrittenRecordCount", 0) > 0) {
            warnings.put("环形缓冲区曾覆盖旧记录");
        }

        JSONObject estimationPolicy = new JSONObject()
                .put("pointPolicy", "UNTIL_NEXT_SAMPLE_CAPPED")
                .put("nominalIntervalNs", nominalInterval)
                .put("nominalIntervalSource",
                        intervalFromManifest ? "MANIFEST" : "DEFAULT_10_MS")
                .put("maxPointDurationNs", maxPointDuration)
                .put("maxIntervalMultiplier", ESTIMATE_CAP_MULTIPLIER)
                .put("lastPointDurationNs", nominalInterval);
        return new AnalysisData(manifest, windowStart, windowEnd, processId,
                pointCount, exactCount, allExact,
                allEstimated, threads, warnings, estimationPolicy);
    }

    private JSONObject buildReport(AnalysisData data, StackAnalysisRequest request)
            throws JSONException {
        JSONArray threadJson = new JSONArray();
        int[] ids = {1};
        for (ThreadData thread : data.threads) {
            threadJson.put(threadToJson(thread, data.windowStart, ids));
        }
        JSONObject report = buildCommonReport(data, "RHEA_STACK_REPORT");
        report.put("renderDefaults", new JSONObject()
                .put("thread", request.getThread())
                .put("sort", request.getSort())
                .put("flameMetric", "estimated")
                .put("view", "flame"));
        report.put("threads", threadJson);
        report.put("warnings", data.warnings);
        return report;
    }

    private JSONObject buildCallTreeReport(AnalysisData data) throws JSONException {
        JSONArray threadJson = new JSONArray();
        int[] ids = {1};
        for (ThreadData thread : data.threads) {
            threadJson.put(treeThreadToJson(thread, ids));
        }
        JSONObject report = buildCommonReport(data, "RHEA_STACK_CALL_TREE");
        report.put("threads", threadJson);
        report.put("warnings", data.warnings);
        return report;
    }

    private static JSONObject buildCommonReport(AnalysisData data, String artifactType)
            throws JSONException {
        JSONObject manifest = data.manifest;
        JSONObject report = new JSONObject();
        report.put("schemaVersion", 1);
        report.put("artifactType", artifactType);
        report.put("selectionType", manifest.getString("selectionType"));
        report.put("appName", manifest.optString("appName", ""));
        report.put("mappingId", manifest.optString("mappingId", ""));
        report.put("processId", data.processId);
        report.put("requestedStartNs", manifest.opt("requestedStartNs"));
        report.put("requestedEndNs", manifest.opt("requestedEndNs"));
        report.put("availableStartNs", manifest.optLong("availableStartNs", data.windowStart));
        report.put("availableEndNs", manifest.optLong("availableEndNs", data.windowEnd));
        report.put("actualStartNs", data.windowStart);
        report.put("actualEndNs", data.windowEnd);
        report.put("durationNs", data.windowEnd - data.windowStart);
        report.put("recordCount", manifest.optInt("recordCount",
                data.pointCount + data.exactCount));
        report.put("pointSampleCount", data.pointCount);
        report.put("exactRecordCount", data.exactCount);
        report.put("exactCoveredDurationNs", mergeDuration(data.allExact));
        report.put("estimatedCoveredDurationNs", mergeDuration(data.allEstimated));
        report.put("estimationPolicy", data.estimationPolicy);
        report.put("overwrittenRecordCount",
                manifest.optLong("overwrittenRecordCount", 0));
        report.put("droppedByRateLimit", manifest.optLong("droppedByRateLimit", 0));
        report.put("durationSemantics", new JSONObject()
                .put("exactDurationNs", "仅来自带开始和结束时间的 duration hook，并裁剪到导出窗口")
                .put("selfDurationNs", "当前节点精确区间并集扣除直接子节点精确区间并集，表示未归属区间而非 CPU 自耗时")
                .put("pointSampleDuration", JSONObject.NULL)
                .put("pointSamplesContinuous", false));
        return report;
    }

    private static JSONObject threadToJson(ThreadData thread, long windowStart, int[] ids)
            throws JSONException {
        JSONArray segments = new JSONArray();
        for (Segment segment : thread.segments) {
            JSONObject item = new JSONObject();
            item.put("id", "segment-" + ids[0]++);
            item.put("startOffsetNs", segment.start - windowStart);
            item.put("endOffsetNs", segment.end == null
                    ? JSONObject.NULL : segment.end - windowStart);
            item.put("exactDurationNs", segment.exact()
                    ? segment.end - segment.start : JSONObject.NULL);
            item.put("estimatedEndOffsetNs", segment.estimatedEnd - windowStart);
            item.put("estimatedDurationNs", segment.estimatedDuration());
            item.put("durationKind", segment.durationKind);
            item.put("estimateSource", segment.estimateSource);
            item.put("sampleCount", 1);
            item.put("eventType", CallNode.getType(segment.type));
            JSONArray stack = new JSONArray();
            for (String frame : segment.frames) {
                stack.put(frameToJson(frame, ids));
            }
            item.put("stack", stack);
            segments.put(item);
        }

        JSONArray roots = new JSONArray();
        for (TreeNode root : thread.roots.values()) {
            roots.put(treeToJson(root, ids));
        }
        return new JSONObject()
                .put("tid", thread.tid)
                .put("threadName", thread.name)
                .put("sampleCount", thread.segments.size())
                .put("estimatedCoveredDurationNs", estimatedCoveredDuration(thread.segments))
                .put("segments", segments)
                .put("callTree", roots);
    }

    /**
     * 只序列化调用树。先预留完整报告中 segments/stack 使用的 ID，保持树节点 ID 稳定。
     */
    private static JSONObject treeThreadToJson(ThreadData thread, int[] ids)
            throws JSONException {
        for (Segment segment : thread.segments) {
            ids[0]++;
            ids[0] += segment.frames.size();
        }
        JSONArray roots = new JSONArray();
        for (TreeNode root : thread.roots.values()) {
            roots.put(treeToJson(root, ids));
        }
        return new JSONObject()
                .put("tid", thread.tid)
                .put("threadName", thread.name)
                .put("sampleCount", thread.segments.size())
                .put("estimatedCoveredDurationNs", estimatedCoveredDuration(thread.segments))
                .put("callTree", roots);
    }

    private static JSONObject treeToJson(TreeNode node, int[] ids) throws JSONException {
        JSONArray children = new JSONArray();
        List<Interval> childExactIntervals = new ArrayList<>();
        List<Interval> childEstimatedIntervals = new ArrayList<>();
        for (TreeNode child : node.children.values()) {
            children.put(treeToJson(child, ids));
            childExactIntervals.addAll(child.exactIntervals);
            childEstimatedIntervals.addAll(child.estimatedIntervals);
        }
        long duration = mergeDuration(node.exactIntervals);
        long selfDuration = Math.max(0, duration - mergeDuration(childExactIntervals));
        long estimatedDuration = mergeDuration(node.estimatedIntervals);
        long estimatedSelfDuration = Math.max(0,
                estimatedDuration - mergeDuration(childEstimatedIntervals));
        JSONObject json = frameToJson(node.method, ids);
        json.put("sampleCount", node.sampleCount);
        json.put("exactDurationNs", node.exactIntervals.isEmpty()
                ? JSONObject.NULL : duration);
        json.put("selfDurationNs", node.exactIntervals.isEmpty()
                ? JSONObject.NULL : selfDuration);
        json.put("estimatedDurationNs", estimatedDuration);
        json.put("estimatedSelfDurationNs", estimatedSelfDuration);
        JSONArray types = new JSONArray();
        for (String type : node.eventTypes) {
            types.put(type);
        }
        json.put("eventTypes", types);
        json.put("children", children);
        return json;
    }

    private static void estimatePointDurations(List<Segment> segments, long windowEnd,
                                               long nominalInterval,
                                               long maxPointDuration) {
        for (int i = 0; i < segments.size(); i++) {
            Segment segment = segments.get(i);
            if (segment.exact()) {
                continue;
            }
            long nextStart = Long.MAX_VALUE;
            for (int j = i + 1; j < segments.size(); j++) {
                long candidate = segments.get(j).start;
                if (candidate > segment.start) {
                    nextStart = candidate;
                    break;
                }
            }
            long estimatedEnd;
            if (nextStart != Long.MAX_VALUE) {
                long cappedEnd = saturatedAdd(segment.start, maxPointDuration);
                if (nextStart <= cappedEnd) {
                    estimatedEnd = nextStart;
                    segment.estimateSource = "NEXT_SAMPLE";
                } else {
                    estimatedEnd = cappedEnd;
                    segment.estimateSource = "CAPPED";
                }
            } else {
                estimatedEnd = saturatedAdd(segment.start, nominalInterval);
                segment.estimateSource = "LAST_SAMPLE";
            }
            segment.estimatedEnd = Math.min(windowEnd, estimatedEnd);
            segment.durationKind = "ESTIMATED";
        }
    }

    private static long estimatedCoveredDuration(List<Segment> segments) {
        List<Interval> intervals = new ArrayList<>();
        for (Segment segment : segments) {
            if (segment.estimatedEnd > segment.start) {
                intervals.add(new Interval(segment.start, segment.estimatedEnd));
            }
        }
        return mergeDuration(intervals);
    }

    private static long saturatedAdd(long value, long delta) {
        if (delta > 0 && value > Long.MAX_VALUE - delta) {
            return Long.MAX_VALUE;
        }
        return value + delta;
    }

    private static JSONObject frameToJson(String symbol, int[] ids) throws JSONException {
        boolean nativeMethod = symbol != null && symbol.contains("Native Method");
        Matcher source = SOURCE_POSITION.matcher(symbol == null ? "" : symbol);
        boolean hasSource = source.matches();
        Integer lineNumber = null;
        if (hasSource) {
            try {
                lineNumber = Integer.parseInt(source.group(2));
            } catch (NumberFormatException ignored) {
                hasSource = false;
            }
        }
        return new JSONObject()
                .put("id", "node-" + ids[0]++)
                .put("method", symbol == null ? "<unknown>" : symbol)
                .put("displayName", displayName(symbol, nativeMethod))
                .put("sourceFile", hasSource ? source.group(1) : JSONObject.NULL)
                .put("lineNumber", hasSource ? lineNumber : JSONObject.NULL)
                .put("nativeMethod", nativeMethod);
    }

    private static String displayName(String symbol, boolean nativeMethod) {
        if (symbol == null || symbol.trim().isEmpty()) {
            return "<unknown>(Unknown Source)";
        }
        String value = symbol.trim();
        if (nativeMethod || value.matches(".*\\([^)]*\\.(java|kt):\\d+\\)$")) {
            return value;
        }
        int space = value.lastIndexOf(' ', value.indexOf('('));
        if (space >= 0 && space + 1 < value.length()) {
            value = value.substring(space + 1);
        }
        int parameters = value.indexOf('(');
        if (parameters > 0) {
            value = value.substring(0, parameters);
        }
        return value + "(Unknown Source)";
    }

    private static boolean isExact(StackList item) {
        return item.isDurationStack() && item.getDuration() > 0;
    }

    private static String exactKey(int tid, int type, long start, List<String> frames) {
        StringBuilder key = new StringBuilder();
        key.append(tid).append('|').append(type).append('|').append(start);
        for (String frame : frames) {
            key.append('\u0000').append(frame);
        }
        return key.toString();
    }

    private static long mergeDuration(List<Interval> intervals) {
        if (intervals == null || intervals.isEmpty()) {
            return 0;
        }
        List<Interval> sorted = new ArrayList<>();
        for (Interval interval : intervals) {
            if (interval != null && interval.end > interval.start) {
                sorted.add(interval);
            }
        }
        if (sorted.isEmpty()) {
            return 0;
        }
        sorted.sort(Comparator.comparingLong(interval -> interval.start));
        long start = sorted.get(0).start;
        long end = sorted.get(0).end;
        long total = 0;
        for (int i = 1; i < sorted.size(); i++) {
            Interval current = sorted.get(i);
            if (current.start <= end) {
                end = Math.max(end, current.end);
            } else {
                total += end - start;
                start = current.start;
                end = current.end;
            }
        }
        return total + end - start;
    }
}
