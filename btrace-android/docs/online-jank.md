# 在线卡顿堆栈采集

## 适用对象

SDK 接入方、线上性能平台开发者，以及需要评估 Native/处理器扩展点的贡献者。本页描述首版“事件驱动 + 主线程同步 Hook + 环形缓冲”的实现边界，不承诺后台网络上传由 SDK 完成。

## 正文

### 目标与边界

在线模式在进程启动后常驻 Hook，采样记录写入 Native 双 RingBuffer。记录达到容量后由新记录覆盖最旧记录；导出时通过备份缓冲切换，采集线程不需要停止。卡顿检测由业务或 JankStats 完成，SDK 只负责在回调中排队导出任务。导出完成后业务自行读取 ZIP 并上传。

首版只采集主线程同步 Hook，默认采样间隔 10ms、缓冲约 5MiB、前台限制开启、导出冷却 60s、设备端配额 20MiB。这里的“持续”表示 Hook 常驻并持续写入事件；纯 CPU 密集型 Java/Kotlin 循环若没有 Hook 或业务手动 `captureStackTrace`，可能没有证据，报告会标记为点采样不足。

### 接入示例

```java
RheaTrace3.OnlineConfig config = RheaTrace3.OnlineConfig.builder()
        .setBufferSizeBytes(5 * 1024 * 1024)
        .setMinSampleIntervalMs(10)
        .setPreRollMs(2000)
        .setDumpCooldownMs(60_000)
        .setDiskQuotaBytes(20 * 1024 * 1024)
        .setArtifactTtlMs(3L * 24 * 60 * 60 * 1000)
        .setForegroundOnly(true)
        .setMappingId("release-2026-08")
        .build();

RheaTrace3.OnlineInitResult result = RheaTrace3.initOnline(application, config);
```

`initOnline` 只能在主进程调用；Android 8.0 以下、配置非法、已启用旧式调试模式或 `setEnabled(false)` 时会返回对应状态。在线模式和调试 HTTP 模式在同一进程内互斥。若应用使用 `enable_btrace=false`，noop 版本会返回 `DISABLED`，不会写文件。

卡顿结束时使用单调时钟构造事件。事件 ID 只能包含 ASCII 字母、数字、点、下划线和连字符：

```java
RheaTrace3.JankEvent event = RheaTrace3.JankEvent
        .builder("scene_home_001", startNs, endNs)
        .setScene("home")
        .setReason("main-thread-heavy-jank")
        .build();

RheaTrace3.DumpRequestResult accepted = RheaTrace3.dumpJankTrace(event, result -> {
    if (result.getStatus() == RheaTrace3.DumpStatus.SUCCESS) {
        // 业务上传 result.getArtifact()，上传成功后可 deleteJankTrace。
    }
});
```

回调在采集线程上异步执行；调用方不得在卡顿检测回调中直接压缩、读盘或发网络，`DumpCallback` 中也应只把文件交给独立上传队列。`DumpRequestResult` 为 `ACCEPTED` 只表示任务入队，最终结果以 `DumpResult` 为准。应用重启后可以通过 `getPendingJankTraces()` 找到尚未上传的 ZIP。

### 产物与服务端处理

每个产物为 `<eventId>.rheajank.zip`，包含：

| 条目 | 内容 |
| --- | --- |
| `manifest.json` | schema、应用名、事件时间、mappingId、采样格式版本、缓冲配置、文件大小和 SHA-256 |
| `sampling.bin` | 现有 Sampling v5 小端编码，已按当前环形缓冲窗口截取 |
| `sampling-mapping.bin` | Native 地址到符号及线程名的映射 |

服务端可以直接执行：

```bash
java -jar rhea-trace-processor.jar analyze-jank \
  --input scene_home_001.rheajank.zip \
  --mapping mapping.txt \
  --output scene_home_001.json \
  --trace scene_home_001.pb
```

JSON 报告把耗时分为两类：`exactDurationNs` 只来自 Hook 的成对开始/结束记录，并在事件窗口内裁剪、合并重叠区间；`pointSampleCount` 只表示某个时刻观察到的栈，不能被乘以采样间隔冒充连续耗时。`warnings` 会提示空采样、只有点采样或设备端截断。`--trace` 是可选的 Perfetto protobuf 视图，不改变 JSON 的精确耗时口径。

### 性能与降级策略

- 线上采样在 Native 入口先做主线程、开关和硬间隔判断；不满足条件时不抓栈、不写盘。
- 在线配置关闭对象分配、rusage、唤醒等高成本附加统计，仅保留主线程栈和线程名。
- 导出在专用 HandlerThread 上执行；RingBuffer 切换保证写入线程不等待 ZIP/哈希计算。
- 进程处于后台时默认暂停 Native 在线写入并且不接受导出请求；回到前台后自动恢复。可通过 `setOnlineTracingEnabled(false)` 做远程开关。
- 冷却、单任务并发限制和 TTL/磁盘配额用于避免卡顿风暴放大 I/O。业务上传失败时保留 ZIP，成功后显式删除。

### 明确限制与待核实项

- 首版不是定时异步 `JavaThread` 抓栈器；纯计算循环没有同步 Hook 时无法保证覆盖。
- 预留窗口由当前 RingBuffer 容量决定，`preRollMs` 写入 manifest 作为请求值；若采样频率或卡顿时长超过容量，旧记录会被覆盖。
- 设备 ABI/API 限制沿用现有 Native 实现（当前源码要求 arm64、Android 8.0+）；构建配置中其他 ABI 的实际运行能力仍标记为“待核实”。
- 服务端鉴权、重试、压缩传输、对象存储和聚合接口不在本次 SDK 改动内，应由线上平台按 manifest schema 设计。

## 相关源码

- `rhea-library/rhea-inhouse/src/main/java/com/bytedance/rheatrace/RheaTrace3.java`：在线公共 API、配置和事件校验。
- `rhea-library/rhea-inhouse/src/main/java/com/bytedance/rheatrace/TraceManager.java`：模式互斥、生命周期、导出、ZIP、配额和回调。
- `rhea-library/rhea-inhouse/src/main/cpp/sampling/SamplingCollector.cpp`：主线程过滤、硬间隔和写入环形缓冲。
- `rhea-library/rhea-inhouse/src/main/cpp/base/PerfBuffer.h`：双缓冲切换、覆盖和 token 区间导出。
- `rhea-tool/rhea-trace-processor/src/main/java/com/bytedance/rheatrace/jank/JankMain.java`：`analyze-jank` 命令入口。
- `rhea-tool/rhea-trace-processor/src/main/java/com/bytedance/rheatrace/jank/JankAnalyzer.java`：精确耗时与点采样报告。

## 验证方式

```powershell
.\gradlew.bat :rhea-trace-processor:test
.\gradlew.bat :rhea-library:rhea-inhouse:assembleDebug
.\gradlew.bat :rhea-library:rhea-inhouse-noop:assembleDebug
```

设备验证应在主进程、Android 8.0+、arm64 环境中触发一次 ≥300ms 的主线程卡顿，检查回调 ZIP、manifest 校验、重启后的待上传列表和服务端 JSON 的 `exactDurationNs`/`pointSampleCount` 分离。

## 相关文档

- [快速开始](getting-started.md)
- [App 端 SDK](app-sdk.md)
- [Native 实现](native-runtime.md)
- [协议与数据格式](protocol-and-data-formats.md)
- [配置参考](configuration-reference.md)
- [CLI 处理器](cli-processor.md)
- [排障指南](troubleshooting.md)
