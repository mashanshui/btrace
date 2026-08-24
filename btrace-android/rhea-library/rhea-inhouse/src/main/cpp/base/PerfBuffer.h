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


#include <errno.h>
#include <unistd.h>
#include <limits>
#include <vector>
#include "RingBuffer.h"
#include "common_write.h"

namespace rheatrace {

class Dumper {
public:
    virtual uint32_t dumpRecord(JNIEnv* env, void* addr, void* r) = 0;
    virtual bool hasMapping() = 0;
    virtual bool dumpMapping(int fd) = 0;
    virtual ~Dumper() {}
};

template<typename T>
class PerfBuffer {
private:
    std::atomic<int64_t> mTicket;
    RingBuffer<T>* mMajorBuffer;
    RingBuffer<T>* mBackupBuffer;
    std::atomic<bool> mUseBackupBuffer;
    std::atomic_flag mRouteLock = ATOMIC_FLAG_INIT;
    void* mMemoryArea;
    size_t mMemroyAreaSize;

    class AutoSwitchBufferHandler {
    private:
        PerfBuffer<T>& mPerfBuffer;
        int64_t mRoughStartTicket;
    public:
        AutoSwitchBufferHandler(PerfBuffer<T>& perfBuffer) : mPerfBuffer(perfBuffer) {
            mPerfBuffer.lockRoute();
            mPerfBuffer.mBackupBuffer->clear();
            mRoughStartTicket = mPerfBuffer.mMajorBuffer->getCurrentTicket();
            mPerfBuffer.mUseBackupBuffer.store(true, std::memory_order_release);
            mPerfBuffer.unlockRoute();
        }

        int64_t getMarkedTicket() {
            return mRoughStartTicket;
        }

        ~AutoSwitchBufferHandler() {
            auto* backupBuffer = mPerfBuffer.mBackupBuffer;
            mPerfBuffer.lockRoute();
            mPerfBuffer.mUseBackupBuffer.store(false, std::memory_order_release);
            int64_t roughEndTicket = mPerfBuffer.mMajorBuffer->getCurrentTicket();
            int64_t accurateStartTicket, accurateEndTicket;
            if (backupBuffer->findValidTicketRange(mRoughStartTicket, roughEndTicket,
                                                   &accurateStartTicket,
                                                   &accurateEndTicket)) {
                mPerfBuffer.mMajorBuffer->writesBack(*backupBuffer, accurateStartTicket, accurateEndTicket);
            }
            mPerfBuffer.unlockRoute();
        }
    };

public:

