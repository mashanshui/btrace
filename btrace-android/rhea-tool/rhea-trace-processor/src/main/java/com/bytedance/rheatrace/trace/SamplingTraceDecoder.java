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
import java.util.List;
import java.util.Map;

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
        private final int formatVersion;
        private final int rawRecordCount;

        private DecodedSampling(Trace trace, List<StackList> items, JSONObject extra,
                                Map<Integer, String> threadNames, int formatVersion,
                                int rawRecordCount) {
            this.trace = trace;
            this.items = items;
            this.extra = extra;
            this.threadNames = Collections.unmodifiableMap(new HashMap<>(threadNames));
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

        SamplingPayload(JSONObject extra, int version, int recordCount) {
            this.extra = extra;
            this.version = version;
            this.recordCount = recordCount;
        }
    }

    private static int pid = 0;

    public static int getPid() {
        return pid;
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
        int actualPid = extra.optInt("processId", 0);
        pid = actualPid;
        Trace trace = samplingTrace.isEmpty() ? null
                : StackTraceConvertor.convert(actualPid, appName, samplingTrace, mappingDecoder.threadNames);
        return new DecodedSampling(trace, samplingTrace, extra, mappingDecoder.threadNames,
                payload.version, payload.recordCount);
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
        validateRecords(buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN), version, count);
        int pid = extra.optInt("processId", 0);
        long traceBeginTime = extra.optLong("startTime", 0) * 1000000;
        try {
            StackList.decode(version, mapping, buffer, items, traceBeginTime, pid);
        } catch (RuntimeException error) {
            throw new IOException("sampling 记录解码失败", error);
        }
        return new SamplingPayload(extra, version, count);
    }

    private static void validateRecords(ByteBuffer buffer, int version, int count)
            throws IOException {
        for (int index = 0; index < count; index++) {
            requireRemaining(buffer, 8 + 32, "记录固定字段", index);
            int type = buffer.getShort() & 0xffff;
            buffer.getShort();
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
