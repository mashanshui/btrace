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

import com.bytedance.rheatrace.perfetto.Trace;
import com.bytedance.rheatrace.trace.CallNode;
import com.bytedance.rheatrace.trace.SamplingTraceDecoder;
import com.bytedance.rheatrace.trace.StackList;
import com.bytedance.rheatrace.trace.StackTraceConvertor;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 解析线上采样并生成完整调用树与方法耗时报告。 */
public final class JankAnalyzer {

    public static final class Analysis {
        private final JSONObject report;
        private final Trace trace;

        private Analysis(JSONObject report, Trace trace) {
            this.report = report;
            this.trace = trace;
        }

        public JSONObject getReport() {
            return report;
        }

        public Trace getTrace() {
            return trace;
        }
    }

    private static final class Interval {
        final long start;
        final long end;

        Interval(long start, long end) {
            this.start = start;
            this.end = end;
        }
    }

    private static final class ThreadStats {
        final int tid;
        int sampleCount;
        int pointSampleCount;
        int exactRecordCount;
        final List<Interval> exactIntervals = new ArrayList<>();

        ThreadStats(int tid) {
            this.tid = tid;
        }

        long exactCoveredDurationNs() {
            return mergeDuration(exactIntervals);
        }
    }

    /** 一个按方法名聚合的调用树节点；同一调用路径的多次出现会合并。 */
    private static final class MethodAggregate {
        final String name;
        final Map<String, MethodAggregate> children = new LinkedHashMap<>();
        final List<Interval> intervals = new ArrayList<>();

        MethodAggregate(String name) {
            this.name = name;
        }

        void addOccurrence(CallNode node, String parentPath, long windowStart, long windowEnd) {
            String methodName = node.getMethodName();
            if (methodName == null || methodName.isEmpty()) {
                return;
            }
            String path = appendPath(parentPath, methodName);
            long begin = Math.max(windowStart, node.getBeginTimeNs());
            long end = Math.min(windowEnd, node.getEndTimeNs());
            if (end <= begin) {
                return;
            }
            intervals.add(new Interval(begin, end));
            for (CallNode child : node.children) {
                String childName = child.getMethodName();
                if (childName == null || childName.isEmpty()) {
                    continue;
                }
                long childBegin = Math.max(windowStart, child.getBeginTimeNs());
                long childEnd = Math.min(windowEnd, child.getEndTimeNs());
                if (childEnd <= childBegin) {
                    continue;
                }
                MethodAggregate aggregate = children.get(childName);
                if (aggregate == null) {
                    aggregate = new MethodAggregate(childName);
                    children.put(childName, aggregate);
                }
                aggregate.addOccurrence(child, path, windowStart, windowEnd);
            }
        }

        JSONObject toJson(String path, Map<String, List<Interval>> exactByPath)
                throws JSONException {
            long duration = mergeDuration(intervals);
            List<Interval> childIntervals = new ArrayList<>();
            JSONArray childJson = new JSONArray();
            for (MethodAggregate child : children.values()) {
                childIntervals.addAll(child.intervals);
                childJson.put(child.toJson(appendPath(path, child.name), exactByPath));
            }
            long childDuration = mergeDuration(childIntervals);
            long selfDuration = Math.max(0, duration - childDuration);
            long exactDuration = Math.min(duration,
                    mergeDuration(exactByPath.get(path)));
            long estimatedDuration = Math.max(0, duration - exactDuration);

            return new JSONObject()
                    .put("name", name)
                    .put("durationNs", duration)
                    .put("selfDurationNs", selfDuration)
                    .put("exactDurationNs", exactDuration)
                    .put("estimatedDurationNs", estimatedDuration)
                    .put("durationSource", durationSource(exactDuration, estimatedDuration))
                    .put("children", childJson);
        }
    }

    private static final class ThreadReport {
        final JSONObject json;

        ThreadReport(JSONObject json) {
            this.json = json;
        }
    }

    public Analysis analyze(File artifact, File proguardMapping) throws IOException {
        try (JankArtifact input = JankArtifact.open(artifact)) {
            JSONObject manifest = input.getManifest();
            String appName = manifest.optString("appName", "online");
            SamplingTraceDecoder.DecodedSampling decoded = SamplingTraceDecoder.decodeDetailed(
                    input.getSamplingFile(), input.getMappingFile(), appName, proguardMapping);
            return buildReport(manifest, decoded);
        } catch (JSONException e) {
            throw new IOException("线上事件字段格式错误", e);
        }
    }

