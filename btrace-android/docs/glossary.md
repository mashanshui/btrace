# 术语表

> 适用对象：阅读 btrace Android 文档、源码、日志或 Perfetto 产物的所有人员。

## 正文

| 术语 | 本项目中的含义 |
| --- | --- |
| btrace | 跨平台性能追踪项目；本知识库仅覆盖 Android 子项目 |
| RheaTrace / rhea | Android 实现沿用的代码、包名、日志和文件命名 |
| `rhea-inhouse` | 包含真实 Java/Native 采集逻辑的 Android Library |
| `rhea-inhouse-noop` | 保持 `RheaTrace3` 签名但不采集的替代库 |
| `rhea-trace-processor` | PC 端 Java CLI，控制采集并生成 Perfetto Trace |
| Trace Ability | Java 层对一种可采集性能数据的生命周期抽象 |
| TraceMeta | Trace Ability 的注册元数据；当前只有 Sampling |
| Sampling | 通过多个同步触发点获取线程调用栈及归因数据的当前核心能力 |
| 同步抓栈 | 在目标线程当前执行路径中直接访问其调用栈，而非独立采样线程异步挂起目标线程 |
| Hook 点 | Binder、GC、JNI、monitor、wait/park、分配等触发抓栈或记录区间的位置 |
| Collector | Native 采集器；当前具体实现是 `SamplingCollector` |
| SamplingRecord | 一条 Native 采样记录，包含事件、时间、线程、统计和堆栈 |
| RingBuffer | 固定容量环形缓存；新记录会覆盖最旧槽位 |
| ticket/token | RingBuffer 递增位置标识；start/stop token 确定本次 dump 区间 |
| core 数据 | 可以携带 extra JSON 的 TraceMeta 数据；当前 Sampling 是 core |
| rusage | 每线程资源统计，本项目使用 major fault 和自愿/非自愿上下文切换等字段 |
| shadow pause | dump/采集期间的暂停协调配置，具体语义由 Native 实现控制 |
| `sampling` | App dump 的变长采样记录文件 |
| `sampling-mapping` | 方法指针到符号、线程 ID 到线程名的配套映射文件 |
| Workspace | CLI 临时目录 `rheatrace.workspace` 及其中间文件集合 |
| Perfetto 模式 | 同时执行系统 Perfetto 脚本并把 App packet 拼接到最终输出 |
| simple 模式 | 当前源码中只转换 App sampling、不包含系统 Trace 的模式 |
| TrackEvent | Perfetto protobuf 中承载 slice、counter 等轨道事件的结构 |
| slice | Perfetto 时间轨上的 begin/end 区间，表示方法或事件持续时间 |
| retrace | 使用 R8/ProGuard mapping 将混淆类/方法名还原 |
| ADB forward | 将 PC 本机 TCP 端口转发到设备内 App HTTP 服务端口 |
| 启动采集 | CLI 通过系统属性让 SDK 在 Application 初始化时立刻 start |
| noop 构建 | 集成空实现，业务调用保留但没有服务、Native 或采集开销的构建 |
| 待核实 | 公开说明与源码/构建配置不一致，尚需维护者确定产品口径 |

## 相关源码

- [TraceMeta](../rhea-library/rhea-inhouse/src/main/java/com/bytedance/rheatrace/trace/base/TraceMeta.java)
- [SamplingRecord](../rhea-library/rhea-inhouse/src/main/cpp/sampling/SamplingRecord.h)
- [Workspace](../rhea-tool/rhea-trace-processor/src/main/java/com/bytedance/rheatrace/core/Workspace.java)
- [Trace protobuf 构造](../rhea-tool/rhea-trace-processor/src/main/java/com/bytedance/rheatrace/perfetto/Trace.java)

## 验证方式

新增术语前先确认它在源码、日志、命令或公开说明中实际出现；同一概念存在多个历史名称时注明当前首选名称和兼容名称。

## 相关文档

- [知识库首页](README.md)
- [总体架构](architecture.md)
- [协议与数据格式](protocol-and-data-formats.md)
- [源码参考](source-reference.md)
