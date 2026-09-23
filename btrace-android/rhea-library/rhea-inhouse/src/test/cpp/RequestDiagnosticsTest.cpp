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
#include "../../main/cpp/sampling/RequestDiagnostics.h"
#include <cassert>
#include <iostream>

using rheatrace::RequestDiagnostics;
int main() {
    RequestDiagnostics d;
    d.start = 1000000000;
    // 稠密请求集中于首桶，30ms 后才出现下一次请求。
    for (int i = 0; i < 100; ++i) {
        auto now = d.start + i * 1000;
        assert(d.begin(1, 9, now, 10000000, now, 0, i == 0));
        d.finish(1, now, i == 0 ? RequestDiagnostics::SUCCESS : RequestDiagnostics::LIMITED);
    }
    assert(d.begin(1, 10, d.start + 30000000, 10000000, 30000000, 10000000, true));
    d.finish(1, d.start + 30000000, RequestDiagnostics::WALK_FAILED);
    // 同桶不同线程独立计数，未完成请求留在 pending 中。
    assert(d.begin(2, 9, d.start + 10000000, 10000000, 20000000, 10000000, true));
    d.end = d.start + 45000000;
    assert(d.threads.at(1).total.requests == 101);
    assert(d.threads.at(1).total.limited == 99);
    assert(d.threads.at(1).total.failed == 1);
    assert(d.threads.at(1).total.admitted == 2);
    assert(d.threads.at(1).maxGap == 29901000);
    assert(d.threads.at(2).hasInitialAge);
    auto report = d.format();
    assert(report.find("bucket tid=1 begin_ms=10 end_ms=20 request_count=0") != std::string::npos);
    assert(report.find("pending_count=1") != std::string::npos);
    assert(report.find("types={9:100,10:1,") != std::string::npos);
    // 超出时间上限只丢分桶详情，仍累计线程总数。
    assert(d.begin(1, 9, d.start + 10000000000ULL, 10, 20, 10, true));
    d.finish(1, d.start + 10000000000ULL, RequestDiagnostics::SUCCESS);
    assert(d.omitted == 1 && d.threads.at(1).total.success == 2);
    assert(d.threads.at(1).buckets.size() == 2);
    for (int tid = 3; tid <= 64; ++tid) assert(d.begin(tid, 9, d.start, 10, 20, 0, true));
    assert(!d.begin(65, 9, d.start, 10, 20, 0, true));
    assert(d.omitted == 2);
    assert(!d.begin(1, 9, d.start - 1, 10, 20, 0, true));
    d = RequestDiagnostics{};
    d.start = d.end = 10;
    assert(d.threads.empty() && d.format().find("duration_ms=0") != std::string::npos);
    std::cout << "RequestDiagnosticsTest passed" << std::endl;
}