    private Analysis buildReport(JSONObject manifest, SamplingTraceDecoder.DecodedSampling decoded)
            throws JSONException {
        long eventStart = manifest.optLong("eventStartElapsedRealtimeNanos", 0);
        long eventEnd = manifest.optLong("eventEndElapsedRealtimeNanos", eventStart);
        if (eventEnd < eventStart) {
            throw new JSONException("event time range is invalid");
        }
        long preRollMs = Math.max(0, manifest.optLong("preRollMs", 0));
        long preRollNs = preRollMs > Long.MAX_VALUE / 1_000_000L
                ? Long.MAX_VALUE : preRollMs * 1_000_000L;
        long pointWindowStart = Math.max(0, eventStart - preRollNs);

        Map<Integer, List<StackList>> itemsByThread = groupByThread(decoded.getItems());
        Map<Integer, ThreadStats> threadStats = collectThreadStats(
                decoded.getItems(), pointWindowStart, eventStart, eventEnd);
        int processId = decoded.getExtra().optInt("processId",
                manifest.optInt("processId", 0));
        int targetTid = chooseTargetThread(processId, threadStats);
        boolean targetFallback = processId <= 0 || (targetTid != 0 && targetTid != processId);

        int pointSamples = 0;
        int exactRecords = 0;
        int consideredSamples = 0;
        List<Interval> exactIntervals = new ArrayList<>();
        for (ThreadStats stats : threadStats.values()) {
            consideredSamples += stats.sampleCount;
            pointSamples += stats.pointSampleCount;
            exactRecords += stats.exactRecordCount;
            exactIntervals.addAll(stats.exactIntervals);
        }
        long exactCovered = mergeDuration(exactIntervals);

        List<String> warnings = new ArrayList<>();
        if (decoded.getItems().isEmpty()) {
            warnings.add("采样记录为空");
        } else if (consideredSamples == 0) {
            warnings.add("事件窗口内没有相关采样");
        }
        if (exactRecords == 0 && pointSamples > 0) {
            warnings.add("只有点采样，不能据此推断连续耗时");
        }
        if (manifest.optBoolean("truncated", false)) {
            warnings.add("采样文件在设备端被截断");
        }
        if (targetFallback && targetTid != 0) {
            if (processId <= 0) {
                warnings.add("缺少主线程 processId，已按事件耗时和采样数选择线程");
            } else {
                warnings.add("主线程在事件窗口内没有相关采样，已按事件耗时和采样数选择线程");
            }
        }

        Map<Integer, String> threadNames = decoded.getThreadNames();
        ThreadReport completeStack = null;
        if (targetTid != 0) {
            completeStack = buildThreadReport(targetTid, itemsByThread.get(targetTid),
                    threadStats.get(targetTid), threadNames, processId,
                    pointWindowStart, eventStart, eventEnd);
        }
        if (completeStack == null && targetTid != 0) {
            warnings.add("主线程没有可重建的完整调用树");
        }

        JSONArray otherThreads = new JSONArray();
        for (Map.Entry<Integer, ThreadStats> entry : threadStats.entrySet()) {
            if (entry.getKey() == targetTid) {
                continue;
            }
            ThreadReport threadReport = buildThreadReport(entry.getKey(),
                    itemsByThread.get(entry.getKey()), entry.getValue(), threadNames,
                    processId, pointWindowStart, eventStart, eventEnd);
            if (threadReport != null) {
                otherThreads.put(threadReport.json);
            }
        }

        JSONObject report = new JSONObject();
        report.put("schemaVersion", 2);
        report.put("eventId", manifest.optString("eventId", ""));
        report.put("scene", manifest.optString("scene", ""));
        report.put("reason", manifest.optString("reason", ""));
        report.put("mappingId", manifest.optString("mappingId", ""));
        report.put("preRollMs", manifest.optLong("preRollMs", 0));
        report.put("processId", processId);
        report.put("eventStartElapsedRealtimeNanos", eventStart);
        report.put("eventEndElapsedRealtimeNanos", eventEnd);
        report.put("eventDurationNs", Math.max(0, eventEnd - eventStart));
        report.put("sampleCount", consideredSamples);
        report.put("pointSampleCount", pointSamples);
        report.put("exactRecordCount", exactRecords);
        report.put("exactCoveredDurationNs", exactCovered);
        report.put("durationSemantics", new JSONObject()
                .put("durationNs", "调用树重建后的 inclusive wall time")
                .put("selfDurationNs", "扣除子方法后的 exclusive wall time")
                .put("exactDurationSource", "duration hooks")
                .put("estimatedDurationSource", "point samples")
                .put("pointSamplesContinuous", false)
                .put("preRollIncludedInDuration", false));
        report.put("completeStack", completeStack == null
                ? JSONObject.NULL : completeStack.json);
        report.put("otherThreads", otherThreads);

        JSONArray warningJson = new JSONArray();
        for (String warning : warnings) {
            warningJson.put(warning);
        }
        report.put("warnings", warningJson);
        return new Analysis(report, decoded.getTrace());
    }

