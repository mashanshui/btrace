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
package com.bytedance.rheatrace.stack;

/**
 * 产物 manifest 经过 {@link StackArtifact} 校验后的最小元数据视图。
 *
 * <p>该对象只在 mapping 解析回调中使用。调用方可以据此选择项目隔离的
 * ProGuard/R8 mapping 文件，但不应把其中的字符串当作文件路径。</p>
 */
public final class StackArtifactMetadata {
    private final int schemaVersion;
    private final String artifactType;
    private final String appId;
    private final String mappingId;

    StackArtifactMetadata(int schemaVersion, String artifactType,
                          String appId, String mappingId) {
        this.schemaVersion = schemaVersion;
        this.artifactType = artifactType;
        this.appId = appId;
        this.mappingId = mappingId;
    }

    /** 原始产物 manifest 的 schema 版本。 */
    public int getSchemaVersion() {
        return schemaVersion;
    }

    /** 原始产物 manifest 的 artifactType。 */
    public String getArtifactType() {
        return artifactType;
    }

    /** 规范化应用标识；v1 来自 appName，v3 卡顿产物来自 packageName。 */
    public String getAppId() {
        return appId;
    }

    /** 规范化 mapping 业务标识；v1 来自 mappingId，v3 卡顿产物来自 buildId。 */
    public String getMappingId() {
        return mappingId;
    }
}
