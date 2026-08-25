# 线上堆栈缓冲与导出

> 适用对象：接入线上卡顿监控的 SDK 使用者，以及维护 Native 采集和服务端解析的贡献者。

## 正文

### 能力边界

线上模式只常驻采集和导出堆栈，不判断卡顿、不自动触发、不上传文件。业务方使用自己的监控逻辑记录 SystemClock.elapsedRealtimeNanos() 时间，随后选择范围导出或全量导出。

线上模式与调试采集模式互斥，首版要求 Android API 26 及以上、64 位 arm64 进程和主进程。线上采集仍由 Binder、GC、monitor、wait、park、Message、动态库加载或手工 captureStackTrace 等同步触发点驱动，不会从后台线程暂停主线程并定时遍历栈。因此，完全未经过触发点的纯计算卡顿可能没有采样证据。

### 初始化

~~~java
RheaTrace3.OnlineTraceConfig config = RheaTrace3.OnlineTraceConfig.builder()
        .setBufferSizeBytes(5 * 1024 * 1024)
        .setMinSampleIntervalMs(10)
        .setForegroundOnly(true)
        .build();

RheaTrace3.InitResult result = RheaTrace3.initOnline(application, config);
~~~

默认缓冲区为 5 MiB，可配置范围为 1～16 MiB；写满后覆盖最旧记录。对象分配、JNI trampoline、wakeup 和 rusage 默认关闭，可分别通过 Builder 显式开启。开启高频 Hook 前必须在目标 Android 版本和业务负载下重新评估 CPU、PSS 与主线程尾延迟。

### 时间范围与导出

时间基准固定为 elapsed realtime 纳秒，范围采用半开区间 [startNs, endNs)：

~~~java
RheaTrace3.BufferTimeRange available =
        RheaTrace3.getAvailableStackTimeRange();

RheaTrace3.ExportRequestResult request = RheaTrace3.exportStackData(
        startElapsedRealtimeNanos,
        endElapsedRealtimeNanos,
        result -> {
            if (result.isSuccess()) {
                upload(result.getArtifact());
            }
        });
~~~

范围与缓冲区只部分相交时，回调状态为 PARTIAL；完全不相交时同步返回 EMPTY_RANGE。结束时间晚于调用时刻返回 FUTURE_RANGE。导出任务使用调用时的 Native ticket 固定快照上界，执行期间的新记录不会进入本次产物。

导出全部当前有效记录：

~~~java
RheaTrace3.exportAllStackData(result -> {
    if (result.isSuccess()) {
        upload(result.getArtifact());
    }
});
~~~

导出在内部 Collector Worker 上执行，回调不切换主线程。同一时刻只允许一个任务，重复请求返回 BUSY。导出不会清空 RingBuffer，可以重复或重叠导出。

stopOnlineTracing 会等待已经进入队列的导出结束并停止后续写入。由于部分 ART/JNI Hook 不能在同一进程内安全撤销，Native 采集器和固定 RingBuffer 会保留到进程退出，避免 Hook 或并发手工 capture 访问已释放内存；因此 stop 以及 Native 启动失败都是当前进程内的终止状态。如需再次初始化或切换调试模式，应重启进程。

### 产物

产物目录为 noBackupFilesDir/rhea/stack，文件名形如：

~~~text
rhea-stack-<pid>-<snapshotTimeNs>.rheatrace.zip
~~~

ZIP 固定包含 manifest.json、sampling.bin 和 sampling-mapping.bin。manifest 记录请求、可用和实际时间范围、快照时间、记录数、覆盖数、限流丢弃数、采集配置和 SHA-256。默认单文件上限 10 MiB、目录配额 20 MiB，写入时先生成临时文件，校验后再原子重命名。

### 服务端解析