    static PerfBuffer<T>* create(uint64_t capacity, GetTimeFn<T> getTimeFn) {
        size_t singleBufferSize = RingBuffer<T>::calculateAllocationSize(capacity);
        size_t memorySize = ((singleBufferSize * 2) + ~PAGE_MASK) & PAGE_MASK;
        void* memory = mmap(nullptr, memorySize, PROT_READ | PROT_WRITE,
                            MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
        if (memory == MAP_FAILED) {
            return nullptr;
        }
        return new PerfBuffer<T>(capacity, memory, memorySize, getTimeFn);
    }

    PerfBuffer(uint64_t capacity, void* addr, size_t memorySize, GetTimeFn<T> getTimeFn)
            : mTicket(0), mMajorBuffer(nullptr), mBackupBuffer(nullptr),
              mUseBackupBuffer(false), mMemoryArea(addr), mMemroyAreaSize(memorySize) {
        char* memory = reinterpret_cast<char*>(mMemoryArea);
        mMajorBuffer = RingBuffer<T>::allocateAt(capacity, mTicket, getTimeFn, memory);
        mBackupBuffer = RingBuffer<T>::allocateAt(capacity,
                                                  mTicket,
                                                  getTimeFn,
                                                  (void*) (memory + memorySize / 2));
    }

    ~PerfBuffer() {
        mMajorBuffer->~RingBuffer<T>();
        mBackupBuffer->~RingBuffer<T>();
        munmap(mMemoryArea, mMemroyAreaSize);
    }

    int64_t capacity() {
        return mMajorBuffer->capacity();
    }

    int64_t write(T& value) {
        lockRoute();
        int64_t ticket = getCurrentRingBuffer()->write(value);
        unlockRoute();
        return ticket;
    }

    int64_t mark() {
        return getCurrentRingBuffer()->getCurrentTicket();
    }

    T& acquire() {
        return getCurrentRingBuffer()->acquire();
    }

    int
    dump(JNIEnv* env, int fd, int mappingFd, uint32_t type, uint32_t version, uint64_t time,
         const char* extra, int32_t extraLen, bool dumpRaw, Dumper* dumper) {
        AutoSwitchBufferHandler handler(*this);
        int64_t endTicket = handler.getMarkedTicket();
        uint32_t count = mMajorBuffer->availableCount(endTicket);
        return innerDump(env, fd, mappingFd, type, version, time, extra, extraLen, dumpRaw, dumper,
                         endTicket - count, endTicket);
    }

    int dumpPart(JNIEnv* env, int fd, int mappingFd, uint32_t type, uint32_t version, uint64_t time,
                 const char* extra, int32_t extraLen, bool dumpRaw, Dumper* dumper, int64_t startTicket, int64_t endTicket) {
        AutoSwitchBufferHandler handler(*this);
        int64_t curBufferStartTicket = std::max(
                handler.getMarkedTicket() - mMajorBuffer->capacity(), int64_t(0));
        int64_t realStartTicket = std::max(curBufferStartTicket, startTicket);
        int64_t realEndTicket = std::min(handler.getMarkedTicket(), endTicket);
        if (realStartTicket < realEndTicket) {
            return innerDump(env, fd, mappingFd, type, version, time, extra, extraLen, dumpRaw,
                             dumper, realStartTicket, realEndTicket);
        } else {
            return 8;
        }
    }

    int dumpTimedPart(JNIEnv* env, int fd, int mappingFd, uint32_t type, uint32_t version,
                      uint64_t time, const char* extra, int32_t extraLen, bool dumpRaw,
                      Dumper* dumper, int64_t endTicket, uint64_t startTimeMillis) {
        AutoSwitchBufferHandler handler(*this);
        int64_t startTicket;
        if (mMajorBuffer->findTimeTicket(startTimeMillis, endTicket, &startTicket) && startTicket < endTicket) {
            return innerDump(env, fd, mappingFd, type, version, time, extra, extraLen, dumpRaw,
                             dumper, startTicket, endTicket);
        }
        return 9;
    }

    template<typename GetStartTimeFn, typename GetEndTimeFn>
    bool getTimeRange(uint64_t* startTimeNanos, uint64_t* endTimeNanos,
                      uint32_t* recordCount, uint64_t* overwrittenCount,
                      int64_t* snapshotEndTicket,
                      GetStartTimeFn getStartTime, GetEndTimeFn getEndTime) {
        AutoSwitchBufferHandler handler(*this);
        int64_t endTicket = handler.getMarkedTicket();
        if (snapshotEndTicket != nullptr) {
            *snapshotEndTicket = endTicket;
        }
        uint32_t count = mMajorBuffer->availableCount(endTicket);
        uint64_t earliest = std::numeric_limits<uint64_t>::max();
        uint64_t latest = 0;
        uint32_t validCount = 0;
        T value;
        for (int64_t ticket = endTicket - count; ticket < endTicket; ++ticket) {
            if (!mMajorBuffer->readAt(ticket, &value)) {
                continue;
            }
            earliest = std::min(earliest, getStartTime(value));
            latest = std::max(latest, getEndTime(value));
            ++validCount;
        }
        if (recordCount != nullptr) {
            *recordCount = validCount;
        }
        if (overwrittenCount != nullptr) {
            *overwrittenCount = endTicket > int64_t(mMajorBuffer->capacity())
                    ? uint64_t(endTicket - mMajorBuffer->capacity()) : 0;
        }
        if (validCount == 0) {
            return false;
        }
        *startTimeNanos = earliest;
        *endTimeNanos = latest;
        return true;
    }

    template<typename GetStartTimeFn, typename GetEndTimeFn>
    int dumpTimeRange(JNIEnv* env, int fd, int mappingFd, uint32_t type, uint32_t version,
                      uint64_t time, const char* extra, int32_t extraLen, bool dumpRaw,
                      Dumper* dumper, uint64_t startTimeNanos, uint64_t endTimeNanos,
                      int64_t snapshotEndTicket, uint64_t* actualStartTimeNanos,
                      uint64_t* actualEndTimeNanos, uint32_t* dumpedRecordCount,
                      GetStartTimeFn getStartTime, GetEndTimeFn getEndTime) {
        if (endTimeNanos <= startTimeNanos) {
            return 10;
        }
        AutoSwitchBufferHandler handler(*this);
        int64_t endTicket = std::min(handler.getMarkedTicket(), snapshotEndTicket);
        uint32_t count = mMajorBuffer->availableCount(endTicket);
        std::vector<T> records;
        records.reserve(count);
        uint64_t actualStart = std::numeric_limits<uint64_t>::max();
        uint64_t actualEnd = 0;
        T value;
        bool expiredRecord = false;
        for (int64_t ticket = endTicket - count; ticket < endTicket; ++ticket) {
            if (!mMajorBuffer->readAt(ticket, &value)) {
                expiredRecord = true;
                continue;
            }
            uint64_t recordStart = getStartTime(value);
            uint64_t recordEnd = getEndTime(value);
            if (recordStart < endTimeNanos && recordEnd > startTimeNanos) {
                records.push_back(value);
                actualStart = std::min(actualStart, std::max(recordStart, startTimeNanos));
                actualEnd = std::max(actualEnd, std::min(recordEnd, endTimeNanos));
            }
        }
        if (records.empty()) {
            return 11;
        }
        if (actualStartTimeNanos != nullptr) {
            *actualStartTimeNanos = actualStart;
        }
        if (actualEndTimeNanos != nullptr) {
            *actualEndTimeNanos = actualEnd;
        }
        if (dumpedRecordCount != nullptr) {
            *dumpedRecordCount = static_cast<uint32_t>(records.size());
        }
        int result = innerDumpRecords(env, fd, mappingFd, type, version, time, extra, extraLen,
                                      dumpRaw, dumper, records);
        return result == 0 && expiredRecord ? 12 : result;
    }

private:
    RingBuffer<T>* getCurrentRingBuffer() {
        if (__builtin_expect(mUseBackupBuffer.load(std::memory_order_acquire), false)) {
            return mBackupBuffer;
        } else {
            return mMajorBuffer;
        }
    }

    int innerDump(JNIEnv* env, int fd, int mappingFd, uint32_t type, uint32_t version, uint64_t time,
                  const char* extra, int32_t extraLen, bool dumpRaw, Dumper* dumper, int64_t startTicket, int64_t endTicket) {
        uint32_t count = endTicket - startTicket;
        uint32_t magicNumber = 0x01020304;
        if (dumpRaw) {
            int64_t dumpSize =
                    sizeof(magicNumber) + sizeof(type) + sizeof(version) + sizeof(time) +
                    sizeof(count) + sizeof(extraLen) + extraLen +
                    (count * sizeof(T));
            if (ftruncate(fd, dumpSize) != 0) {
                return 3;
            }
            int64_t mmapSize = (dumpSize + ~PAGE_MASK) & PAGE_MASK;
            void* addr = mmap(nullptr, mmapSize, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
            if (addr == MAP_FAILED) {
                return errno;
            }
            char* writeAddr = static_cast<char*>(addr);
            uint32_t offset = rheatrace::writeBuf(writeAddr, magicNumber); // magic number
            offset += rheatrace::writeBuf(writeAddr + offset, type); // type
            offset += rheatrace::writeBuf(writeAddr + offset, version); // version
            offset += rheatrace::writeBuf(writeAddr + offset, time); // time
            offset += rheatrace::writeBuf(writeAddr + offset, count); // count
            // dump extra info
            if (extraLen > 0 && extra != nullptr) {
                offset += rheatrace::writeBuf(writeAddr + offset, extraLen);
                memcpy(writeAddr + offset, extra, extraLen);
                offset += extraLen;
            } else {
                offset += rheatrace::writeBuf(writeAddr + offset, int32_t(0));
            }
            mMajorBuffer->quickDump(reinterpret_cast<T*>(writeAddr + offset),
                                    startTicket, endTicket);
            msync(addr, dumpSize, MS_SYNC);
            munmap(addr, mmapSize);
            return 0;
        } else if (dumper != nullptr) {
            int64_t pageSize = sysconf(_SC_PAGE_SIZE);
            int64_t mapUnit = 128 * 1024;
            if (pageSize > mapUnit) {
                mapUnit = pageSize;
            }
            int64_t mmapSize = mapUnit * 4;
            if (ftruncate(fd, mmapSize) != 0) {
                return 5;
            }
            void* addr = mmap(nullptr, mmapSize, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
            if (addr == MAP_FAILED) {
                return errno;
            }
            char* writeAddr = static_cast<char*>(addr);
            uint32_t offset = rheatrace::writeBuf(writeAddr, magicNumber); // magic number
            offset += rheatrace::writeBuf(writeAddr + offset, type); // type
            offset += rheatrace::writeBuf(writeAddr + offset, version); // version
            offset += rheatrace::writeBuf(writeAddr + offset, time); // time
            offset += rheatrace::writeBuf(writeAddr + offset, count); // count
            // dump extra info
            if (extraLen > 0 && extra != nullptr) {
                offset += rheatrace::writeBuf(writeAddr + offset, extraLen);
                memcpy(writeAddr + offset, extra, extraLen);
                offset += extraLen;
            } else {
                offset += rheatrace::writeBuf(writeAddr + offset, int32_t(0));
            }
            int64_t currentFileMmapOffset = 0;
            for (int64_t i = endTicket - count; i < endTicket; i++) {
                if (mmapSize + currentFileMmapOffset - offset < mapUnit) {
                    // sync already dumped
                    msync(addr, mmapSize, MS_SYNC);
                    munmap(addr, mmapSize);
                    // grow file size and create new map
                    currentFileMmapOffset += mmapSize - mapUnit;
                    if (ftruncate(fd, currentFileMmapOffset + mmapSize) != 0) {
                        return 7;
                    }
                    addr = mmap(nullptr, mmapSize, PROT_READ | PROT_WRITE, MAP_SHARED, fd,
                                currentFileMmapOffset);
                    if (addr == MAP_FAILED) {
                        return 8;
                    }
                }
                offset += dumper->dumpRecord(env, static_cast<char*>(addr) + offset -
                                                  currentFileMmapOffset,
                                             &(mMajorBuffer->getAt(i)));
            }
            msync(addr, mmapSize, MS_SYNC);
            munmap(addr, mmapSize);
            ftruncate(fd, offset);

            if (dumper->hasMapping() && mappingFd != -1) {
                if (!dumper->dumpMapping(mappingFd)) {
                    return 13;
                }
            }

            return 0;
        } else {
            return 9;
        }
    }

    void lockRoute() {
        while (mRouteLock.test_and_set(std::memory_order_acquire)) {
        }
    }

    void unlockRoute() {
        mRouteLock.clear(std::memory_order_release);
    }

    int innerDumpRecords(JNIEnv* env, int fd, int mappingFd, uint32_t type, uint32_t version,
                         uint64_t time, const char* extra, int32_t extraLen, bool dumpRaw,
                         Dumper* dumper, const std::vector<T>& records) {
        uint32_t count = static_cast<uint32_t>(records.size());
        uint32_t magicNumber = 0x01020304;
        if (dumpRaw) {
            int64_t dumpSize = sizeof(magicNumber) + sizeof(type) + sizeof(version) + sizeof(time)
                    + sizeof(count) + sizeof(extraLen) + extraLen + count * sizeof(T);
            if (ftruncate(fd, dumpSize) != 0) {
                return 3;
            }
            int64_t mmapSize = (dumpSize + ~PAGE_MASK) & PAGE_MASK;
            void* addr = mmap(nullptr, mmapSize, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
            if (addr == MAP_FAILED) {
                return errno;
            }
            char* writeAddr = static_cast<char*>(addr);
            uint32_t offset = rheatrace::writeBuf(writeAddr, magicNumber);
            offset += rheatrace::writeBuf(writeAddr + offset, type);
            offset += rheatrace::writeBuf(writeAddr + offset, version);
            offset += rheatrace::writeBuf(writeAddr + offset, time);
            offset += rheatrace::writeBuf(writeAddr + offset, count);
            offset += rheatrace::writeBuf(writeAddr + offset, extraLen > 0 ? extraLen : int32_t(0));
            if (extraLen > 0 && extra != nullptr) {
                memcpy(writeAddr + offset, extra, extraLen);
                offset += extraLen;
            }
            memcpy(writeAddr + offset, records.data(), count * sizeof(T));
            msync(addr, dumpSize, MS_SYNC);
            munmap(addr, mmapSize);
            return 0;
        }
        if (dumper == nullptr) {
            return 9;
        }
        int64_t pageSize = sysconf(_SC_PAGE_SIZE);
        int64_t mapUnit = std::max(int64_t(128 * 1024), pageSize);
        int64_t mmapSize = mapUnit * 4;
        if (ftruncate(fd, mmapSize) != 0) {
            return 5;
        }
        void* addr = mmap(nullptr, mmapSize, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
        if (addr == MAP_FAILED) {
            return errno;
        }
        char* writeAddr = static_cast<char*>(addr);
        uint32_t offset = rheatrace::writeBuf(writeAddr, magicNumber);
        offset += rheatrace::writeBuf(writeAddr + offset, type);
        offset += rheatrace::writeBuf(writeAddr + offset, version);
        offset += rheatrace::writeBuf(writeAddr + offset, time);
        offset += rheatrace::writeBuf(writeAddr + offset, count);
        offset += rheatrace::writeBuf(writeAddr + offset, extraLen > 0 ? extraLen : int32_t(0));
        if (extraLen > 0 && extra != nullptr) {
            memcpy(writeAddr + offset, extra, extraLen);
            offset += extraLen;
        }
        int64_t currentFileMmapOffset = 0;
        for (const T& record : records) {
            if (mmapSize + currentFileMmapOffset - offset < mapUnit) {
                msync(addr, mmapSize, MS_SYNC);
                munmap(addr, mmapSize);
                currentFileMmapOffset += mmapSize - mapUnit;
                if (ftruncate(fd, currentFileMmapOffset + mmapSize) != 0) {
                    return 7;
                }
                addr = mmap(nullptr, mmapSize, PROT_READ | PROT_WRITE, MAP_SHARED, fd,
                            currentFileMmapOffset);
                if (addr == MAP_FAILED) {
                    return 8;
                }
            }
            T copy = record;
            offset += dumper->dumpRecord(env,
                    static_cast<char*>(addr) + offset - currentFileMmapOffset, &copy);
        }
        msync(addr, mmapSize, MS_SYNC);
        munmap(addr, mmapSize);
        ftruncate(fd, offset);
        if (dumper->hasMapping() && mappingFd != -1) {
            if (!dumper->dumpMapping(mappingFd)) {
                return 13;
            }
        }
        return 0;
    }
};

} // namespace rheatrace
