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
package com.bytedance.rheatrace;

import android.app.Application;
import android.content.Context;

import com.bytedance.rheatrace.trace.base.TraceGlobal;
import com.bytedance.rheatrace.utils.ProcessUtils;

import java.io.File;
import java.util.Collections;
import java.util.List;

/** RheaTrace 对外公开 API。当前只支持主进程采集。 */
public final class RheaTrace3 {

    public enum InitResult {
        STARTED, ALREADY_STARTED, MODE_CONFLICT, NOT_MAIN_PROCESS, UNSUPPORTED_DEVICE,
        INVALID_CONFIG, NATIVE_INIT_FAILED, DISABLED
    }

    public enum ExportRequestResult {
        ACCEPTED, NOT_INITIALIZED, DISABLED, INVALID_RANGE, FUTURE_RANGE,
        EMPTY_RANGE, BUSY, STORAGE_UNAVAILABLE
    }

    public enum ExportStatus {
        SUCCESS, PARTIAL, EMPTY, FAILED
    }

    public interface ExportCallback {
        /** 在 RheaTrace 内部导出线程回调，不会切换到主线程。 */
        void onCompleted(ExportResult result);
    }

    /** 线上常驻采集配置。 */
    public static final class OnlineTraceConfig {
        private final int bufferSizeBytes;
        private final long minSampleIntervalNs;
        private final long diskQuotaBytes;
        private final long artifactTtlMs;
        private final long maxArtifactBytes;
        private final boolean foregroundOnly;
        private final boolean enabled;
        private final boolean enableJniHook;
        private final boolean enableObjectAllocation;
        private final boolean enableWakeup;
        private final boolean enableRusage;
        private final String mappingId;

        private OnlineTraceConfig(Builder builder) {
            bufferSizeBytes = builder.bufferSizeBytes;
            minSampleIntervalNs = builder.minSampleIntervalNs;
            diskQuotaBytes = builder.diskQuotaBytes;
            artifactTtlMs = builder.artifactTtlMs;
            maxArtifactBytes = builder.maxArtifactBytes;
            foregroundOnly = builder.foregroundOnly;
            enabled = builder.enabled;
            enableJniHook = builder.enableJniHook;
            enableObjectAllocation = builder.enableObjectAllocation;
            enableWakeup = builder.enableWakeup;
            enableRusage = builder.enableRusage;
            mappingId = builder.mappingId;
        }

        public int getBufferSizeBytes() { return bufferSizeBytes; }
        public long getMinSampleIntervalNs() { return minSampleIntervalNs; }
        public long getDiskQuotaBytes() { return diskQuotaBytes; }
        public long getArtifactTtlMs() { return artifactTtlMs; }
        public long getMaxArtifactBytes() { return maxArtifactBytes; }
        public boolean isForegroundOnly() { return foregroundOnly; }
        public boolean isEnabled() { return enabled; }
        public boolean isEnableJniHook() { return enableJniHook; }
        public boolean isEnableObjectAllocation() { return enableObjectAllocation; }
        public boolean isEnableWakeup() { return enableWakeup; }
        public boolean isEnableRusage() { return enableRusage; }
        public String getMappingId() { return mappingId; }

        public static Builder builder() { return new Builder(); }

        public static final class Builder {
            private int bufferSizeBytes = 5 * 1024 * 1024;
            private long minSampleIntervalNs = 10_000_000L;
            private long diskQuotaBytes = 20L * 1024L * 1024L;
            private long artifactTtlMs = 3L * 24L * 60L * 60L * 1000L;
            private long maxArtifactBytes = 10L * 1024L * 1024L;
            private boolean foregroundOnly = true;
            private boolean enabled = true;
            private boolean enableJniHook;
            private boolean enableObjectAllocation;
            private boolean enableWakeup;
            private boolean enableRusage;
            private String mappingId = "";

            public Builder setBufferSizeBytes(int value) {
                bufferSizeBytes = value;
                return this;
            }

            public Builder setMinSampleIntervalMs(long value) {
                minSampleIntervalNs = value * 1_000_000L;
                return this;
            }

            public Builder setDiskQuotaBytes(long value) {
                diskQuotaBytes = value;
                return this;
            }

            public Builder setArtifactTtlMs(long value) {
                artifactTtlMs = value;
                return this;
            }

            public Builder setMaxArtifactBytes(long value) {
                maxArtifactBytes = value;
                return this;
            }

            public Builder setForegroundOnly(boolean value) {
                foregroundOnly = value;
                return this;
            }

            public Builder setEnabled(boolean value) {
                enabled = value;
                return this;
            }

            public Builder setEnableJniHook(boolean value) {
                enableJniHook = value;
                return this;
            }

            public Builder setEnableObjectAllocation(boolean value) {
                enableObjectAllocation = value;
                return this;
            }

            public Builder setEnableWakeup(boolean value) {
                enableWakeup = value;
                return this;
            }

            public Builder setEnableRusage(boolean value) {
                enableRusage = value;
                return this;
            }

            public Builder setMappingId(String value) {
                mappingId = value == null ? "" : value;
                return this;
            }

            public OnlineTraceConfig build() {
                if (bufferSizeBytes < 1024 * 1024 || bufferSizeBytes > 16 * 1024 * 1024) {
                    throw new IllegalArgumentException(
                            "bufferSizeBytes must be between 1MiB and 16MiB");
                }
                if (minSampleIntervalNs < 5_000_000L) {
                    throw new IllegalArgumentException(
                            "minSampleIntervalMs must be at least 5ms");
                }
                if (diskQuotaBytes < 1 || artifactTtlMs < 1 || maxArtifactBytes < 1
                        || maxArtifactBytes > diskQuotaBytes) {
                    throw new IllegalArgumentException("invalid online storage limits");
                }
                if (mappingId.length() > 128) {
                    throw new IllegalArgumentException("mappingId is too long");
                }
                return new OnlineTraceConfig(this);
            }
        }
    }