~~~powershell
java -jar rhea-trace-processor.jar analyze-stack `
  --input rhea-stack.rheatrace.zip `
  --output report.json `
  --html report.html `
  --mapping mapping.txt `
  --trace report.pb
~~~

report.json 同时包含按时间排列的 segments 和按公共前缀合并的 callTree。report.html 默认展示不依赖网络资源的“聚合火焰图 + 估算耗时”：相同公共前缀只出现一个方法块，纵向表示调用深度，横向使用估算区间并集。页面可切换样本数、精确区间、PB 式采样时间轴、聚合调用树和时间堆栈明细；火焰图与时间轴支持按钮或 Ctrl+滚轮缩放、拖动平移，极小分支保持至少 2 px 的可见宽度。调用树分别列出估算总耗时、估算自耗时和精确区间，其中估算自耗时仅表示没有归属到直接子节点的估算区间。

点采样估算规则固定为：从当前点延伸到下一条同线程记录，最多不超过 `2 × minSampleIntervalNs`；最后一点按一个采样间隔计算，所有区间裁剪到导出窗口。旧产物没有采样间隔时回退为 10 ms。时间轴按真实时间横向放置 root → leaf 调用层级，把相邻或重叠记录中路径相同的公共前缀合并成连续 slice；路径变化时只结束发生变化的分支，超过估算上限的空洞保持为空。尺下短刻度表示真实采样时刻，虚线块表示估算跨度，实线块才表示 duration Hook 的真实区间；两类证据不会相互合并。`estimatedDurationNs` 和 `estimatedSelfDurationNs` 只能用于采样归因，不能解释为精确方法耗时或 CPU 自耗时。

只有带真实开始和结束时间的 duration Hook 才进入 `exactDurationNs`。普通点采样的精确耗时仍显示为 `--`；页面新增的估算耗时始终明确标记为“估算”，不会覆盖精确字段。当前热路径没有记录 Dex PC；只有 mapping 或符号本身能够可靠提供位置时才显示文件和行号，否则显示 Unknown Source。

### app 模块端到端测试

`app` 提供设备测试和主机解析编排任务。普通 app 构建仍进入原有调试模式；只有显式传入 `online_trace_test=true` 时，测试构建才让示例 `Application` 初始化线上模式。测试会启动 `MainActivity`，在主线程产生采样，导出 `RANGE` 和 `ALL` 两个 ZIP，并在设备端校验 manifest、条目、文件大小和 SHA-256。随后 Gradle 任务使用 adb 拉取产物并调用 Processor 生成三类报告：

~~~powershell
.\gradlew.bat --no-daemon `
  -Ponline_trace_test=true `
  -Pdevice=设备序列号 `
  :app:parseOnlineStackFlow
~~~

输入 ZIP 位于 `app/build/online-stack-flow/input/`，解析结果分别位于 `app/build/online-stack-flow/range/` 和 `app/build/online-stack-flow/all/`，每个目录包含 `report.json`、`report.html` 和 `report.pb`。设备必须满足 API 26+、64 位进程，并已启用 adb；不支持设备会由 instrumentation 测试跳过。

## 相关源码

- [公开 API](../rhea-library/rhea-inhouse/src/main/java/com/bytedance/rheatrace/RheaTrace3.java)
- [导出管理](../rhea-library/rhea-inhouse/src/main/java/com/bytedance/rheatrace/TraceManager.java)
- [Native 双缓冲](../rhea-library/rhea-inhouse/src/main/cpp/base/PerfBuffer.h)
- [通用分析器](../rhea-tool/rhea-trace-processor/src/main/java/com/bytedance/rheatrace/stack/StackAnalyzer.java)

## 验证方式

1. 记录一次 Hook 区间和一次手工点采样，分别执行范围导出和全量导出。
2. 核对 manifest 的请求、可用、实际范围以及 PARTIAL 语义。
3. 使用 analyze-stack 同时生成 JSON、HTML 和可选 PB，确认点采样精确耗时为 --、估算字段有明确来源，并验证时间轴、缩放和平移。
4. 执行 `:rhea-inhouse:assembleDebug`、`:rhea-inhouse-noop:assembleDebug` 和 `:rhea-trace-processor:test`。
5. 在已连接的 API 26+ 64 位设备执行 `:app:parseOnlineStackFlow`，确认 RANGE/ALL 两条链路均生成 JSON、HTML 和 PB。
6. 在目标设备记录 API、ABI、CPU、PSS 和主线程 P95/P99；源码构建成功不能替代 ART 私有符号兼容性验证。

## 相关文档

- [App 端 SDK](app-sdk.md)
- [Native 实现](native-runtime.md)
- [CLI 处理器](cli-processor.md)
- [协议与数据格式](protocol-and-data-formats.md)
