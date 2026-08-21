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
import com.bytedance.rheatrace.trace.SamplingTraceDecoder;
import com.bytedance.rheatrace.trace.StackList;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 解析线上采样并生成“精确耗时 + 点采样”分离的报告。 */
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

    private static final class StackStat {
        final String key;
        int sampleCount;
        int exactRecordCount;
        long exactDurationNs;
        int type;
        int tid;

        StackStat(String key, int type, int tid) {
            this.key = key;
            this.type = type;
            this.tid = tid;
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
        Map<String, StackStat> stats = new LinkedHashMap<>();
        List<Interval> exactIntervals = new ArrayList<>();
        int pointSamples = 0;
        int exactRecords = 0;
        int consideredSamples = 0;
        long preRollMs = Math.max(0, manifest.optLong("preRollMs", 0));
        long preRollNs = preRollMs > Long.MAX_VALUE / 1_000_000L
                ? Long.MAX_VALUE : preRollMs * 1_000_000L;
        long pointWindowStart = Math.max(0, eventStart - preRollNs);
        for (StackList item : decoded.getItems()) {
            boolean durationRecord = item.isDurationStack() && item.getDuration() > 0;
            if (durationRecord) {
                long begin = item.getNanoTime() - item.getDuration();
                long end = item.getNanoTime();
                if (end <= eventStart || begin >= eventEnd) {
                    continue;
                }
            } else if (item.getNanoTime() < pointWindowStart || item.getNanoTime() > eventEnd) {
                continue;
            }
            consideredSamples++;
            List<String> names = item.getNames();
            String key = join(names);
            if (key.isEmpty()) {
                key = "<empty>";
            }
            // 同一堆栈在不同 Hook 类型或线程上代表不同语义，分开统计避免 tid/type 被首条记录覆盖。
            String statKey = item.getTid() + "\u0000" + item.getType() + "\u0000" + key;
            StackStat stat = stats.get(statKey);
            if (stat == null) {
                stat = new StackStat(key, item.getType(), item.getTid());
                stats.put(statKey, stat);
            }
            stat.sampleCount++;
            if (durationRecord) {
                long begin = item.getNanoTime() - item.getDuration();
                long end = item.getNanoTime();
                long clippedBegin = Math.max(begin, eventStart);
                long clippedEnd = Math.min(end, eventEnd);
                if (clippedEnd > clippedBegin) {
                    long duration = clippedEnd - clippedBegin;
                    stat.exactRecordCount++;
                    stat.exactDurationNs += duration;
                    exactRecords++;
                    exactIntervals.add(new Interval(clippedBegin, clippedEnd));
                }
            } else {
                pointSamples++;
            }
        }

        Collections.sort(exactIntervals, Comparator.comparingLong(interval -> interval.start));
        long exactCovered = mergeDuration(exactIntervals);
        List<StackStat> sortedStats = new ArrayList<>(stats.values());
        Collections.sort(sortedStats, (left, right) -> {
            int byDuration = Long.compare(right.exactDurationNs, left.exactDurationNs);
            return byDuration != 0 ? byDuration : Integer.compare(right.sampleCount, left.sampleCount);
        });

        JSONObject report = new JSONObject();
        report.put("schemaVersion", 1);
        report.put("eventId", manifest.optString("eventId", ""));
        report.put("scene", manifest.optString("scene", ""));
        report.put("reason", manifest.optString("reason", ""));
        report.put("mappingId", manifest.optString("mappingId", ""));
        report.put("preRollMs", manifest.optLong("preRollMs", 0));
        report.put("processId", decoded.getExtra().optInt("processId",
                manifest.optInt("processId", 0)));
        report.put("eventStartElapsedRealtimeNanos", eventStart);
        report.put("eventEndElapsedRealtimeNanos", eventEnd);
        report.put("eventDurationNs", Math.max(0, eventEnd - eventStart));
        report.put("sampleCount", consideredSamples);
        report.put("pointSampleCount", pointSamples);
        report.put("exactRecordCount", exactRecords);
        report.put("exactCoveredDurationNs", exactCovered);
        report.put("durationSemantics", new JSONObject()
                .put("exactDurationSource", "duration hooks").put("pointSamplesContinuous", false));

        JSONArray stacks = new JSONArray();
        for (StackStat stat : sortedStats) {
            stacks.put(new JSONObject().put("stack", stat.key).put("type", stat.type)
                    .put("tid", stat.tid).put("sampleCount", stat.sampleCount)
                    .put("exactRecordCount", stat.exactRecordCount)
                    .put("exactDurationNs", stat.exactDurationNs));
        }
        report.put("stacks", stacks);
        JSONArray warnings = new JSONArray();
        if (decoded.getItems().isEmpty()) {
            warnings.put("采样记录为空");
        } else if (consideredSamples == 0) {
            warnings.put("事件窗口内没有相关采样");
        }
        if (exactRecords == 0 && pointSamples > 0) {
            warnings.put("只有点采样，不能据此推断连续耗时");
        }
        if (manifest.optBoolean("truncated", false)) {
            warnings.put("采样文件在设备端被截断");
        }
        report.put("warnings", warnings);
        return new Analysis(report, decoded.getTrace());
    }

    private static long mergeDuration(List<Interval> intervals) {
        if (intervals.isEmpty()) {
            return 0;
        }
        long start = intervals.get(0).start;
        long end = intervals.get(0).end;
        long total = 0;
        for (int i = 1; i < intervals.size(); i++) {
            Interval current = intervals.get(i);
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

    private static String join(List<String> names) {
        StringBuilder result = new StringBuilder();
        for (String name : names) {
            if (result.length() > 0) {
                result.append("\n");
            }
            result.append(name);
        }
        return result.toString();
    }
}
