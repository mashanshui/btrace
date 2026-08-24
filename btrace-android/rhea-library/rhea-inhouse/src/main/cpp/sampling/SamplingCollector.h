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
#pragma once

#include <jni.h>
#include "../base/PerfCollectorBaseImpl.h"
#include "SamplingRecord.h"
#include "SamplingConfig.h"
#include "StackVisitor.h"
#include "../utils/time.h"
#include <unistd.h>
#include <atomic>

namespace rheatrace {

/**
 * Collector of sampling stack trace. Major usage of this class is calling request() method in our
 * preset trace point to capture java stack synchronously and saved it to buffer inside this
 * collector.
 */
class SamplingCollector : public PerfCollectorBaseImpl<rheatrace::TYPE_SAMPLING, 5, false, SamplingRecord> {
public:
    static SamplingCollector* create(JNIEnv* env, jlongArray configs);

    static bool destroy() {
        auto* instance = sInstance.load(std::memory_order_acquire);
        if (instance != nullptr) {
            instance->stop();
        }
        return false;
    }

    static SamplingCollector* getInstance() {
        return sInstance.load(std::memory_order_acquire);
    }

    static void setOnlineEnabled(bool enabled) {
        sOnlineEnabled.store(enabled, std::memory_order_release);
    }

    static bool
    request(SamplingType type, void* self = nullptr, bool force = false, bool captureAtEnd = false,
            uint64_t beginNano = 0, uint64_t beginCpuNano = 0);

    static bool shouldCaptureCurrentThread();

    static void newJavaMessageWillBegin();

    bool start(JNIEnv* env, jlongArray asyncConfigs) override;

    void updateConfigs(JNIEnv* env, jlongArray configs) override;

    void stop() override {
        paused.store(true, std::memory_order_release);
        if (config.onlineMode) {
            sOnlineEnabled.store(false, std::memory_order_release);
        }
    }

    int64_t write(SamplingRecord& r) {
        return mBuffer->write(r);
    }

    bool isPaused() const {
        return paused.load(std::memory_order_acquire);
    }

    uint64_t getDroppedByRateLimit() override {
        return droppedByRateLimit.load(std::memory_order_relaxed);
    }

protected:

    Dumper* newDumper() override;

    const char* getDumpPerfFileName() override;

    const char* getDumpMappingFileName() override;

    uint64_t getRecordStartTimeNanos(SamplingRecord& record) override {
        return record.mNanoTime;
    }

    uint64_t getRecordEndTimeNanos(SamplingRecord& record) override {
        if (record.mEndNanoTime > record.mNanoTime) {
            return record.mEndNanoTime;
        }
        return record.mNanoTime == UINT64_MAX ? UINT64_MAX : record.mNanoTime + 1;
    }

private:

    SamplingCollector(PerfBuffer<SamplingRecord>* buffer, SamplingConfig& config)
            : PerfCollectorBaseImpl<rheatrace::TYPE_SAMPLING, 5, false, SamplingRecord>(buffer),
              config(config), paused(true) {
    }

    static std::atomic<SamplingCollector*> sInstance;
    static std::atomic<bool> sOnlineEnabled;
    SamplingConfig config;
    std::atomic<bool> paused;
    std::atomic<uint64_t> droppedByRateLimit{0};
};

class ScopeSampling {
public:
    uint64_t beginNano_;
private:
    void* self_;
    SamplingType type_;
    uint64_t beginCpuNano_;
    bool force_;
    bool condition_;
    pid_t tid;
public:
    explicit ScopeSampling(SamplingType type, void *self = nullptr, bool force = false)
            : beginNano_(0), self_(self), type_(type), beginCpuNano_(0), force_(force),
              condition_(SamplingCollector::shouldCaptureCurrentThread()), tid(gettid()) {
        if (condition_) {
            beginNano_ = current_boot_time_nanos();
            beginCpuNano_ = current_thread_cpu_time_nanos();
        }
    }

    ScopeSampling(ScopeSampling&) = delete;

    void setCondition(bool cond) {
        condition_ = condition_ && cond;
    }

    ~ScopeSampling() {
        if (condition_) {
            SamplingCollector::request(type_, self_, force_, true, beginNano_, beginCpuNano_);
        }
    }
};

} // namespace rheatrace