    /** 当前缓冲区可导出的 elapsed realtime 纳秒范围。 */
    public static final class BufferTimeRange {
        public static final BufferTimeRange EMPTY = new BufferTimeRange(0, 0, 0, 0);

        private final long startElapsedRealtimeNanos;
        private final long endElapsedRealtimeNanos;
        private final int recordCount;
        private final long overwrittenRecordCount;

        public BufferTimeRange(long startElapsedRealtimeNanos, long endElapsedRealtimeNanos,
                               int recordCount, long overwrittenRecordCount) {
            this.startElapsedRealtimeNanos = startElapsedRealtimeNanos;
            this.endElapsedRealtimeNanos = endElapsedRealtimeNanos;
            this.recordCount = recordCount;
            this.overwrittenRecordCount = overwrittenRecordCount;
        }

        public long getStartElapsedRealtimeNanos() { return startElapsedRealtimeNanos; }
        public long getEndElapsedRealtimeNanos() { return endElapsedRealtimeNanos; }
        public int getRecordCount() { return recordCount; }
        public long getOverwrittenRecordCount() { return overwrittenRecordCount; }

        public boolean isEmpty() {
            return recordCount == 0 || endElapsedRealtimeNanos <= startElapsedRealtimeNanos;
        }
    }

    public static final class ExportResult {
        private final ExportStatus status;
        private final File artifact;
        private final BufferTimeRange requestedRange;
        private final BufferTimeRange availableRange;
        private final BufferTimeRange actualRange;
        private final int recordCount;
        private final long overwrittenRecordCount;
        private final long droppedByRateLimit;
        private final String message;

        public ExportResult(ExportStatus status, File artifact,
                            BufferTimeRange requestedRange, BufferTimeRange availableRange,
                            BufferTimeRange actualRange, int recordCount,
                            long overwrittenRecordCount, long droppedByRateLimit, String message) {
            this.status = status;
            this.artifact = artifact;
            this.requestedRange = requestedRange;
            this.availableRange = availableRange;
            this.actualRange = actualRange;
            this.recordCount = recordCount;
            this.overwrittenRecordCount = overwrittenRecordCount;
            this.droppedByRateLimit = droppedByRateLimit;
            this.message = message == null ? "" : message;
        }

        public ExportStatus getStatus() { return status; }
        public boolean isSuccess() {
            return status == ExportStatus.SUCCESS || status == ExportStatus.PARTIAL;
        }
        public File getArtifact() { return artifact; }
        public BufferTimeRange getRequestedRange() { return requestedRange; }
        public BufferTimeRange getAvailableRange() { return availableRange; }
        public BufferTimeRange getActualRange() { return actualRange; }
        public int getRecordCount() { return recordCount; }
        public long getOverwrittenRecordCount() { return overwrittenRecordCount; }
        public long getDroppedByRateLimit() { return droppedByRateLimit; }
        public String getMessage() { return message; }
    }

    private RheaTrace3() {
    }

    /** 初始化现有调试采集模式。 */
    public static void init(Context context) {
        if (!ProcessUtils.isMainProcess(context)) {
            return;
        }
        TraceManager.getInstance().init(context);
    }

    /** 初始化低损耗线上常驻采集；线上模式与调试模式互斥。 */
    public static InitResult initOnline(Application application, OnlineTraceConfig config) {
        if (application == null || config == null) {
            return InitResult.INVALID_CONFIG;
        }
        if (!ProcessUtils.isMainProcess(application)) {
            return InitResult.NOT_MAIN_PROCESS;
        }
        if (android.os.Build.VERSION.SDK_INT < 26 || !android.os.Process.is64Bit()) {
            return InitResult.UNSUPPORTED_DEVICE;
        }
        return TraceManager.getInstance().initOnline(application, config);
    }

    public static BufferTimeRange getAvailableStackTimeRange() {
        return TraceManager.getInstance().getAvailableStackTimeRange();
    }

    public static ExportRequestResult exportStackData(
            long startElapsedRealtimeNanos, long endElapsedRealtimeNanos,
            ExportCallback callback) {
        return TraceManager.getInstance().exportStackData(
                startElapsedRealtimeNanos, endElapsedRealtimeNanos, callback);
    }

    public static ExportRequestResult exportAllStackData(ExportCallback callback) {
        return TraceManager.getInstance().exportAllStackData(callback);
    }

    public static void setOnlineTracingEnabled(boolean enabled) {
        TraceManager.getInstance().setOnlineTracingEnabled(enabled);
    }

    public static List<File> getPendingStackFiles() {
        List<File> files = TraceManager.getInstance().getPendingStackFiles();
        return files == null ? Collections.<File>emptyList() : files;
    }

    public static boolean deleteStackFile(File artifact) {
        return TraceManager.getInstance().deleteStackFile(artifact);
    }

    public static void stopOnlineTracing() {
        TraceManager.getInstance().stopOnlineTracing();
    }

    /** 同步抓取当前线程 Java 栈。线上模式下 force=true 仍受最低采样间隔限制。 */
    public static void captureStackTrace(boolean force) {
        TraceGlobal.capture(force);
    }
}
