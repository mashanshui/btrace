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

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** `rhea-trace-processor analyze-jank` 入口。 */
public final class JankMain {
    private JankMain() {
    }

    public static void main(String[] args) throws Exception {
        File input = null;
        File output = null;
        File mapping = null;
        File traceOutput = null;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--input".equals(arg) && i + 1 < args.length) {
                input = new File(args[++i]);
            } else if ("--output".equals(arg) && i + 1 < args.length) {
                output = new File(args[++i]);
            } else if ("--mapping".equals(arg) && i + 1 < args.length) {
                mapping = new File(args[++i]);
            } else if ("--trace".equals(arg) && i + 1 < args.length) {
                traceOutput = new File(args[++i]);
            } else {
                throw new IllegalArgumentException("参数错误，支持 --input、--output、--mapping、--trace");
            }
        }
        if (input == null) {
            throw new IllegalArgumentException("缺少 --input <artifact.zip>");
        }
        if (output == null) {
            output = new File(input.getParentFile(), input.getName() + ".json");
        }
        JankAnalyzer.Analysis analysis = new JankAnalyzer().analyze(input, mapping);
        if (output.getParentFile() != null && !output.getParentFile().exists()
                && !output.getParentFile().mkdirs()) {
            throw new IOException("无法创建输出目录: " + output.getParent());
        }
        java.nio.file.Files.write(output.toPath(), analysis.getReport().toString(2)
                .getBytes(StandardCharsets.UTF_8));
        if (traceOutput != null) {
            Trace trace = analysis.getTrace();
            if (trace == null) {
                throw new IOException("没有可转换为 Perfetto 的有效采样");
            }
            if (traceOutput.getParentFile() != null && !traceOutput.getParentFile().exists()
                    && !traceOutput.getParentFile().mkdirs()) {
                throw new IOException("无法创建 Perfetto 输出目录: " + traceOutput.getParent());
            }
            try (OutputStream stream = new FileOutputStream(traceOutput)) {
                trace.marshal(stream);
            }
        }
        System.out.println("在线卡顿产物解析完成: " + output.getAbsolutePath());
    }
}
