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
#include <jni.h>
#include "sampling/SamplingCollector.h"
#include "utils/log.h"

extern "C"
JNIEXPORT jlong JNICALL
Java_com_bytedance_rheatrace_trace_base_TraceAbility_nativeCreate(
        JNIEnv* env, jobject thiz, jint type, jlongArray configs) {
    switch (type) {
        case rheatrace::TYPE_SAMPLING:
            return reinterpret_cast<jlong>(rheatrace::SamplingCollector::create(env,configs));
        default:
            return 0;
    }
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_bytedance_rheatrace_trace_base_TraceAbility_nativeStart(
        JNIEnv* env, jobject thiz, jlong collector, jlongArray extraConfigs) {
    if (collector == 0) {
        return JNI_FALSE;
    }
    return reinterpret_cast<rheatrace::PerfCollector*>(collector)->start(env, extraConfigs)
            ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_bytedance_rheatrace_trace_base_TraceAbility_nativeUpdateConfig(
        JNIEnv* env, jobject thiz, jlong collector, jlongArray configs) {
    reinterpret_cast<rheatrace::PerfCollector*>(collector)->updateConfigs(env,configs);
}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_bytedance_rheatrace_trace_base_TraceAbility_nativeMark(
        JNIEnv* env, jobject thiz, jlong collector) {
    return reinterpret_cast<rheatrace::PerfCollector*>(collector)->mark();
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_bytedance_rheatrace_trace_base_TraceAbility_nativeDumpTokenRange(
        JNIEnv* env, jobject thiz, jlong collector, jlong start, jlong end, jstring path, jstring jextra) {
    const char *dump_path = env->GetStringUTFChars(path, nullptr);
    const char* extra = nullptr;
    int32_t extraLen = 0;
    if (jextra != nullptr) {
        extra = env->GetStringUTFChars(jextra, nullptr);
        extraLen = env->GetStringUTFLength(jextra);
    }
    int result = reinterpret_cast<rheatrace::PerfCollector*>(collector)->dumpPart(env, dump_path,
                                                                                  extra, extraLen,
                                                                                  start, end);
    ALOGI("dump [%lld, %lld] result is %d, error is %s",
          static_cast<long long>(start), static_cast<long long>(end), result, strerror(result));
    env->ReleaseStringUTFChars(path, dump_path);
    if (extra != nullptr) {
        env->ReleaseStringUTFChars(jextra, extra);
    }
    return result;
}

namespace {
void dumpWithExtra(JNIEnv* env, jlong collector, jstring path, jstring jextra,
                  uint64_t startTimeNanos, uint64_t endTimeNanos,
                  int64_t snapshotEndTicket, jlong values[4]) {
    if (collector == 0 || path == nullptr) {
        values[0] = EINVAL;
        return;
    }
    const char* dumpPath = env->GetStringUTFChars(path, nullptr);
    const char* extra = nullptr;
    int32_t extraLen = 0;
    if (jextra != nullptr) {
        extra = env->GetStringUTFChars(jextra, nullptr);
        extraLen = env->GetStringUTFLength(jextra);
    }
    auto* perfCollector = reinterpret_cast<rheatrace::PerfCollector*>(collector);
    uint64_t actualStart = 0;
    uint64_t actualEnd = 0;
    uint32_t recordCount = 0;
    values[0] = perfCollector->dumpTimeRange(env, dumpPath, extra, extraLen,
                                             startTimeNanos, endTimeNanos,
                                             snapshotEndTicket, &actualStart, &actualEnd,
                                             &recordCount);
    values[1] = static_cast<jlong>(actualStart);
    values[2] = static_cast<jlong>(actualEnd);
    values[3] = static_cast<jlong>(recordCount);
    env->ReleaseStringUTFChars(path, dumpPath);
    if (extra != nullptr) {
        env->ReleaseStringUTFChars(jextra, extra);
    }
}
}

extern "C"
JNIEXPORT jlongArray JNICALL
Java_com_bytedance_rheatrace_trace_base_TraceAbility_nativeDumpTimeRange(
        JNIEnv* env, jobject thiz, jlong collector, jlong startTimeNanos, jlong endTimeNanos,
        jlong snapshotEndToken, jstring path, jstring extra) {
    jlong values[4] = {EINVAL, 0, 0, 0};
    dumpWithExtra(env, collector, path, extra,
                  static_cast<uint64_t>(startTimeNanos),
                  static_cast<uint64_t>(endTimeNanos),
                  static_cast<int64_t>(snapshotEndToken), values);
    jlongArray result = env->NewLongArray(4);
    if (result != nullptr) {
        env->SetLongArrayRegion(result, 0, 4, values);
    }
    return result;
}

extern "C"
JNIEXPORT jlongArray JNICALL
Java_com_bytedance_rheatrace_trace_base_TraceAbility_nativeGetBufferTimeRange(
        JNIEnv* env, jobject thiz, jlong collector) {
    jlong values[6] = {0, 0, 0, 0, 0, 0};
    if (collector != 0) {
        uint64_t start = 0;
        uint64_t end = 0;
        uint32_t count = 0;
        uint64_t overwritten = 0;
        int64_t snapshotEndTicket = 0;
        auto* perfCollector = reinterpret_cast<rheatrace::PerfCollector*>(collector);
        values[5] = static_cast<jlong>(perfCollector->getDroppedByRateLimit());
        if (perfCollector->getTimeRange(&start, &end, &count, &overwritten,
                                        &snapshotEndTicket)) {
            values[0] = static_cast<jlong>(start);
            values[1] = static_cast<jlong>(end);
            values[2] = static_cast<jlong>(count);
            values[3] = static_cast<jlong>(overwritten);
        }
        values[4] = static_cast<jlong>(snapshotEndTicket);
    }
    jlongArray result = env->NewLongArray(6);
    if (result != nullptr) {
        env->SetLongArrayRegion(result, 0, 6, values);
    }
    return result;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_bytedance_rheatrace_trace_base_TraceAbility_nativeDestroy(
        JNIEnv* env, jobject thiz, jint type, jlong collector) {
    if (collector == 0) {
        return JNI_FALSE;
    }
    switch (type) {
        case rheatrace::TYPE_SAMPLING:
            return rheatrace::SamplingCollector::destroy() ? JNI_TRUE : JNI_FALSE;
        default:
            return JNI_FALSE;
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_bytedance_rheatrace_trace_base_TraceAbility_nativeStop(
        JNIEnv* env, jobject thiz, jlong collector) {
    reinterpret_cast<rheatrace::PerfCollector*>(collector)->stop();
}
