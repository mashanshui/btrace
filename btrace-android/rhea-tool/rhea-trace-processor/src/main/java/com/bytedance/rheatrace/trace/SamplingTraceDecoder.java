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
package com.bytedance.rheatrace.trace;

import com.bytedance.rheatrace.Log;
import com.bytedance.rheatrace.core.Arguments;
import com.bytedance.rheatrace.core.TraceError;
import com.bytedance.rheatrace.core.Workspace;
import com.bytedance.rheatrace.perfetto.Trace;
import com.bytedance.rheatrace.stack.ProcessIdentity;

import org.apache.commons.io.FileUtils;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class SamplingTraceDecoder {

    private static final int MAGIC = 0x01020304;
    private static final int TYPE_SAMPLING = 0;
    private static final int MAX_VERSION = 5;
    private static final int MAX_STACK_DEPTH = 128;
    private static final int TYPE_TRACE_ARG = 15;

    public static final class DecodedSampling {
        private final Trace trace;
        private final List<StackList> items;
        private final JSONObject extra;
        private final Map<Integer, String> threadNames;
        /** 线上产物声明的 UUID 进程身份；调试采样没有该值时为 null。 */
        private final String processId;
        /** 所有原始 SamplingRecord 中出现过的线程 ID。 */
        private final Set<Integer> threadIds;
        /** 线上产物唯一主线程的数值 tid；非线上采样时为 null。 */
        private final Integer mainTid;
        private final int formatVersion;
        private final int rawRecordCount;

        private DecodedSampling(Trace trace, List<StackList> items, JSONObject extra,
                                Map<Integer, String> threadNames, String processId,
                                Set<Integer> threadIds, Integer mainTid,
                                int formatVersion, int rawRecordCount) {
            this.trace = trace;
            this.items = items;
            this.extra = extra;
            this.threadNames = Collections.unmodifiableMap(new HashMap<>(threadNames));
            this.processId = processId;
            this.threadIds = Collections.unmodifiableSet(new LinkedHashSet<>(threadIds));
            this.mainTid = mainTid;
            this.formatVersion = formatVersion;
            this.rawRecordCount = rawRecordCount;
        }

        public Trace getTrace() {
            return trace;
        }

        public List<StackList> getItems() {
            return items;
        }

        public JSONObject getExtra() {
            return extra;
        }

        public Map<Integer, String> getThreadNames() {
            return threadNames;
        }

        /** 返回线上产物中的 UUID 进程身份；调试采样没有该值时返回 null。 */
        public String getProcessId() {
            return processId;
        }

        /** 返回所有原始 SamplingRecord 中出现过的线程 ID。 */
        public Set<Integer> getThreadIds() {
            return threadIds;
        }

        /** 返回线上主线程 ID；非主线程限定的调试采样返回 null。 */
        public Integer getMainTid() {
            return mainTid;
        }

        public int getFormatVersion() {
            return formatVersion;
        }

        public int getRawRecordCount() {
            return rawRecordCount;
        }
    }

    private static final class SamplingPayload {
        final JSONObject extra;
        final int version;
        final int recordCount;
        /** 所有原始 SamplingRecord 中出现过的线程 ID。 */
        final Set<Integer> threadIds;

        SamplingPayload(JSONObject extra, int version, int recordCount,
                        Set<Integer> threadIds) {
            this.extra = extra;
            this.version = version;
            this.recordCount = recordCount;
            this.threadIds = threadIds;
        }
    }

    public static Trace decode() throws IOException {
        File mappingPath = Arguments.get().mappingPath == null
                ? null : new File(Arguments.get().mappingPath);
        DecodedSampling decoded = decodeDetailed(Workspace.samplingTrace(),
                Workspace.samplingMapping(), Arguments.get().appName, mappingPath);
        if (decoded.getItems().isEmpty() || !decoded.getExtra().has("processId")) {
            return null;
        }
        return decoded.getTrace();
    }

    /** 解码在线 ZIP 解包后的采样文件，并返回原始记录供 JSON 分析使用。 */
    public static DecodedSampling decodeDetailed(File sampling, File mapping,
                                                 String appName, File proguardMapping)
            throws IOException {
        return decodeDetailed(sampling, mapping, appName, proguardMapping, true);
    }

    /**
     * 解码在线 ZIP 解包后的采样文件，可按调用方需要跳过 Perfetto Trace 构建。
     * 跳过 Trace 只影响返回的 Trace，不改变采样记录、mapping 和线程信息。
     */
    public static DecodedSampling decodeDetailed(File sampling, File mapping,
                                                 String appName, File proguardMapping,
                                                 boolean buildTrace)
            throws IOException {
        SamplingMappingDecoder mappingDecoder = decodeMapping(mapping);
        if (proguardMapping != null) {
            ProguardMappingDecoder proguardMappingDecoder =
                    new ProguardMappingDecoder(proguardMapping.getAbsolutePath());
            proguardMappingDecoder.decode();
            mappingDecoder.retrace(proguardMappingDecoder);
        }
        List<StackList> samplingTrace = new ArrayList<>();
        SamplingPayload payload = decodeSampling(
                sampling, mappingDecoder.symbolMapping, samplingTrace);
        JSONObject extra = payload.extra;
        boolean mainThreadOnly = hasMainThreadScope(extra);
        String processId = null;
        Integer mainTid = null;
        int tracePid = 0;
        int traceMainTid = tracePid;
        if (mainThreadOnly) {
            Object processIdValue = extra.opt("processId");
            if (!ProcessIdentity.isUuidV4(processIdValue)) {
                throw new IOException("sampling extra processId 必须为 UUID v4");
            }
            if (payload.threadIds.size() != 1) {
                throw new IOException("线上 sampling 必须只包含一个线程");
            }
            processId = (String) processIdValue;
            mainTid = payload.threadIds.iterator().next();
            // Perfetto 仍要求数值 pid；线上协议不暴露数值进程身份，因此使用主线程 tid 作为内部值。
            tracePid = mainTid;
            traceMainTid = mainTid;
        } else if (extra.has("threadScope")) {
            throw new IOException("sampling extra threadScope 必须为 main");
        } else {
            // 调试/离线采样仍使用历史数值 PID，同时不把它暴露为线上身份。
            tracePid = extra.optInt("processId", 0);
            traceMainTid = tracePid;
        }
        Trace trace = !buildTrace || samplingTrace.isEmpty() ? null
                : StackTraceConvertor.convert(tracePid, traceMainTid, appName,
                samplingTrace, mappingDecoder.threadNames);
        return new DecodedSampling(trace, samplingTrace, extra, mappingDecoder.threadNames,
                processId, payload.threadIds, mainTid, payload.version, payload.recordCount);
    }

    private static SamplingPayload decodeSampling(File sampling, Map<Long, MethodSymbol> mapping,
                                                  List<StackList> items) throws IOException {
        byte[] samplingBytes = FileUtils.readFileToByteArray(sampling);
        ByteBuffer buffer = ByteBuffer.wrap(samplingBytes).order(ByteOrder.LITTLE_ENDIAN);
        if (buffer.remaining() < 28) {
            Log.red("buffer underflow on " + sampling.getName() + ", size is " + samplingBytes.length);
            throw new TraceError("sample trace file is empty: " + sampling.getName(), null);
        }
        int magic = buffer.getInt();
        int type = buffer.getInt();
        int version = buffer.getInt();
        buffer.getLong();
        int count = buffer.getInt();
        int extraLength = buffer.getInt();
        if (magic != MAGIC || type != TYPE_SAMPLING) {
            throw new IOException("sampling 文件头 magic 或 type 无效");
        }
        if (version < 1 || version > MAX_VERSION) {
            throw new IOException("不支持的 sampling 格式版本: " + version);
        }
        if (count < 0) {
            throw new IOException("sampling 记录数无效");
        }
        if (extraLength < 0 || extraLength > buffer.remaining()) {
            throw new IOException("sampling extra 长度无效");
        }
        JSONObject extra;
        if (extraLength > 0) {
            byte[] b = new byte[extraLength];
            buffer.get(b);
            try {
                extra = new JSONObject(new String(b, StandardCharsets.UTF_8));
            } catch (RuntimeException error) {
                throw new IOException("sampling extra 不是有效 JSON", error);
            }
        } else {
            extra = new JSONObject();
        }
        Set<Integer> threadIds = new LinkedHashSet<>();
        validateRecords(buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN), version, count,
                threadIds);
        long traceBeginTime = extra.optLong("startTime", 0) * 1000000;
        boolean onlineMainScope = "main".equals(extra.optString("threadScope", ""));
        int mainTid = onlineMainScope && threadIds.size() == 1
                ? threadIds.iterator().next() : extra.optInt("processId", 0);
        try {
            StackList.decode(version, mapping, buffer, items, traceBeginTime, mainTid);
        } catch (RuntimeException error) {
            throw new IOException("sampling 记录解码失败", error);
        }
        return new SamplingPayload(extra, version, count, threadIds);
    }

    /** 判断 extra 是否声明线上主线程采样范围。 */
    private static boolean hasMainThreadScope(JSONObject extra) throws IOException {
        if (!extra.has("threadScope")) {
            return false;
        }
        if (!"main".equals(extra.optString("threadScope", ""))) {
            throw new IOException("sampling extra threadScope 必须为 main");
        }
        return true;
    }

    private static void validateRecords(ByteBuffer buffer, int version, int count,
                                        Set<Integer> threadIds)
            throws IOException {
        for (int index = 0; index < count; index++) {
            requireRemaining(buffer, 8 + 32, "记录固定字段", index);
            int type = buffer.getShort() & 0xffff;
            int tid = buffer.getShort();
            threadIds.add(tid);
            buffer.getInt();
            buffer.position(buffer.position() + 32);
            if (type < 1 || type > 23) {
                throw new IOException("sampling 记录类型无效: " + type);
            }
            if (type == TYPE_TRACE_ARG) {
                requireRemaining(buffer, 8, "TraceArg", index);
                buffer.position(buffer.position() + 8);
            }
            if (version >= 4) {
                requireRemaining(buffer, 16, "分配统计", index);
                buffer.position(buffer.position() + 16);
            }
            if (version >= 5) {
                requireRemaining(buffer, 12, "rusage", index);
                buffer.position(buffer.position() + 12);
            }
            requireRemaining(buffer, 8, "栈深度", index);
            int savedDepth = buffer.getInt();
            int actualDepth = buffer.getInt();
            if (savedDepth < 0 || savedDepth > MAX_STACK_DEPTH || actualDepth < 0
                    || (type == TYPE_TRACE_ARG && savedDepth < 2)) {
                throw new IOException("sampling 栈深度无效: " + savedDepth + "/" + actualDepth);
            }
            requireRemaining(buffer, savedDepth * 8, "栈帧", index);
            buffer.position(buffer.position() + savedDepth * 8);
        }
        if (buffer.hasRemaining()) {
            throw new IOException("sampling 记录数与文件长度不一致");
        }
    }

    private static void requireRemaining(ByteBuffer buffer, int bytes, String field, int index)
            throws IOException {
        if (bytes < 0 || buffer.remaining() < bytes) {
            throw new IOException("sampling 第 " + index + " 条记录的" + field + "被截断");
        }
    }

    private static SamplingMappingDecoder decodeMapping(File mapping) throws IOException {
        byte[] mappingBytes = FileUtils.readFileToByteArray(mapping);
        return new SamplingMappingDecoder(mappingBytes).decode();
    }
}
