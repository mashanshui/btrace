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
#include "SamplingCollector.h"
#include "Stack.h"
#include "StackVisitor.h"
#include "../trace/SamplingTrace.h"
#include "../stat/JavaObjectStat.h"
#include "SamplingRecord.h"
#include <unistd.h>
#include <unordered_set>
#include <setjmp.h>
#include <sys/resource.h>
#include <dirent.h>
#include <new>
#include <string>
#include <algorithm>

#include "../utils/time.h"
#include "../utils/misc.h"

#define LOG_TAG "RheaTrace:Sampling"
#include "../utils/log.h"


namespace rheatrace {

namespace {

constexpr uint64_t kCaptureStatsWindowNs = 1ULL * 1000ULL * 1000ULL * 1000ULL;

uint64_t elapsedNanos(uint64_t beginNs, uint64_t endNs) {
    return endNs >= beginNs ? endNs - beginNs : 0;
}

void addSaturated(uint64_t* value, uint64_t delta) {
    if (UINT64_MAX - *value < delta) {
        *value = UINT64_MAX;
    } else {
        *value += delta;
    }
}

} // namespace

std::atomic<SamplingCollector*> SamplingCollector::sInstance{nullptr};
std::atomic<bool> SamplingCollector::sOnlineEnabled{false};

static uint64_t getStackRecordTime(SamplingRecord& r) {
    return r.mEndNanoTime == 0 ? r.mNanoTime : r.mEndNanoTime;
}

SamplingCollector* SamplingCollector::create(JNIEnv* env, jlongArray rawConfig) {
    auto* instance = sInstance.load(std::memory_order_acquire);
    if (instance == nullptr) {
        SamplingConfig config(env, rawConfig);
        auto* buffer = PerfBuffer<SamplingRecord>::create(config.capacity, getStackRecordTime);
        if (buffer == nullptr) {
            ALOGE("create sampling buffer failed, capacity=%lld",
                  static_cast<long long>(config.capacity));
            return nullptr;
        }
        struct timespec ts{};
        clock_getres(config.clockId, &ts);
        ALOGI("clockId is %d, resolution is %ldns, visitKind is %d, interval is %lluns",
              config.clockId, ts.tv_nsec, config.stackWalkKind,
              static_cast<unsigned long long>(config.mainThreadJavaIntervalNs));
        instance = new(std::nothrow) SamplingCollector(buffer, config);
        if (instance == nullptr) {
            delete buffer;
            ALOGE("create sampling collector failed");
            return nullptr;
        }
        sInstance.store(instance, std::memory_order_release);
    }
    return instance;
}

thread_local uint64_t lastJavaNano = 0;

thread_local uint32_t messageIndex = 0;

void SamplingCollector::newJavaMessageWillBegin() {
    messageIndex++;
}

bool SamplingCollector::shouldCaptureCurrentThread() {
    auto* collector = getInstance();
    if (collector == nullptr || collector->isPaused()) {
        return false;
    }
    if (!collector->config.onlineMode) {
        return true;
    }
    return sOnlineEnabled.load(std::memory_order_acquire)
            && (!collector->config.mainThreadOnly || is_main_thread());
}

bool SamplingCollector::request(SamplingType type, void* self, bool force, bool captureAtEnd,
                                uint64_t beginNano, uint64_t beginCpuNano) {
    auto* collector = SamplingCollector::getInstance();
    if (collector == nullptr || collector->isPaused()) {
        return false;
    }
    const bool mainThread = is_main_thread();
    if (collector->config.onlineMode) {
        if (!sOnlineEnabled.load(std::memory_order_acquire)
                || (collector->config.mainThreadOnly && !mainThread)) {
            return false;
        }
    }
    const bool collectStats = collector->config.enableStackCaptureStats;
    const uint64_t statsBeginNano = collectStats ? current_boot_time_nanos() : 0;
    auto currentNano = current_clock_id_time_nanos(collector->config.clockId);
    const uint64_t intervalNs = mainThread ? collector->config.mainThreadJavaIntervalNs
                                           : collector->config.otherThreadJavaIntervalNs;
    // 在线模式始终遵守硬间隔，避免调用方传入 force 造成线上抖动。
    if ((collector->config.onlineMode ? false : force)
            || currentNano - lastJavaNano > intervalNs) {
        lastJavaNano = currentNano;
        SamplingRecord r{};
        if (StackVisitor::visitOnce(r.mStack, self, collector->config.stackWalkKind)) {
            if (r.mStack.mSavedDepth == 0 || r.mStack.mSavedDepth != r.mStack.mActualDepth) {
                return false;
            }
        } else {
            return false;
        }
        r.mType = type;
        r.mTid = gettid();
        r.mMessageId = messageIndex;

        auto& objectStat = JavaObjectStat::getAllocatedObjectStat();
        r.mAllocatedObjects = objectStat.objects;
        r.mAllocatedBytes = objectStat.bytes;
        if (collector->config.enableRusage) {
            struct rusage ru;
            if (getrusage(RUSAGE_THREAD, &ru) == 0) {
                r.mMajFlt = ru.ru_majflt;
                r.mNvCsw = ru.ru_nvcsw;
                r.mNivCsw = ru.ru_nivcsw;
            }
        }
        if (captureAtEnd) {
            r.mNanoTime = beginNano;
            r.mCpuTime = beginCpuNano;
            r.mEndNanoTime = currentNano;
            r.mEndCpuTime = current_thread_cpu_time_nanos();
        } else {
            r.mNanoTime = current_boot_time_nanos();
            r.mCpuTime = current_thread_cpu_time_nanos();
            r.mEndNanoTime = 0;
            r.mEndCpuTime = 0;
        }
        collector->write(r);
        if (collectStats) {
            const uint64_t statsEndNano = current_boot_time_nanos();
            collector->recordCaptureStats(true, elapsedNanos(statsBeginNano, statsEndNano),
                                          statsEndNano);
        }
        return true;
    }
    collector->droppedByRateLimit.fetch_add(1, std::memory_order_relaxed);
    if (collectStats) {
        const uint64_t statsEndNano = current_boot_time_nanos();
        collector->recordCaptureStats(false, elapsedNanos(statsBeginNano, statsEndNano),
                                      statsEndNano);
    }
    return false;
}

bool SamplingCollector::start(JNIEnv* env, jlongArray asyncConfigs) {
    if (!StackVisitor::init()) {
        ALOGE("StackVisitor init failed");
        return false;
    }
    if (!trace::init(env, asyncConfigs, config.enableObjectAllocationStub, config.enableWakeup,
                     config.shadowPauseMode, config.enableJniHook)) {
        ALOGE("sampling hooks init failed");
        return false;
    }
    resetCaptureStats();
    paused.store(false, std::memory_order_release);
    if (config.onlineMode) {
        sOnlineEnabled.store(true, std::memory_order_release);
    }
    return true;
}

void SamplingCollector::resetCaptureStats() {
    std::lock_guard<std::mutex> lock(captureStatsMutex);
    captureStatsWindowStartNs = current_boot_time_nanos();
    captureDurationSamplesNs.clear();
    rateLimitedStatsCount = 0;
    rateLimitedWastedNs = 0;
}

void SamplingCollector::recordCaptureStats(bool complete, uint64_t elapsedNs, uint64_t nowNs) {
    std::vector<uint64_t> samples;
    uint64_t rateLimitedCount = 0;
    uint64_t wastedNs = 0;
    bool shouldReport = false;
    {
        std::lock_guard<std::mutex> lock(captureStatsMutex);
        // request() 可由多个线程并发返回；较早完成的调用不能把已经开始的窗口回拨。
        if (captureStatsWindowStartNs == 0) {
            captureStatsWindowStartNs = nowNs;
        }
        if (complete) {
            captureDurationSamplesNs.push_back(elapsedNs);
        } else {
            rateLimitedStatsCount++;
            addSaturated(&rateLimitedWastedNs, elapsedNs);
        }
        if (nowNs >= captureStatsWindowStartNs
                && nowNs - captureStatsWindowStartNs >= kCaptureStatsWindowNs) {
            samples.swap(captureDurationSamplesNs);
            rateLimitedCount = rateLimitedStatsCount;
            wastedNs = rateLimitedWastedNs;
            rateLimitedStatsCount = 0;
            rateLimitedWastedNs = 0;
            captureStatsWindowStartNs = nowNs;
            shouldReport = true;
        }
    }
    if (!shouldReport) {
        return;
    }

    if (samples.empty()) {
        ALOGI("stack capture stats (5s): success_count=0, min_ms=N/A, median_ms=N/A, "
              "avg_ms=N/A, max_ms=N/A, capture_total_ms=0.000, rate_limited_count=%llu, "
              "rate_limited_wasted_ms=%.3f",
              static_cast<unsigned long long>(rateLimitedCount),
              static_cast<double>(wastedNs) / 1000000.0);
        return;
    }

    std::sort(samples.begin(), samples.end());
    const size_t sampleCount = samples.size();
    long double totalNs = 0;
    for (uint64_t sample : samples) {
        totalNs += static_cast<long double>(sample);
    }
    long double medianNs = static_cast<long double>(samples[sampleCount / 2]);
    if (sampleCount % 2 == 0) {
        medianNs = (static_cast<long double>(samples[sampleCount / 2 - 1])
                + static_cast<long double>(samples[sampleCount / 2])) / 2.0L;
    }
    ALOGI("stack capture stats (5s): success_count=%zu, min_ms=%.3f, median_ms=%.3f, "
          "avg_ms=%.3f, max_ms=%.3f, capture_total_ms=%.3f, rate_limited_count=%llu, "
          "rate_limited_wasted_ms=%.3f",
          sampleCount,
          static_cast<double>(samples.front()) / 1000000.0,
          static_cast<double>(medianNs) / 1000000.0,
          static_cast<double>(totalNs / static_cast<long double>(sampleCount) / 1000000.0L),
          static_cast<double>(samples.back()) / 1000000.0,
          static_cast<double>(totalNs / 1000000.0L),
          static_cast<unsigned long long>(rateLimitedCount),
          static_cast<double>(wastedNs) / 1000000.0);
}

class SamplingDumper : public Dumper {
private:
    std::unordered_set<uint64_t> mMethodIds;
    bool enableThreadNames;
public:
    explicit SamplingDumper(bool threadNames) : enableThreadNames(threadNames) {}