    private static Map<Integer, ThreadStats> collectThreadStats(List<StackList> items,
                                                                 long pointWindowStart,
                                                                 long eventStart,
                                                                 long eventEnd) {
        Map<Integer, ThreadStats> result = new LinkedHashMap<>();
        for (StackList item : items) {
            boolean durationRecord = isDurationRecord(item);
            if (!isRelevant(item, durationRecord, pointWindowStart, eventStart, eventEnd)) {
                continue;
            }
            ThreadStats stats = result.get(item.getTid());
            if (stats == null) {
                stats = new ThreadStats(item.getTid());
                result.put(item.getTid(), stats);
            }
            stats.sampleCount++;
            if (!durationRecord) {
                stats.pointSampleCount++;
                continue;
            }
            long begin = item.getNanoTime() - item.getDuration();
            long end = item.getNanoTime();
            long clippedBegin = Math.max(begin, eventStart);
            long clippedEnd = Math.min(end, eventEnd);
            if (clippedEnd > clippedBegin) {
                stats.exactRecordCount++;
                stats.exactIntervals.add(new Interval(clippedBegin, clippedEnd));
            }
        }
        return result;
    }

    private static int chooseTargetThread(int processId, Map<Integer, ThreadStats> stats) {
        if (processId > 0 && stats.containsKey(processId)) {
            return processId;
        }
        ThreadStats selected = null;
        for (ThreadStats candidate : stats.values()) {
            if (selected == null
                    || candidate.exactCoveredDurationNs() > selected.exactCoveredDurationNs()
                    || (candidate.exactCoveredDurationNs() == selected.exactCoveredDurationNs()
                    && candidate.sampleCount > selected.sampleCount)) {
                selected = candidate;
            }
        }
        return selected == null ? (processId > 0 ? processId : 0) : selected.tid;
    }

    private static ThreadReport buildThreadReport(int tid, List<StackList> sourceItems,
                                                  ThreadStats stats,
                                                  Map<Integer, String> threadNames,
                                                  int processId,
                                                  long pointWindowStart,
                                                  long eventStart,
                                                  long eventEnd) {
        if (sourceItems == null || sourceItems.isEmpty()) {
            return null;
        }
        List<StackList> treeItems = selectTreeItems(sourceItems, pointWindowStart,
                eventStart, eventEnd);
        if (treeItems.isEmpty()) {
            return null;
        }
        CallNode root = StackTraceConvertor.decodeCallNode(treeItems, tid == processId,
                true, eventEnd);
        Map<String, List<Interval>> exactByPath = collectExactIntervals(sourceItems,
                eventStart, eventEnd);
        Map<String, MethodAggregate> roots = new LinkedHashMap<>();
        for (CallNode child : root.children) {
            String name = child.getMethodName();
            if (name == null || name.isEmpty()) {
                continue;
            }
            MethodAggregate aggregate = roots.get(name);
            if (aggregate == null) {
                aggregate = new MethodAggregate(name);
                roots.put(name, aggregate);
            }
            aggregate.addOccurrence(child, "", eventStart, eventEnd);
        }
        if (roots.isEmpty()) {
            return null;
        }

        JSONArray methods = new JSONArray();
        List<Interval> rootIntervals = new ArrayList<>();
        for (MethodAggregate aggregate : roots.values()) {
            rootIntervals.addAll(aggregate.intervals);
            methods.put(aggregate.toJson(aggregate.name, exactByPath));
        }
        long durationNs = mergeDuration(rootIntervals);
        String threadName = threadNames.get(tid);
        if (threadName == null || threadName.isEmpty()) {
            threadName = tid == processId ? "main" : "Thread-" + tid;
        }
        JSONObject json = new JSONObject();
        try {
            json.put("tid", tid);
            json.put("threadName", threadName);
            json.put("durationNs", durationNs);
            if (stats != null) {
                json.put("sampleCount", stats.sampleCount);
                json.put("pointSampleCount", stats.pointSampleCount);
                json.put("exactRecordCount", stats.exactRecordCount);
            }
            json.put("methods", methods);
        } catch (JSONException e) {
            // JSONObject.put only fails for a null key/value, both of which are controlled here.
            throw new IllegalStateException("生成调用树 JSON 失败", e);
        }
        return new ThreadReport(json);
    }

