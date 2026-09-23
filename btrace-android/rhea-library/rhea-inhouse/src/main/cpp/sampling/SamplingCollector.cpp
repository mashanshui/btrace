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
#include <cstdio>

#include "../utils/time.h"
#include "../utils/misc.h"

#define LOG_TAG "RheaTrace:Sampling"
#include "../utils/log.h"


namespace rheatrace {

namespace {

// 仅保存本线程开启的会话代次，避免跨线程或跨生命周期结束统计。
thread_local std::vector<uint64_t> captureStatsSessionStack;

struct DurationSummary {
    bool hasSamples = false;
    size_t count = 0;
    long double minNs = 0;
    long double medianNs = 0;
    long double totalNs = 0;
    long double maxNs = 0;
};

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

DurationSummary summarizeDurations(std::vector<uint64_t>& samples) {
    DurationSummary summary;
    if (samples.empty()) {
        return summary;
    }
    std::sort(samples.begin(), samples.end());
    summary.hasSamples = true;
    summary.count = samples.size();
    summary.minNs = static_cast<long double>(samples.front());
    summary.maxNs = static_cast<long double>(samples.back());
    for (uint64_t sample : samples) {
        summary.totalNs += static_cast<long double>(sample);
    }
    summary.medianNs = static_cast<long double>(samples[summary.count / 2]);
    if (summary.count % 2 == 0) {
        summary.medianNs = (static_cast<long double>(samples[summary.count / 2 - 1])
                + static_cast<long double>(samples[summary.count / 2])) / 2.0L;
    }
    return summary;
}

std::string formatMilliseconds(bool hasSamples, long double nanos) {
    if (!hasSamples) {
        return "N/A";
    }
    char buffer[32];
    std::snprintf(buffer, sizeof(buffer), "%.3f",
                  static_cast<double>(nanos / 1000000.0L));
    return std::string(buffer);
}

std::string formatCaptureStatsLog(std::vector<uint64_t>& samples, uint64_t rateLimitedCount,
                                  uint64_t wastedNs) {
    const DurationSummary captureSummary = summarizeDurations(samples);
    const long double captureAvgNs = captureSummary.hasSamples
            ? captureSummary.totalNs / static_cast<long double>(captureSummary.count) : 0;
    const std::string captureMinMs = formatMilliseconds(captureSummary.hasSamples,
                                                        captureSummary.minNs);
    const std::string captureMedianMs = formatMilliseconds(captureSummary.hasSamples,
                                                           captureSummary.medianNs);
    const std::string captureAvgMs = formatMilliseconds(captureSummary.hasSamples, captureAvgNs);
    const std::string captureMaxMs = formatMilliseconds(captureSummary.hasSamples,
                                                        captureSummary.maxNs);
    char logBuffer[1024];
    std::snprintf(logBuffer, sizeof(logBuffer),
                  "stack capture stats: success_count=%zu, min_ms=%s, median_ms=%s, "
                  "avg_ms=%s, max_ms=%s, capture_total_ms=%.3f, rate_limited_count=%llu, "
                  "rate_limited_wasted_ms=%.3f",
                  captureSummary.count,
                  captureMinMs.c_str(),
                  captureMedianMs.c_str(),
                  captureAvgMs.c_str(),
                  captureMaxMs.c_str(),
                  captureSummary.hasSamples
                          ? static_cast<double>(captureSummary.totalNs / 1000000.0L) : 0.0,
                  static_cast<unsigned long long>(rateLimitedCount),
                  static_cast<double>(wastedNs) / 1000000.0);
    return std::string(logBuffer);
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

void SamplingCollector::beginStackTiming() {
    auto* collector = SamplingCollector::getInstance();
    if (collector == nullptr || !collector->config.enableStackCaptureStats
            || !shouldCaptureCurrentThread()) {
        return;
    }
    uint64_t stackTimingEpoch;
    {
        std::lock_guard<std::mutex> lock(collector->captureStatsMutex);
        // 进入锁后重新校验，防止与 stop 或线上开关切换并发启动无效会话。
        if (!shouldCaptureCurrentThread()) {
            return;
        }
        collector->requestDiagnostics = RequestDiagnostics{};
        collector->requestDiagnostics.start = current_boot_time_nanos();
        collector->requestDiagnostics.clockId = collector->config.clockId;
        collector->captureDurationSamplesNs.clear();
        collector->rateLimitedStatsCount = 0;
        collector->rateLimitedWastedNs = 0;
        stackTimingEpoch = collector->stackTimingEpoch.fetch_add(
                1, std::memory_order_acq_rel) + 1;
        collector->captureStatsActive.store(true, std::memory_order_release);
    }
    captureStatsSessionStack.push_back(stackTimingEpoch);
}

std::string SamplingCollector::endStackTiming() {
    if (captureStatsSessionStack.empty()) {
        return std::string();
    }
    const uint64_t stackTimingEpoch = captureStatsSessionStack.back();
    captureStatsSessionStack.pop_back();

    auto* collector = SamplingCollector::getInstance();
    if (collector == nullptr || !collector->config.enableStackCaptureStats
            || !shouldCaptureCurrentThread()) {
        return std::string();
    }
    RequestDiagnostics diagnostics;
    std::vector<uint64_t> samples;
    uint64_t rateLimitedCount;
    uint64_t wastedNs;
    {
        std::lock_guard<std::mutex> lock(collector->captureStatsMutex);
        if (!shouldCaptureCurrentThread()
                || !collector->captureStatsActive.load(std::memory_order_acquire)
                || stackTimingEpoch != collector->stackTimingEpoch.load(
                        std::memory_order_acquire)) {
            return std::string();
        }
        collector->captureStatsActive.store(false, std::memory_order_release);
        collector->requestDiagnostics.end = current_boot_time_nanos();
        std::swap(diagnostics, collector->requestDiagnostics);
        samples.swap(collector->captureDurationSamplesNs);
        rateLimitedCount = collector->rateLimitedStatsCount;
        wastedNs = collector->rateLimitedWastedNs;
        collector->rateLimitedStatsCount = 0;
        collector->rateLimitedWastedNs = 0;
    }
    // 排序和字符串格式化在锁外完成，避免阻塞其他线程的 request()。
    return formatCaptureStatsLog(samples, rateLimitedCount, wastedNs) + diagnostics.format();
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
    uint64_t statsEpoch = 0;
    uint64_t statsBeginNano = 0;
    bool collectStats = false;
    if (collector->config.enableStackCaptureStats
            && collector->captureStatsActive.load(std::memory_order_acquire)) {
        statsEpoch = collector->stackTimingEpoch.load(std::memory_order_acquire);
        statsBeginNano = current_boot_time_nanos();
        // 读取时钟后再校验会话，避免将 begin/end 切换边界上的请求记入新会话。
        collectStats = collector->captureStatsActive.load(std::memory_order_acquire)
                && statsEpoch == collector->stackTimingEpoch.load(std::memory_order_acquire);
    }
    auto currentNano = current_clock_id_time_nanos(collector->config.clockId);
    const uint64_t intervalNs = mainThread ? collector->config.mainThreadJavaIntervalNs
                                           : collector->config.otherThreadJavaIntervalNs;
    // 在线模式始终遵守硬间隔，避免调用方传入 force 造成线上抖动。
    const bool admitted = (collector->config.onlineMode ? false : force)
            || currentNano - lastJavaNano > intervalNs;
    const int statsTid = collectStats ? gettid() : 0;
    if (collectStats) {
        std::lock_guard<std::mutex> lock(collector->captureStatsMutex);
        collectStats = collector->captureStatsActive.load(std::memory_order_acquire)
                && statsEpoch == collector->stackTimingEpoch.load(std::memory_order_acquire);
        if (collectStats) {
            // 分桶达到上限不影响原有成功耗时和限流汇总。
            collector->requestDiagnostics.begin(statsTid, static_cast<int>(type),
                    statsBeginNano, intervalNs, currentNano, lastJavaNano, admitted);
        }
    }
    if (admitted) {
        lastJavaNano = currentNano;
        SamplingRecord r{};
        // 诊断会话统一输出，避免逐次 Logcat 干扰采样时间分布。
        const bool stackWalked = StackVisitor::visitOnce(
                r.mStack, self, collector->config.stackWalkKind);
        const bool stackComplete = stackWalked
                && r.mStack.mSavedDepth != 0
                && r.mStack.mSavedDepth == r.mStack.mActualDepth;
        if (!stackComplete) {
            if (collectStats) {
                collector->recordCaptureStats(statsEpoch, RequestDiagnostics::WALK_FAILED,
                        elapsedNanos(statsBeginNano, current_boot_time_nanos()),
                        statsTid, statsBeginNano);
            }
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
            collector->recordCaptureStats(statsEpoch, RequestDiagnostics::SUCCESS,
                    elapsedNanos(statsBeginNano, statsEndNano), statsTid, statsBeginNano);
        }
        return true;
    }
    collector->droppedByRateLimit.fetch_add(1, std::memory_order_relaxed);
    if (collectStats) {
        const uint64_t statsEndNano = current_boot_time_nanos();
        collector->recordCaptureStats(statsEpoch, RequestDiagnostics::LIMITED,
                elapsedNanos(statsBeginNano, statsEndNano), statsTid, statsBeginNano);
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
    captureStatsActive.store(false, std::memory_order_release);
    stackTimingEpoch.fetch_add(1, std::memory_order_acq_rel);
    requestDiagnostics = RequestDiagnostics{};
    captureDurationSamplesNs.clear();
    rateLimitedStatsCount = 0;
    rateLimitedWastedNs = 0;
}

void SamplingCollector::recordCaptureStats(uint64_t statsEpoch, RequestDiagnostics::Outcome outcome,
                                           uint64_t elapsedNs, int tid, uint64_t requestNs) {
    std::lock_guard<std::mutex> lock(captureStatsMutex);
    // endStackTiming() 会先关闭会话再快照；较晚完成的抓栈请求将被丢弃。
    if (!captureStatsActive.load(std::memory_order_acquire)
            || statsEpoch != stackTimingEpoch.load(std::memory_order_acquire)) {
        return;
    }
    requestDiagnostics.finish(tid, requestNs, outcome);
    if (outcome == RequestDiagnostics::SUCCESS) {
        captureDurationSamplesNs.push_back(elapsedNs);
    } else if (outcome == RequestDiagnostics::LIMITED) {
        rateLimitedStatsCount++;
        addSaturated(&rateLimitedWastedNs, elapsedNs);
    }
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