    uint32_t dumpRecord(JNIEnv* env, void* addr, void* r) override;

    bool hasMapping() override;

    bool dumpMapping(int fd) override;
};

Dumper* SamplingCollector::newDumper() {
    return new SamplingDumper(config.enabledThreadNames);
}

const char* SamplingCollector::getDumpPerfFileName() {
    return "sampling";
}

const char* SamplingCollector::getDumpMappingFileName() {
    return "sampling-mapping";
}

void SamplingCollector::updateConfigs(JNIEnv* env, jlongArray rawUpdatableConfig) {
    config.update(env, rawUpdatableConfig);
}

uint32_t SamplingDumper::dumpRecord(JNIEnv* env, void* addr, void* r) {
    SamplingRecord* record = reinterpret_cast<SamplingRecord*>(r);
    return record->encodeInto(reinterpret_cast<char*>(addr), &mMethodIds);
}

bool SamplingDumper::hasMapping() {
    return true;
}

thread_local struct sigaction preSEGVAction;
thread_local jmp_buf dumpMappingJmp;

void dumpMappingSIGSEGVHandler(int signo, siginfo_t *info, void *context) {
    if (sigaction(signo, &preSEGVAction, nullptr) != 0) {
        ALOGE("unregister signal %d handler failed: %m", signo);
    }
    siglongjmp(dumpMappingJmp, 1);
}

bool SamplingDumper::dumpMapping(int fd) {
    struct sigaction act{};
    act.sa_flags = SA_SIGINFO;
    act.sa_sigaction = dumpMappingSIGSEGVHandler;
    if (sigaction(SIGSEGV, &act, &preSEGVAction) != 0) {
        ALOGE("sigaction failed.");
        return false;
    }
    if (sigsetjmp(dumpMappingJmp, 1) == 0) {
        uint64_t magic = 0;
        uint32_t version = 1;
        write(fd, &magic, sizeof(magic));
        write(fd, &version, sizeof(version));
        uint32_t count = mMethodIds.size();
        write(fd, &count, sizeof(count));
        for (const auto &item: mMethodIds) {
            write(fd, &item, sizeof(item));
            std::string symbol = Stack::toString(reinterpret_cast<void *>(item));
            uint16_t len = symbol.length();
            write(fd, &len, sizeof(len));
            auto buf = symbol.c_str();
            write(fd, buf, symbol.length());
        }
        // thread names
        if (enableThreadNames) {
            auto now = current_boot_time_millis();
            const char *task_dir = "/proc/self/task";
            DIR *dir = opendir(task_dir);
            if (dir != nullptr) {
                struct dirent *entry;
                while ((entry = readdir(dir)) != nullptr) {
                    // Skip the current (.) and parent (..) entries
                    if (entry->d_type == DT_DIR && strcmp(entry->d_name, ".") != 0 && strcmp(entry->d_name, "..") != 0) {
                        auto tid = (pid_t) atoi(entry->d_name);
                        char path[256];
                        snprintf(path, sizeof(path), "/proc/self/task/%d/comm", tid);
                        FILE *file = fopen(path, "r");
                        if (file) {
                            char thread_name[17];
                            if (fgets(thread_name, sizeof(thread_name), file) != nullptr) {
                                write(fd, &tid, 2);
                                thread_name[16] = 0;
                                uint8_t len = strlen(thread_name);
                                write(fd, &len, 1);
                                write(fd, thread_name, len);
                            }
                            fclose(file);
                        }
                    }
                }
                closedir(dir);
            }
            auto cost = current_boot_time_millis() - now;
            ALOGD("dump thread names cost %llums", static_cast<unsigned long long>(cost));
        }
        return true;
    } else {
        return false;
    }
}

} // namespace rheatrace