    private static List<StackList> selectTreeItems(List<StackList> items,
                                                   long pointWindowStart,
                                                   long eventStart,
                                                   long eventEnd) {
        List<StackList> selected = new ArrayList<>();
        Set<StackList> selectedSet = Collections.newSetFromMap(
                new IdentityHashMap<StackList, Boolean>());
        StackList firstAfterEvent = null;
        for (StackList item : items) {
            if (isDurationRecord(item)) {
                long begin = item.getNanoTime() - item.getDuration();
                long end = item.getNanoTime();
                if (end > eventStart && begin < eventEnd) {
                    addSelected(selected, selectedSet, item);
                    StackList start = findDurationStart(items, item, begin);
                    if (start != null) {
                        addSelected(selected, selectedSet, start);
                    }
                }
            } else if (item.getNanoTime() < pointWindowStart) {
                continue;
            } else if (item.getNanoTime() <= eventEnd) {
                addSelected(selected, selectedSet, item);
            } else if (firstAfterEvent == null) {
                firstAfterEvent = item;
            }
        }
        if (firstAfterEvent != null) {
            addSelected(selected, selectedSet, firstAfterEvent);
        }
        selected.sort(Comparator.comparingLong(StackList::getNanoTime));
        return selected;
    }

    private static StackList findDurationStart(List<StackList> items, StackList duration,
                                                long begin) {
        List<String> durationNames = duration.getNames();
        for (StackList candidate : items) {
            if (!candidate.isDurationStack()
                    && candidate.getTid() == duration.getTid()
                    && candidate.getType() == duration.getType()
                    && candidate.getNanoTime() == begin
                    && durationNames.equals(candidate.getNames())) {
                return candidate;
            }
        }
        return null;
    }

    private static void addSelected(List<StackList> selected, Set<StackList> selectedSet,
                                    StackList item) {
        if (selectedSet.add(item)) {
            selected.add(item);
        }
    }

    private static Map<String, List<Interval>> collectExactIntervals(List<StackList> items,
                                                                       long eventStart,
                                                                       long eventEnd) {
        Map<String, List<Interval>> result = new HashMap<>();
        for (StackList item : items) {
            if (!isDurationRecord(item)) {
                continue;
            }
            long begin = item.getNanoTime() - item.getDuration();
            long end = item.getNanoTime();
            long clippedBegin = Math.max(begin, eventStart);
            long clippedEnd = Math.min(end, eventEnd);
            if (clippedEnd <= clippedBegin) {
                continue;
            }
            String path = "";
            for (String name : item.getNames()) {
                path = appendPath(path, name);
                List<Interval> intervals = result.get(path);
                if (intervals == null) {
                    intervals = new ArrayList<>();
                    result.put(path, intervals);
                }
                intervals.add(new Interval(clippedBegin, clippedEnd));
            }
        }
        return result;
    }

    private static Map<Integer, List<StackList>> groupByThread(List<StackList> items) {
        Map<Integer, List<StackList>> result = new LinkedHashMap<>();
        for (StackList item : items) {
            List<StackList> threadItems = result.get(item.getTid());
            if (threadItems == null) {
                threadItems = new ArrayList<>();
                result.put(item.getTid(), threadItems);
            }
            threadItems.add(item);
        }
        return result;
    }

    private static boolean isDurationRecord(StackList item) {
        return item.isDurationStack() && item.getDuration() > 0;
    }

    private static boolean isRelevant(StackList item, boolean durationRecord,
                                      long pointWindowStart, long eventStart, long eventEnd) {
        if (durationRecord) {
            long begin = item.getNanoTime() - item.getDuration();
            long end = item.getNanoTime();
            return end > eventStart && begin < eventEnd;
        }
        return item.getNanoTime() >= pointWindowStart && item.getNanoTime() <= eventEnd;
    }

    private static String durationSource(long exactDuration, long estimatedDuration) {
        if (exactDuration == 0 && estimatedDuration == 0) {
            return "unknown";
        }
        if (exactDuration == 0) {
            return "estimated";
        }
        if (estimatedDuration == 0) {
            return "exact";
        }
        return "mixed";
    }

    private static String appendPath(String parent, String name) {
        return parent.isEmpty() ? name : parent + "\u0000" + name;
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
