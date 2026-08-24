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

#include <fcntl.h>
#include "PerfCollector.h"
#include "../utils/time.h"

namespace rheatrace {

template<uint32_t type, uint32_t version, bool dumpRawData, typename T>
class PerfCollectorBaseImpl : public PerfCollector {
protected:
    PerfBuffer<T>* mBuffer;
public:
    int dump(JNIEnv* env, const char* outDir, const char* extra, int32_t extraLen) override {
        if (outDir == nullptr) {
            return 1;
        }
        int dirPathLen = strlen(outDir);
        char* path = new char[dirPathLen + 64]; // suppose that dumpFileName is not longer than 64
        sprintf(path, "%s/%s", outDir, getDumpPerfFileName());
        int fd = open(path, O_RDWR | O_CREAT, S_IRUSR | S_IWUSR | S_IRGRP);
        if (fd == -1) {
            return errno;
        }
        Dumper* dumper = newDumper();
        int mappingFd = -1;
        if (dumper != nullptr && dumper->hasMapping()) {
            memset(path, 0, dirPathLen + 64);
            sprintf(path, "%s/%s", outDir, getDumpMappingFileName());
            mappingFd = open(path, O_RDWR | O_CREAT, S_IRUSR | S_IWUSR | S_IRGRP);
        }
        uint64_t curTime = current_boot_time_millis();
        int result = mBuffer->dump(env, fd, mappingFd, type, version, curTime, extra, extraLen,
                                   dumpRawData, dumper);
        delete[] path;
        delete dumper;
        close(fd);
        return result;
    }

    int dumpPart(JNIEnv* env, const char* outDir, const char* extra, int32_t extraLen,
                 int64_t startTicket, int64_t endTicket) override {
        if (outDir == nullptr) {
            return 1;
        }
        int dirPathLen = strlen(outDir);
        char* path = new char[dirPathLen + 64]; // suppose that dumpFileName is not longer than 64
        sprintf(path, "%s/%s", outDir, getDumpPerfFileName());
        int fd = open(path, O_RDWR | O_CREAT, S_IRUSR | S_IWUSR | S_IRGRP);
        if (fd == -1) {
            return errno;
        }
        Dumper* dumper = newDumper();
        int mappingFd = -1;
        if (dumper != nullptr && dumper->hasMapping()) {
            memset(path, 0, dirPathLen + 64);
            sprintf(path, "%s/%s", outDir, getDumpMappingFileName());
            mappingFd = open(path, O_RDWR | O_CREAT, S_IRUSR | S_IWUSR | S_IRGRP);
        }
        uint64_t curTime = current_boot_time_millis();
        int result = mBuffer->dumpPart(env, fd, mappingFd, type, version, curTime, extra, extraLen,
                                       dumpRawData, dumper, startTicket, endTicket);
        delete[] path;
        delete dumper;
        close(fd);
        return result;
    }

    int dumpTimeRange(JNIEnv* env, const char* outDir, const char* extra, int32_t extraLen,
                      uint64_t startTimeNanos, uint64_t endTimeNanos,
                      int64_t snapshotEndTicket, uint64_t* actualStartTimeNanos,
                      uint64_t* actualEndTimeNanos, uint32_t* dumpedRecordCount) override {
        if (outDir == nullptr || endTimeNanos <= startTimeNanos) {
            return 1;
        }
        int dirPathLen = strlen(outDir);
        char* path = new char[dirPathLen + 64];
        sprintf(path, "%s/%s", outDir, getDumpPerfFileName());
        int fd = open(path, O_RDWR | O_CREAT | O_TRUNC, S_IRUSR | S_IWUSR | S_IRGRP);
        if (fd == -1) {
            delete[] path;
            return errno;
        }
        Dumper* dumper = newDumper();
        int mappingFd = -1;
        if (dumper != nullptr && dumper->hasMapping()) {
            memset(path, 0, dirPathLen + 64);
            sprintf(path, "%s/%s", outDir, getDumpMappingFileName());
            mappingFd = open(path, O_RDWR | O_CREAT | O_TRUNC, S_IRUSR | S_IWUSR | S_IRGRP);
            if (mappingFd == -1) {
                int result = errno;
                delete[] path;
                delete dumper;
                close(fd);
                return result;
            }
        }
        uint64_t curTime = current_boot_time_millis();
        int result = mBuffer->dumpTimeRange(env, fd, mappingFd, type, version, curTime,
                extra, extraLen, dumpRawData, dumper, startTimeNanos, endTimeNanos,
                snapshotEndTicket, actualStartTimeNanos, actualEndTimeNanos, dumpedRecordCount,
                [this](T& record) { return getRecordStartTimeNanos(record); },
                [this](T& record) { return getRecordEndTimeNanos(record); });
        delete[] path;
        delete dumper;
        close(fd);
        if (mappingFd != -1) {
            close(mappingFd);
        }
        return result;
    }

    bool getTimeRange(uint64_t* startTimeNanos, uint64_t* endTimeNanos,
                      uint32_t* recordCount, uint64_t* overwrittenCount,
                      int64_t* snapshotEndTicket) override {
        return mBuffer->getTimeRange(startTimeNanos, endTimeNanos, recordCount,
                overwrittenCount, snapshotEndTicket,
                [this](T& record) { return getRecordStartTimeNanos(record); },
                [this](T& record) { return getRecordEndTimeNanos(record); });
    }

    int64_t mark() override {
        return mBuffer->mark();
    }

protected:
    PerfCollectorBaseImpl(PerfBuffer<T>* buffer) : mBuffer(buffer) {}

    virtual ~PerfCollectorBaseImpl() override {
        delete mBuffer;
    }

    virtual Dumper* newDumper() = 0;

    virtual const char* getDumpPerfFileName() = 0;

    virtual const char* getDumpMappingFileName() = 0;

    virtual uint64_t getRecordStartTimeNanos(T& record) = 0;

    virtual uint64_t getRecordEndTimeNanos(T& record) = 0;
};


} // namespace rheatrace
