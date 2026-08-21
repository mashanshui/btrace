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

import android.content.Context;

import com.bytedance.rheatrace.trace.base.TraceGlobal;
import com.bytedance.rheatrace.utils.ProcessUtils;

import java.io.File;
import java.util.Collections;
import java.util.List;

/**
 * This is the only API class exposed externally by RheaTrace. Users are not expected to rely on
 * other classes beyond this one.
 * <p>
 * Note: RheaTrace only support main process tracing.
 */
public class RheaTrace3 {

    public enum OnlineInitResult {
        STARTED,
        ALREADY_STARTED,
        MODE_CONFLICT,
        NOT_MAIN_PROCESS,
        UNSUPPORTED_DEVICE,
        INVALID_CONFIG,
        NATIVE_INIT_FAILED,
        DISABLED
    }

    public enum DumpRequestResult {
        ACCEPTED,
        NOT_INITIALIZED,
        DISABLED,
        BACKGROUND,
        COOLDOWN,
        BUSY,
        INVALID_EVENT
    }

    public enum DumpStatus {
        SUCCESS,
        FAILED
    }

    public interface DumpCallback {
        void onComplete(DumpResult result);
    }

    /** Configuration for the low-overhead, event-driven online collector. */
    public static final class OnlineConfig {
        private final int bufferSizeBytes;
        private final long minSampleIntervalNs;
        private final long preRollMs;
        private final long dumpCooldownMs;
        private final long diskQuotaBytes;
        private final long artifactTtlMs;
        private final boolean foregroundOnly;
        private final boolean enabled;
        private final String mappingId;

        private OnlineConfig(Builder builder) {
            bufferSizeBytes = builder.bufferSizeBytes;
            minSampleIntervalNs = builder.minSampleIntervalNs;
            preRollMs = builder.preRollMs;
            dumpCooldownMs = builder.dumpCooldownMs;
            diskQuotaBytes = builder.diskQuotaBytes;
            artifactTtlMs = builder.artifactTtlMs;
            foregroundOnly = builder.foregroundOnly;
            enabled = builder.enabled;
            mappingId = builder.mappingId;
        }

        public int getBufferSizeBytes() {
            return bufferSizeBytes;
        }

        public long getMinSampleIntervalNs() {
            return minSampleIntervalNs;
        }

        public long getPreRollMs() {
            return preRollMs;
        }

        public long getDumpCooldownMs() {
            return dumpCooldownMs;
        }

        public long getDiskQuotaBytes() {
            return diskQuotaBytes;
        }

        public long getArtifactTtlMs() {
            return artifactTtlMs;
        }

