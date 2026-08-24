/*
 * Copyright (C) 2021 ByteDance Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.bytedance.rheatrace.stack;

import java.io.File;

/** 通用线上堆栈分析请求。 */
public final class StackAnalysisRequest {
    private final File input;
    private final File proguardMapping;
    private final String thread;
    private final String sort;

    private StackAnalysisRequest(Builder builder) {
        input = builder.input;
        proguardMapping = builder.proguardMapping;
        thread = builder.thread;
        sort = builder.sort;
    }

    public File getInput() { return input; }
    public File getProguardMapping() { return proguardMapping; }
    public String getThread() { return thread; }
    public String getSort() { return sort; }

    public static Builder builder(File input) {
        return new Builder(input);
    }

    public static final class Builder {
        private final File input;
        private File proguardMapping;
        private String thread = "main";
        private String sort = "chronological";

        private Builder(File input) {
            if (input == null) {
                throw new IllegalArgumentException("input == null");
            }
            this.input = input;
        }

        public Builder setProguardMapping(File value) {
            proguardMapping = value;
            return this;
        }

        public Builder setThread(String value) {
            thread = value == null || value.trim().isEmpty() ? "main" : value.trim();
            return this;
        }

        public Builder setSort(String value) {
            if (!"chronological".equals(value) && !"duration".equals(value)) {
                throw new IllegalArgumentException(
                        "sort must be chronological or duration");
            }
            sort = value;
            return this;
        }

        public StackAnalysisRequest build() {
            return new StackAnalysisRequest(this);
        }
    }
}
