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
#include <algorithm>
#include <cstdint>
#include <map>
#include <sstream>
#include <string>

namespace rheatrace {
// 仅在显式诊断会话内使用，由调用方持有 captureStatsMutex。
// 最多保留 64 个线程、每线程 1000 个 10ms 桶；超限明确报告，避免无限增长。
struct RequestDiagnostics {
    static constexpr uint64_t BUCKET_NS = 10000000;
    enum Outcome { LIMITED, SUCCESS, WALK_FAILED };
    struct Counts {
        uint64_t requests = 0, limited = 0, admitted = 0, success = 0, failed = 0;
        std::map<int, uint64_t> types;
    };
    struct Thread {
        Counts total;
        std::map<uint64_t, Counts> buckets;
        uint64_t first = 0, last = 0, maxGap = 0, interval = 0, initialAge = 0;
        bool hasInitialAge = false;
    };
    uint64_t start = 0, end = 0, omitted = 0;
    int clockId = 0;
    std::map<int, Thread> threads;

    bool begin(int tid, int type, uint64_t now, uint64_t interval,
               uint64_t gateNow, uint64_t previous, bool admitted) {
        if (now < start) return false;
        if (threads.find(tid) == threads.end() && threads.size() >= 64) {
            ++omitted;
            return false;
        }
        auto &t = threads[tid];
        if (!t.total.requests) {
            t.first = now;
            t.interval = interval;
            t.hasInitialAge = previous != 0 && gateNow >= previous;
            t.initialAge = t.hasInitialAge ? gateNow - previous : 0;
        } else {
            t.maxGap = std::max(t.maxGap, now >= t.last ? now - t.last : 0);
        }
        t.last = now;
        auto update = [&](Counts &c) {
            ++c.requests;
            ++c.types[type];
            if (admitted) ++c.admitted;
        };
        update(t.total);
        uint64_t bucket = (now - start) / BUCKET_NS;
        if (bucket < 1000) update(t.buckets[bucket]);
        else ++omitted;
        return true;
    }

    void finish(int tid, uint64_t now, Outcome outcome) {
        auto found = threads.find(tid);
        if (found == threads.end()) return;
        auto update = [&](Counts &c) {
            if (outcome == LIMITED) ++c.limited;
            else if (outcome == SUCCESS) ++c.success;
            else ++c.failed;
        };
        update(found->second.total);
        auto bucket = found->second.buckets.find((now - start) / BUCKET_NS);
        if (bucket != found->second.buckets.end()) update(bucket->second);
    }

    static void counts(std::ostringstream &out, const Counts &c) {
        out << " request_count=" << c.requests << " rate_limited_count=" << c.limited
            << " admitted_count=" << c.admitted << " walk_failed_count=" << c.failed
            << " success_count=" << c.success
            << " pending_count=" << c.requests - c.limited - c.failed - c.success;
        out << " types={";
        for (const auto &type : c.types) out << type.first << ':' << type.second << ',';
        out << '}';
    }

    std::string format() const {
        std::ostringstream out;
        out << "\nrequest diagnostics: session_start_ns=" << start
            << " session_end_ns=" << end << " duration_ms=" << (end - start) / 1e6
            << " clock_id=" << clockId << " bucket_ms=10 omitted_count=" << omitted;
        for (const auto &entry : threads) {
            const auto &t = entry.second;
            out << "\nthread tid=" << entry.first << " interval_ns=" << t.interval
                << " first_request_ms=" << (t.first - start) / 1e6
                << " last_request_ms=" << (t.last - start) / 1e6
                << " max_request_gap_ms=" << t.maxGap / 1e6
                << " tail_gap_ms=" << (end >= t.last ? end - t.last : 0) / 1e6
                << " initial_gate_age_ms=";
            if (t.hasInitialAge) out << t.initialAge / 1e6;
            else out << "N/A";
            counts(out, t.total);
            // 输出空桶（包括首尾空白），使没有 Hook 请求的时间段可见。
            const auto size = std::min<uint64_t>(1000, (end - start) / BUCKET_NS + 1);
            for (uint64_t i = 0; i < size; ++i) {
                out << "\nbucket tid=" << entry.first << " begin_ms=" << i * 10
                    << " end_ms=" << std::min<uint64_t>((i + 1) * BUCKET_NS, end - start) / 1e6;
                auto b = t.buckets.find(i);
                counts(out, b == t.buckets.end() ? Counts{} : b->second);
            }
        }
        return out.str();
    }
};
} // namespace rheatrace
