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
package com.bytedance.rheatrace.trace.base;

import androidx.annotation.NonNull;

import com.bytedance.rheatrace.trace.TraceConfigurations;

/**
 * Abstraction of handling different kinds of traceable perf data's collection.
 *
 * @param <T> Configuration for the collection.
 */
public abstract class TraceAbility<T extends TraceConfig> {

    public static final long START_FAILED = Long.MIN_VALUE;

    private int activeCount = 0;
    private long nativeCollectorPtr = 0;

    public synchronized long start() {
        if (activeCount == 0) {
            if (nativeCollectorPtr == 0) {
                nativeCollectorPtr = nativeCreate(getMeta().getOffset(), getDeflatedConfigs());
            }
            if (nativeCollectorPtr == 0
                    || !nativeStart(nativeCollectorPtr, getExtraStartConfig())) {
                return START_FAILED;
            }
        } else {
            nativeUpdateConfig(nativeCollectorPtr, getDeflatedUpdatableConfigs());
        }
        activeCount++;
        return nativeMark(nativeCollectorPtr);
    }

    public synchronized long stop() {
        if (nativeCollectorPtr == 0 || activeCount == 0) {
            return 0;
        }
        long result = nativeMark(nativeCollectorPtr);
        if (--activeCount == 0) {
            if (nativeCollectorPtr != 0) {
                nativeStop(nativeCollectorPtr);
            }
        }
        return result;
    }

    /** 返回当前采集器游标，用于在线事件导出时截取环形缓冲区。 */
    public synchronized long mark() {
        if (nativeCollectorPtr == 0 || activeCount == 0) {
            return 0;
        }
        return nativeMark(nativeCollectorPtr);
    }

    public int dumpTokenRange(long start, long end, String path, String extra) {
        TraceMeta meta = getMeta();
        if (meta.isCore()) {
            return nativeDumpTokenRange(nativeCollectorPtr, start, end, path, extra);
        } else {
            return nativeDumpTokenRange(nativeCollectorPtr, start, end, path, null);
        }
    }

    /** 返回 native 状态、实际开始、实际结束和记录数。 */
    public long[] dumpTimeRange(long startTimeNanos, long endTimeNanos, long snapshotEndToken,
                                String path, String extra) {
        TraceMeta meta = getMeta();
        return nativeDumpTimeRange(nativeCollectorPtr, startTimeNanos, endTimeNanos,
                snapshotEndToken, path, meta.isCore() ? extra : null);
    }

    /** startNs、endNs、recordCount、overwrittenCount、endToken、限流丢弃数。 */
    public synchronized long[] getBufferTimeRange() {
        if (nativeCollectorPtr == 0 || activeCount == 0) {
            return new long[]{0, 0, 0, 0};
        }
        return nativeGetBufferTimeRange(nativeCollectorPtr);
    }

    /**
     * 终止采集但保留 Native 采集器到进程退出。部分 ART Hook 无法安全撤销，立即释放会让
     * 已安装的回调访问悬空指针。
     */
    public synchronized void destroy() {
        if (nativeCollectorPtr == 0) {
            activeCount = 0;
            return;
        }
        if (activeCount > 0) {
            nativeStop(nativeCollectorPtr);
        }
        activeCount = 0;
        if (nativeDestroy(getMeta().getOffset(), nativeCollectorPtr)) {
            nativeCollectorPtr = 0;
        }
    }

    @NonNull
    protected abstract TraceMeta getMeta();

    protected abstract long[] getExtraStartConfig();

    private long[] getDeflatedConfigs() {
        T config = TraceConfigurations.getConfig(getMeta());
        if (config != null) {
            return config.deflate();
        }
        return new long[0];
    }

    private long[] getDeflatedUpdatableConfigs() {
        TraceMeta meta = getMeta();
        T config = TraceConfigurations.getConfig(meta);
        if (config == null) {
            throw new RuntimeException(meta.getName() + " has no config");
        }
        return config.deflateUpdatable();
    }

    private native long nativeCreate(int type, long[] configs);

    private native boolean nativeStart(long collector, long[] extraConfigs);

    private native void nativeUpdateConfig(long collector, long[] configs);

    private native long nativeMark(long collector);

    private native int nativeDumpTokenRange(long collector, long start, long end, String path, String extra);

    private native long[] nativeDumpTimeRange(long collector, long startTimeNanos,
                                              long endTimeNanos, long snapshotEndToken,
                                              String path, String extra);

    private native long[] nativeGetBufferTimeRange(long collector);

    private native boolean nativeDestroy(int type, long collector);

    private native void nativeStop(long collector);
}
