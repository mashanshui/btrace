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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class SamplingMappingDecoder {
    private final byte[] mappingBytes;
    public final Map<Long, MethodSymbol> symbolMapping = new HashMap<>();
    public final Map<Integer, String> threadNames = new HashMap<>();

    public SamplingMappingDecoder(byte[] mappingBytes) {
        this.mappingBytes = mappingBytes;
    }

    public SamplingMappingDecoder decode() throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(mappingBytes).order(ByteOrder.LITTLE_ENDIAN);
        if (buffer.remaining() < 16) {
            throw new IOException("sampling mapping 文件头不完整");
        }
        long maybeMagic = buffer.getLong();
        int version = buffer.getInt();
        int count = buffer.getInt();
        if (maybeMagic != 0 || version != 1 || count < 0) {
            throw new IOException("sampling mapping 文件头无效");
        }
        for (int i = 0; i < count; i++) {
            if (buffer.remaining() < 10) {
                throw new IOException("sampling mapping 方法记录被截断");
            }
            long pointer = buffer.getLong();
            int len = buffer.getShort() & 0xffff;
            if (len <= 0 || len > buffer.remaining()) {
                throw new IOException("sampling mapping 方法名长度无效");
            }
            byte[] b = new byte[len];
            buffer.get(b);
            symbolMapping.put(pointer, new MethodSymbol(pointer, 0,
                    new String(b, StandardCharsets.UTF_8)));
        }
        while (buffer.hasRemaining()) {
            if (buffer.remaining() < 3) {
                throw new IOException("sampling mapping 线程记录被截断");
            }
            int tid = buffer.getShort() & 0xffff;
            int len = buffer.get() & 0xff;
            if (len <= 0 || len > buffer.remaining()) {
                throw new IOException("sampling mapping 线程名长度无效");
            }
            byte[] name = new byte[len];
            buffer.get(name);
            threadNames.put(tid, new String(name, StandardCharsets.UTF_8));
        }
        return this;
    }

    public void retrace(ProguardMappingDecoder decoder) {
        for (MethodSymbol symbol : symbolMapping.values()) {
            symbol.symbol = decoder.retrace(symbol.symbol);
        }
    }
}
