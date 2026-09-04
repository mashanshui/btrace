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
import java.io.InputStream;

/** 面向服务端上传场景的线上堆栈解析接口。 */
public interface StackParser {

    /**
     * 解析 SDK 导出的 v1 {@code .rheatrace.zip} 或 v3 {@code .rheajank.zip} 输入流并返回完整报告 JSON。
     *
     * <p>调用方持有并负责关闭 {@code artifactInput}。实现不得关闭该输入流，也不得修改或
     * 删除可选的 {@code proguardMapping} 文件。卡顿输入的完整报告包含经过校验的
     * {@code sourceManifest}，而报告自身仍使用 {@code schemaVersion=1}。</p>
     *
     * @param artifactInput SDK 导出的线上堆栈 ZIP 输入流
     * @param proguardMapping 可选的 ProGuard/R8 mapping 文件；不需要解混淆时传 {@code null}
     * @return UTF-8 语义的格式化 {@code RHEA_STACK_REPORT} JSON 字符串
     * @throws IllegalArgumentException {@code artifactInput} 为 {@code null}
     * @throws IOException 输入超限、产物校验失败或堆栈解码失败
     */
    String parse(InputStream artifactInput, File proguardMapping) throws IOException;

    /**
     * 在产物 manifest 和二进制文件完成校验后，根据元数据选择 mapping 并解析完整报告。
     *
     * <p>卡顿产物使用 {@code buildId} 作为 {@link StackArtifactMetadata#getMappingId()}，
     * 因而服务端无需在读取 manifest 前重复实现 ZIP 解包。旧版实现如果不支持该能力，
     * 应显式抛出 {@link UnsupportedOperationException}。</p>
     *
     * @param artifactInput SDK 导出的线上堆栈 ZIP 输入流
     * @param mappingResolver manifest 校验完成后调用的 mapping 选择器
     * @return UTF-8 语义的格式化 {@code RHEA_STACK_REPORT} JSON 字符串
     * @throws IllegalArgumentException 参数为 {@code null}
     * @throws IOException 输入超限、产物校验失败、mapping 选择失败或堆栈解码失败
     */
    default String parseWithMappingResolver(InputStream artifactInput,
                                            StackMappingResolver mappingResolver)
            throws IOException {
        if (artifactInput == null) {
            throw new IllegalArgumentException("artifactInput == null");
        }
        if (mappingResolver == null) {
            throw new IllegalArgumentException("mappingResolver == null");
        }
        throw new UnsupportedOperationException(
                "当前 StackParser 不支持基于 manifest 的 mapping 解析");
    }

    /** 不使用 ProGuard/R8 mapping 解析完整报告。 */
    default String parse(InputStream artifactInput) throws IOException {
        return parse(artifactInput, null);
    }
}
