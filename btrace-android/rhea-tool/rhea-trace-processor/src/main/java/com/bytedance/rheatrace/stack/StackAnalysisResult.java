/*
 * Copyright (C) 2021 ByteDance Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.bytedance.rheatrace.stack;

import com.bytedance.rheatrace.perfetto.Trace;

import org.json.JSONObject;

/** 堆栈分析的完整报告、独立调用树 JSON 与可选 Perfetto Trace。 */
public final class StackAnalysisResult {
    private final JSONObject report;
    private final String callTreeJson;
    private final Trace trace;

    StackAnalysisResult(JSONObject report, String callTreeJson, Trace trace) {
        this.report = report;
        this.callTreeJson = callTreeJson;
        this.trace = trace;
    }

    /** 保留旧的包内构造方式，供不需要独立调用树 JSON 的测试或适配代码使用。 */
    StackAnalysisResult(JSONObject report, Trace trace) {
        this(report, null, trace);
    }

    public JSONObject getReport() { return report; }
    public String getCallTreeJson() { return callTreeJson; }
    public Trace getTrace() { return trace; }
}
