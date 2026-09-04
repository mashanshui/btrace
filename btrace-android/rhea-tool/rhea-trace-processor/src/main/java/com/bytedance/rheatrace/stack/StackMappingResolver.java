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

import java.io.File;
import java.io.IOException;

/** 在产物校验完成后为一次解析选择 ProGuard/R8 mapping 文件。 */
@FunctionalInterface
public interface StackMappingResolver {

    /**
     * 根据已校验的产物元数据返回 mapping 文件。
     *
     * @return 可读 mapping 文件；返回 {@code null} 表示不解混淆
     * @throws IOException mapping 注册表或文件解析失败
     */
    File resolve(StackArtifactMetadata metadata) throws IOException;
}