        public boolean isForegroundOnly() {
            return foregroundOnly;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public String getMappingId() {
            return mappingId;
        }

        public static Builder builder() {
            return new Builder();
        }

        public static final class Builder {
            private int bufferSizeBytes = 5 * 1024 * 1024;
            private long minSampleIntervalNs = 10_000_000L;
            private long preRollMs = 2_000L;
            private long dumpCooldownMs = 60_000L;
            private long diskQuotaBytes = 20L * 1024L * 1024L;
            private long artifactTtlMs = 3L * 24L * 60L * 60L * 1000L;
            private boolean foregroundOnly = true;
            private boolean enabled = true;
            private String mappingId = "";

            public Builder setBufferSizeBytes(int value) {
                bufferSizeBytes = value;
                return this;
            }

            public Builder setMinSampleIntervalMs(long value) {
                minSampleIntervalNs = value * 1_000_000L;
                return this;
            }

            public Builder setPreRollMs(long value) {
                preRollMs = value;
                return this;
            }

            public Builder setDumpCooldownMs(long value) {
                dumpCooldownMs = value;
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

            public Builder setForegroundOnly(boolean value) {
                foregroundOnly = value;
                return this;
            }

            public Builder setEnabled(boolean value) {
                enabled = value;
                return this;
            }

            public Builder setMappingId(String value) {
                mappingId = value == null ? "" : value;
                return this;
            }

            public OnlineConfig build() {
                if (bufferSizeBytes < 1 * 1024 * 1024 || bufferSizeBytes > 16 * 1024 * 1024) {
                    throw new IllegalArgumentException("bufferSizeBytes must be between 1MiB and 16MiB");
                }
                if (minSampleIntervalNs < 5_000_000L) {
                    throw new IllegalArgumentException("minSampleIntervalMs must be at least 5ms");
                }
                if (preRollMs < 0 || dumpCooldownMs < 0 || diskQuotaBytes < 1 || artifactTtlMs < 1) {
                    throw new IllegalArgumentException("online timing and storage values must be positive");
                }
                if (mappingId.length() > 128) {
                    throw new IllegalArgumentException("mappingId is too long");
                }
                return new OnlineConfig(this);
            }
        }
    }

    public static final class JankEvent {
        private final String eventId;
        private final long startElapsedRealtimeNanos;
        private final long endElapsedRealtimeNanos;
        private final String scene;
        private final String reason;

        private JankEvent(Builder builder) {
            eventId = builder.eventId;
            startElapsedRealtimeNanos = builder.startElapsedRealtimeNanos;
            endElapsedRealtimeNanos = builder.endElapsedRealtimeNanos;
            scene = builder.scene;
            reason = builder.reason;
        }

        public String getEventId() {
            return eventId;
        }

        public long getStartElapsedRealtimeNanos() {
            return startElapsedRealtimeNanos;
        }

        public long getEndElapsedRealtimeNanos() {
            return endElapsedRealtimeNanos;
        }

        public String getScene() {
            return scene;
        }

        public String getReason() {
            return reason;
        }

        public static Builder builder(String eventId, long startElapsedRealtimeNanos,
                                      long endElapsedRealtimeNanos) {
            return new Builder(eventId, startElapsedRealtimeNanos, endElapsedRealtimeNanos);
        }

        public static final class Builder {
            private final String eventId;
            private final long startElapsedRealtimeNanos;
            private final long endElapsedRealtimeNanos;
            private String scene = "";
            private String reason = "";

            private Builder(String eventId, long startElapsedRealtimeNanos, long endElapsedRealtimeNanos) {
                if (eventId == null || !eventId.matches("[A-Za-z0-9._-]{1,64}")) {
                    throw new IllegalArgumentException("eventId must match [A-Za-z0-9._-]{1,64}");
                }
                if (startElapsedRealtimeNanos < 0 || endElapsedRealtimeNanos < startElapsedRealtimeNanos) {
                    throw new IllegalArgumentException("invalid event time range");
                }
                this.eventId = eventId;
                this.startElapsedRealtimeNanos = startElapsedRealtimeNanos;
                this.endElapsedRealtimeNanos = endElapsedRealtimeNanos;
            }

            public Builder setScene(String value) {
                scene = value == null ? "" : value;
                return this;
            }

            public Builder setReason(String value) {
                reason = value == null ? "" : value;
                return this;
            }

            public JankEvent build() {
                if (scene.length() > 128 || reason.length() > 128) {
                    throw new IllegalArgumentException("scene and reason must be at most 128 characters");
                }
                return new JankEvent(this);
            }
        }
    }

    public static final class DumpResult {
        private final DumpStatus status;
        private final String eventId;
        private final File artifact;
        private final String message;

        public DumpResult(DumpStatus status, String eventId, File artifact, String message) {
            this.status = status;
            this.eventId = eventId;
            this.artifact = artifact;
            this.message = message == null ? "" : message;
        }

        public DumpStatus getStatus() {
            return status;
        }

        public String getEventId() {
            return eventId;
        }

        public File getArtifact() {
            return artifact;
        }

        public String getMessage() {
            return message;
        }
    }

    /**
     * This is the entry point api for RheaTrace. The initialization stage includes two aspects:
     * 1. If we are tracing app cold launch stage, we will start tracing immediately.
     * 2. Start a http server for receiving latter tracing commands, such as start/stop tracing.
     *
     * @param context used for initializing tracing data directory
     */
    public static void init(Context context) {
        // RheaTrace now only support trace main process.
        if (!ProcessUtils.isMainProcess(context)) {
            return;
        }
        TraceManager.getInstance().init(context);
    }

    public static OnlineInitResult initOnline(Context context, OnlineConfig config) {
        if (context == null || config == null) {
            return OnlineInitResult.INVALID_CONFIG;
        }
        if (!ProcessUtils.isMainProcess(context)) {
            return OnlineInitResult.NOT_MAIN_PROCESS;
        }
        if (android.os.Build.VERSION.SDK_INT < 26) {
            return OnlineInitResult.UNSUPPORTED_DEVICE;
        }
        return TraceManager.getInstance().initOnline(context, config);
    }

    public static void setOnlineTracingEnabled(boolean enabled) {
        TraceManager.getInstance().setOnlineTracingEnabled(enabled);
    }

    public static DumpRequestResult dumpJankTrace(JankEvent event, DumpCallback callback) {
        if (event == null) {
            return DumpRequestResult.INVALID_EVENT;
        }
        return TraceManager.getInstance().dumpJankTrace(event, callback);
    }

    public static List<File> getPendingJankTraces() {
        List<File> files = TraceManager.getInstance().getPendingJankTraces();
        return files == null ? Collections.<File>emptyList() : files;
    }

    public static boolean deleteJankTrace(File artifact) {
        return TraceManager.getInstance().deleteJankTrace(artifact);
    }

    public static void stopOnlineTracing() {
        TraceManager.getInstance().stopOnlineTracing();
    }

    /**
     * Capture current stacktrace manually and save into RheaTrace sampling buffer for latter
     * transforming to trace data.
     * <p>
     * RheaTrace3 is a stacktrace based tracing tool. Inside of it, we've built in some hook points
     * for active stack capturing. However, these hook points can't cover the execution of all
     * methods. So, we offer this method to users. Users can decide to invoke it within appropriate
     * methods to make up for the methods that our hook points fail to cover.
     *
     * @param force if true, we will ignore the stacktrace capture interval limit and force capture a stacktrace.
     */
    public static void captureStackTrace(boolean force) {
        TraceGlobal.capture(force);
    }
}
