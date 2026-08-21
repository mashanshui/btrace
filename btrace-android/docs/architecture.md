# 总体架构

> 适用对象：需要理解完整采集链路、定位跨模块问题或设计扩展的贡献者。

## 正文

### 模块关系

```mermaid
flowchart LR
    User[开发者 / 性能分析人员] --> CLI[rhea-trace-processor]
    CLI -->|adb shell / forward| Device[Android 设备]
    Device --> App[目标 App]
    App --> SDK[rhea-inhouse Java]
    App -. 正常构建替代 .-> Noop[rhea-inhouse-noop]
    SDK -->|JNI| Native[librheatrace.so]
    Native --> Buffer[Sampling RingBuffer]
    SDK --> HTTP[NanoHTTPD 随机端口]
    CLI -->|start / stop / query / download| HTTP
    CLI --> Sys[Perfetto 或 simple 系统采集]
    HTTP -->|sampling + mapping| CLI
    Buffer --> HTTP
    Sys --> Merge[Trace 解码与合并]
    CLI --> Merge
    Merge --> Output[Perfetto .pb]
```

`app` 只负责示例和测试，不参与发布时的处理链。`rhea-inhouse` 与 `rhea-inhouse-noop` 共享唯一公开类 `RheaTrace3`。`rhea-trace-processor` 是 Java 8 CLI，同时包含 Perfetto 生成的 protobuf Java 类型和按操作系统选择的辅助脚本资源。

### 控制时序

```mermaid
sequenceDiagram
    participant U as 用户
    participant C as CLI Main
    participant A as ADB
    participant H as App HttpServer
    participant M as TraceManager
    participant N as Native Collector
    participant S as 系统 Trace

    U->>C: 参数与采集命令
    C->>A: 设备检查、setprop
    opt -r 或 -w
        C->>A: 启动/等待 App
        M->>N: 启动阶段 start
    end
    C->>A: 读取 rhea-port 并 adb forward
    C->>S: start
    alt 非启动阶段采集
        C->>H: action=start
        H->>M: startTracing(true)
        M->>N: start + mark
    end
    C->>C: 等待 -t 或交互结束
    C->>H: action=stop
    H->>M: stopTracing()
    M->>N: mark + stop + dump token range
    N-->>H: sampling / sampling-mapping ready
    C->>S: stop
    C->>H: action=download
    H-->>C: 二进制文件
    C->>C: 解码、retrace、转 Perfetto、合并
    C-->>U: output.pb
    C->>H: action=clean
    C->>A: 移除 forward、还原临时属性
```

### 数据流

```mermaid
flowchart TD
    Hook[系统/ART Hook 与手动 capture] --> Stack[StackVisitor 同步抓栈]
    Stack --> Record[SamplingRecord + rusage/分配统计]
    Record --> Ring[主 RingBuffer / 备份 RingBuffer]
    Ring --> Dump[sampling 文件]
    Ring --> Map[sampling-mapping 文件]
    Dump --> Decoder[SamplingTraceDecoder]
    Map --> Decoder
    Proguard[ProGuard mapping，可选] --> Decoder
    Decoder --> Track[TrackEvent / Slice protobuf]
    System[systemTrace.trace] --> Merger[Trace.append]
    Track --> Merger
    Merger --> PB[最终 Perfetto protobuf]
```

### 在线卡顿链路

```mermaid
sequenceDiagram
    participant J as JankStats/业务检测器
    participant R as RheaTrace3
    participant M as TraceManager
    participant N as Native RingBuffer
    participant H as Collector HandlerThread
    participant U as 业务上传器
    participant P as 服务端处理器

    R->>M: initOnline(config)
    M->>N: 主线程 Hook 常驻采集
    N->>N: 满容量后覆盖旧 ticket
    J->>R: JankEvent(startNs,endNs)
    R->>M: dumpJankTrace(event)
    M->>H: 入队并 mark token
    H->>N: dumpTokenRange + extra
    N-->>H: sampling / sampling-mapping
    H->>H: 写 manifest、SHA-256、ZIP、配额清理
    H-->>R: DumpCallback(SUCCESS/FAILED)
    R-->>U: artifact 文件
    U->>P: 上传 ZIP + 外部 ProGuard mapping
    P->>P: analyze-jank / 生成 JSON 与可选 Perfetto PB
```

在线链路不经过调试 HTTP 或 ADB；SDK 只负责本地环形缓存和异步产物，网络重试、鉴权、存储及聚合由业务平台负责。

### 生命周期与边界

- `RheaTrace3.init` 仅主进程生效；多进程 App 的其他进程不会启动服务或采集。
- `RheaTrace3.initOnline` 同样只在主进程生效；它不启动 HTTP，前台/远程开关决定 Native 是否写入，导出在独立 HandlerThread 上完成。
- `TraceManager` 当前固定请求 `TraceMeta.Sampling`，抽象虽然支持扩展能力，但没有启用第二种 TraceMeta。
- start/stop 返回的 token 是 RingBuffer ticket 范围；dump 仅导出该区间，旧数据可能因容量不足被覆盖。
- App 数据和系统 Trace 独立开始/停止，CLI 最终以 protobuf 方式合并，因此异常退出可能只留下工作目录中的部分产物。
- HTTP 服务绑定随机可用端口，通过 App 外部文件目录暴露端口号，再由 ADB 转发到本机端口；它不是对外稳定的远程网络 API。

### 主要扩展点

1. 新增端上采集能力：扩展 `TraceMeta`、配置创建器、Java `TraceAbility`、JNI 创建分发和 Native `PerfCollector`。
2. 新增 Hook：在 Native `trace/` 中实现初始化/恢复，并接入 `trace::init` 生命周期。
3. 新增 CLI 输入或输出：扩展 `Arguments`、`Workspace`、下载流程和解码/转换流程。
4. 修改二进制格式：写入端和 Java 解码端必须同步升级版本并保持显式兼容策略。

## 相关源码

- [模块声明](../settings.gradle)
- [TraceManager](../rhea-library/rhea-inhouse/src/main/java/com/bytedance/rheatrace/TraceManager.java)
- [CLI Main](../rhea-tool/rhea-trace-processor/src/main/java/com/bytedance/rheatrace/Main.java)
- [Native CMake](../rhea-library/rhea-inhouse/src/main/cpp/CMakeLists.txt)

## 验证方式

1. 对照 `settings.gradle` 确认图中四个模块均存在。
2. 从 `Main.main` 跟踪一次 start、stop、download、process 调用，确认时序图没有跳过跨进程步骤。
3. 从 `SamplingCollector::request` 跟踪到 `StackTraceConvertor.convert`，确认数据流两端字段对应。

## 相关文档

- [App 端 SDK](app-sdk.md)
- [Native 实现](native-runtime.md)
- [CLI 处理器](cli-processor.md)
- [协议与数据格式](protocol-and-data-formats.md)
