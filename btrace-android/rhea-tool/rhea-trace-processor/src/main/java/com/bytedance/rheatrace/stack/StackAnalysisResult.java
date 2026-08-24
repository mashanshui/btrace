/*
 * Copyright (C) 2021 ByteDance Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.bytedance.rheatrace.stack;

import com.bytedance.rheatrace.perfetto.Trace;

import org.json.JSONObject;

/** 堆栈分析的结构化结果与可选 Perfetto Trace。 */
public final class StackAnalysisResult {
    private final JSONObject report;
    private final Trace trace;

    StackAnalysisResult(JSONObject report, Trace trace) {
        this.report = report;
        this.trace = trace;
    }

    public JSONObject getReport() { return report; }
    public Trace getTrace() { return trace; }
}
