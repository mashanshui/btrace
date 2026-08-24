/*
 * Copyright (C) 2021 ByteDance Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.bytedance.rheatrace.stack;

import com.bytedance.rheatrace.perfetto.Trace;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** rhea-trace-processor analyze-stack 命令入口。 */
public final class StackMain {
    private StackMain() {
    }

    public static void main(String[] args) throws Exception {
        File input = null;
        File output = null;
        File html = null;
        File mapping = null;
        File traceOutput = null;
        String thread = "main";
        String sort = "chronological";
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--input".equals(arg) && i + 1 < args.length) {
                input = new File(args[++i]);
            } else if ("--output".equals(arg) && i + 1 < args.length) {
                output = new File(args[++i]);
            } else if ("--html".equals(arg) && i + 1 < args.length) {
                html = new File(args[++i]);
            } else if ("--mapping".equals(arg) && i + 1 < args.length) {
                mapping = new File(args[++i]);
            } else if ("--trace".equals(arg) && i + 1 < args.length) {
                traceOutput = new File(args[++i]);
            } else if ("--thread".equals(arg) && i + 1 < args.length) {
                thread = args[++i];
            } else if ("--sort".equals(arg) && i + 1 < args.length) {
                sort = args[++i];
            } else {
                throw new IllegalArgumentException(
                        "参数错误，支持 --input、--output、--html、--mapping、--trace、--thread、--sort");
            }
        }
        if (input == null) {
            throw new IllegalArgumentException("缺少 --input <artifact.zip>");
        }
        if (output == null) {
            output = sibling(input, input.getName() + ".json");
        }
        StackAnalysisRequest request = StackAnalysisRequest.builder(input)
                .setProguardMapping(mapping)
                .setThread(thread)
                .setSort(sort)
                .build();
        StackAnalysisResult result = new StackAnalyzer().analyze(request);
        ensureParent(output);
        Files.write(output.toPath(),
                result.getReport().toString(2).getBytes(StandardCharsets.UTF_8));
        if (html != null) {
            new StackHtmlRenderer().write(result.getReport(), html);
        }
        if (traceOutput != null) {
            Trace trace = result.getTrace();
            if (trace == null) {
                throw new IOException("没有可转换为 Perfetto 的有效采样");
            }
            ensureParent(traceOutput);
            try (OutputStream stream = new FileOutputStream(traceOutput)) {
                trace.marshal(stream);
            }
        }
        System.out.println("堆栈产物解析完成: " + output.getAbsolutePath()
                + (html == null ? "" : "；HTML: " + html.getAbsolutePath()));
    }

    private static File sibling(File input, String name) {
        File parent = input.getAbsoluteFile().getParentFile();
        return new File(parent == null ? new File(".") : parent, name);
    }

    private static void ensureParent(File file) throws IOException {
        if (file.getParentFile() != null && !file.getParentFile().exists()
                && !file.getParentFile().mkdirs()) {
            throw new IOException("无法创建输出目录: " + file.getParent());
        }
    }
}
