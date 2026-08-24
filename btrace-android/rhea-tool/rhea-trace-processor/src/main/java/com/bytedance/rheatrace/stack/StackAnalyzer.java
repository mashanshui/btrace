/*
 * Copyright (C) 2021 ByteDance Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.bytedance.rheatrace.stack;

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

        Segment(long start, Long end, int type, List<String> frames) {
            this.start = start;
            this.end = end;
            this.type = type;
            this.frames = frames;
        }

        boolean exact() {
            return end != null && end > start;
        }
    }

    private static final class TreeNode {
        final String method;
        final Map<String, TreeNode> children = new LinkedHashMap<>();
        final List<Interval> exactIntervals = new ArrayList<>();
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
            String rootName = segment.frames.get(0);
            TreeNode root = roots.get(rootName);
            if (root == null) {
                root = new TreeNode(rootName);
                roots.put(rootName, root);
            }
            root.add(segment.frames, 0, segment);
        }
    }

    public StackAnalysisResult analyze(StackAnalysisRequest request) throws IOException {
        try (StackArtifact artifact = StackArtifact.open(request.getInput())) {
            JSONObject manifest = artifact.getManifest();
            String appName = manifest.optString("appName", "online");
            SamplingTraceDecoder.DecodedSampling decoded = SamplingTraceDecoder.decodeDetailed(
                    artifact.getSamplingFile(), artifact.getMappingFile(), appName,
                    request.getProguardMapping());
            if (decoded.getFormatVersion() != manifest.getInt("samplingFormatVersion")) {
                throw new IOException("manifest 与 sampling 格式版本不一致");
            }
            if (decoded.getRawRecordCount() != manifest.getInt("recordCount")) {
                throw new IOException("manifest 与 sampling 记录数不一致");
            }
            return new StackAnalysisResult(
                    buildReport(manifest, decoded, request), decoded.getTrace());
        } catch (JSONException e) {
            throw new IOException("生成堆栈报告失败", e);
        }
    }

    public StackAnalysisResult analyze(File artifact, File proguardMapping) throws IOException {
        return analyze(StackAnalysisRequest.builder(artifact)
                .setProguardMapping(proguardMapping).build());
    }

    private JSONObject buildReport(JSONObject manifest,
                                   SamplingTraceDecoder.DecodedSampling decoded,
                                   StackAnalysisRequest request) throws JSONException {
        long windowStart = manifest.getLong("actualStartNs");
        long windowEnd = manifest.getLong("actualEndNs");
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

        JSONArray threadJson = new JSONArray();
        int[] ids = {1};
        for (ThreadData thread : threads) {
            thread.segments.sort(Comparator.comparingLong(segment -> segment.start));
            threadJson.put(threadToJson(thread, windowStart, ids));
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

        JSONObject report = new JSONObject();
        report.put("schemaVersion", 1);
        report.put("artifactType", "RHEA_STACK_REPORT");
        report.put("selectionType", manifest.getString("selectionType"));
        report.put("appName", manifest.optString("appName", ""));
        report.put("mappingId", manifest.optString("mappingId", ""));
        report.put("processId", processId);
        report.put("requestedStartNs", manifest.opt("requestedStartNs"));
        report.put("requestedEndNs", manifest.opt("requestedEndNs"));
        report.put("availableStartNs", manifest.optLong("availableStartNs", windowStart));
        report.put("availableEndNs", manifest.optLong("availableEndNs", windowEnd));
        report.put("actualStartNs", windowStart);
        report.put("actualEndNs", windowEnd);
        report.put("durationNs", windowEnd - windowStart);
        report.put("recordCount", manifest.optInt("recordCount",
                pointCount + exactCount));
        report.put("pointSampleCount", pointCount);
        report.put("exactRecordCount", exactCount);
        report.put("exactCoveredDurationNs", mergeDuration(allExact));
        report.put("overwrittenRecordCount",
                manifest.optLong("overwrittenRecordCount", 0));
        report.put("droppedByRateLimit", manifest.optLong("droppedByRateLimit", 0));
        report.put("durationSemantics", new JSONObject()
                .put("exactDurationNs", "仅来自带开始和结束时间的 duration hook，并裁剪到导出窗口")
                .put("selfDurationNs", "当前节点精确区间并集扣除直接子节点精确区间并集，表示未归属区间而非 CPU 自耗时")
                .put("pointSampleDuration", JSONObject.NULL)
                .put("pointSamplesContinuous", false));
        report.put("renderDefaults", new JSONObject()
                .put("thread", request.getThread())
                .put("sort", request.getSort())
                .put("flameMetric", "samples")
                .put("view", "flame"));
        report.put("threads", threadJson);
        report.put("warnings", warnings);
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
                .put("segments", segments)
                .put("callTree", roots);
    }

    private static JSONObject treeToJson(TreeNode node, int[] ids) throws JSONException {
        JSONArray children = new JSONArray();
        List<Interval> childIntervals = new ArrayList<>();
        for (TreeNode child : node.children.values()) {
            children.put(treeToJson(child, ids));
            childIntervals.addAll(child.exactIntervals);
        }
        long duration = mergeDuration(node.exactIntervals);
        long selfDuration = Math.max(0, duration - mergeDuration(childIntervals));
        JSONObject json = frameToJson(node.method, ids);
        json.put("sampleCount", node.sampleCount);
        json.put("exactDurationNs", node.exactIntervals.isEmpty()
                ? JSONObject.NULL : duration);
        json.put("selfDurationNs", node.exactIntervals.isEmpty()
                ? JSONObject.NULL : selfDuration);
        JSONArray types = new JSONArray();
        for (String type : node.eventTypes) {
            types.put(type);
        }
        json.put("eventTypes", types);
        json.put("children", children);
        return json;
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
