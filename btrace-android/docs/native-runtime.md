# Native 实现

> 适用对象：维护 JNI、ART Hook、同步抓栈、性能计数或二进制导出的 C/C++ 贡献者。

## 正文

### 加载与 JNI 边界

`RheaOnLoad.cpp` 在 `JNI_OnLoad` 中保存 JavaVM，并注册 `TraceGlobal`、`TraceAbility` 等 Native 方法。Java 侧主要边界如下：

| Java 调用 | Native 入口 | 作用 |
| --- | --- | --- |
| `TraceGlobal.nativeInit` | `TraceGlobalJni.cpp` | 初始化全局上下文、主线程和 JNI Hook |
| `TraceGlobal.nativeCapture` | `TraceGlobalJni.cpp` | 请求一次当前线程同步抓栈 |
| `TraceGlobal.nativeBeginStackTiming/nativeEndStackTiming` | `TraceGlobalJni.cpp` | 显式开启/结束抓栈性能统计；结束调用返回汇总日志字符串 |
| `TraceAbility.nativeCreate` | `TraceAbilityJni.cpp` | 按 TraceMeta offset 创建 Collector |
| `nativeStart/nativeStop` | `TraceAbilityJni.cpp` | 启停 Collector 和 Hook 生命周期 |
| `nativeMark` | `TraceAbilityJni.cpp` | 返回当前 RingBuffer ticket |
| `nativeDumpTokenRange` | `TraceAbilityJni.cpp` | 导出指定 ticket 区间 |

`RheaContext` 保存运行期共享状态。JNI 层使用裸 `jlong` 传递 Collector 指针，因此 Java 与 Native 的生命周期、类型 offset 和配置数组顺序必须严格一致。

### Collector 与缓存

```mermaid
flowchart TD
    Event[Hook / 手动 capture] --> Request[SamplingCollector::request]
    Request --> Visitor[StackVisitor::visitOnce]
    Visitor --> Record[SamplingRecord]
    Record --> Perf[PerfBuffer]
    Perf --> Major[主 RingBuffer]
    Perf -. dump 期间写入 .-> Backup[备份 RingBuffer]
    Major --> Dumper[SamplingDumper]
    Dumper --> Sampling[sampling]
    Dumper --> Mapping[sampling-mapping]
```

`SamplingCollector` 是当前唯一 Collector。首次创建时根据 `SamplingConfig.capacity` 分配 `PerfBuffer<SamplingRecord>`。每次 request 会先检查暂停状态和最小间隔，再同步访问当前线程栈；只有保存深度等于实际深度时才写入。

`SamplingRecord` 保存事件类型、线程 ID、消息 ID、开始/结束 wall time 与 CPU time、对象分配数量/字节、major fault、主动/被动上下文切换和堆栈。时间字段采用配置的 clock；默认配置为 boottime。

RingBuffer 使用递增 ticket 标识记录位置。容量用尽后槽位被覆盖；dump 根据 start/end token 导出仍可用区间。备份 buffer 用于 dump 等场景下继续接收写入，具体切换逻辑由 `PerfBuffer` 管理。

### 抓栈与采样点

- `StackVisitor` 负责访问 ART 线程栈并填充 `Stack`。
- `SamplingTrace` 提供不同事件的采集入口，最终统一调用 `SamplingCollector::request`。
- `force` 绕过 `lastJavaNano` 间隔判断；正常采样按主线程或其他线程间隔限流。
- `captureAtEnd` 事件同时记录开始/结束时间和 CPU time；瞬时事件只记录当前时间。
- `messageIndex` 与 `lastJavaNano` 是 thread-local，消息边界和采样限流互不跨线程。
- `beginStackTiming` 在统计锁内清空旧数据、递增 epoch 并激活全进程会话；`request` 只在会话激活时读取额外时钟并累计成功抓栈或限频浪费耗时。
- `endStackTiming` 通过 thread-local epoch 验证同线程配对，在锁内关闭会话并快照，在锁外排序和格式化，返回完整抓栈分布与限频统计。它不写入采样缓冲区，也不自动写 Logcat。
- 统计会话是全进程且不可重叠的；新 begin 会替换旧会话。Collector 启停或线上开关切换时递增 epoch 并清空会话，跨生命周期的 end 返回空字符串。

### Hook 组件

| 组件 | 观测目标 | 主要产出 |
| --- | --- | --- |
| `TraceBinderCall` | Binder 调用 | Binder 类型采样记录 |
| `TraceGC` | GC 与 GC 内部阶段 | GC 区间/事件 |
| `TraceJNICall` | JNI trampoline | JNI 调用采样记录 |
| `TraceJavaMonitor` | monitor 锁竞争 | Monitor/Mutex/Unlock 相关记录 |
| `TraceLoadLibrary` | 动态库加载 | LoadLibrary 记录 |
| `TraceMessageIDChange` | 主线程消息边界 | 消息 ID 递增及关联 |
| `TraceObjectWait` | Object.wait/notify | Wait/Notify 区间与唤醒关系 |
| `TraceUnsafePark` | park/unpark | Park/Unpark 区间与唤醒关系 |
| `TraceJavaAlloc` | Java 对象分配 | 分配计数与字节统计 |

Hook 的可用性依赖 Android/ART 版本、目标符号和 ShadowHook。初始化失败、符号缺失或签名变化应被视为设备兼容问题，而不是在文档中假设所有系统版本都支持相同观测项。

### dump 与错误码

`PerfCollectorBaseImpl` 创建 `<name>` 和可选的 `<name>-mapping` 文件，随后由 `PerfBuffer::dumpPart` 导出。文件头包含 magic、type、version、时间、记录数和 extra JSON 长度；采样记录由 `SamplingDumper` 变长编码，mapping 另行写入方法指针、符号和线程名。

常见非零结果来自创建目录/文件失败、`ftruncate`、`mmap` 或无 Dumper。JNI 将结果返回 Java，`TraceManager` 逐 Ability 记录日志；当前 `onTraceDumpFinished` 只对创建 dump 目录失败传递非零 code，单个 Ability dump 的非零结果不会改变最终回调 code，排障时必须同时查看 logcat 和下载结果。

### 安全修改规则

1. 修改 `SamplingRecord` 编码时同步更新 Java `StackList.decode` 和格式版本。
2. 修改配置数组时同步更新 Java `SamplingConfig.deflate` 与 C++ `SamplingConfig` 构造/更新顺序。
3. 新增 Hook 时保证初始化可失败、停止可恢复，并验证多个 Android API/ABI。
4. 处理 ART 内部结构时不要把单个系统镜像或符号名推广为普遍兼容性结论。
5. 涉及 signal、mmap、线程暂停或 JNI 引用的变更必须进行真机压力测试。

## 相关源码

- [Native CMake](../rhea-library/rhea-inhouse/src/main/cpp/CMakeLists.txt)
- [SamplingCollector](../rhea-library/rhea-inhouse/src/main/cpp/sampling/SamplingCollector.cpp)
- [RequestDiagnostics](../rhea-library/rhea-inhouse/src/main/cpp/sampling/RequestDiagnostics.h)
- [SamplingRecord](../rhea-library/rhea-inhouse/src/main/cpp/sampling/SamplingRecord.h)
- [PerfBuffer](../rhea-library/rhea-inhouse/src/main/cpp/base/PerfBuffer.h)

## 验证方式

1. 至少在 arm64 真机上运行启动采集和普通采集，记录 API/ABI。
2. 使用小 buffer 制造覆盖，确认 debug query 的 `end - start > capacity` 与 CLI 警告一致。
3. 对新增 Hook 分别验证命中、符号缺失、重复 start/stop 和 App 退出恢复路径。
4. 对格式变更保留旧样本，验证新 CLI 的兼容或明确拒绝行为。

## 相关文档

- [App 端 SDK](app-sdk.md)
- [协议与数据格式](protocol-and-data-formats.md)
- [源码参考](source-reference.md)
- [开发与发布](development-and-release.md)

### 请求分布诊断

`endStackTiming()` 在原有耗时汇总后追加 `request diagnostics`、按线程汇总和 10 ms 桶。
无需新增开关，仍需启用 `enableStackCaptureStats`，且每次 begin/end 必须配对。
返回值可能超过 Logcat 单条长度，请逐行打印或保存到文件；SDK 不自动打印。

- `request_count` 统计通过暂停、在线开关及线程过滤、进入限流判断的请求；`admitted_count` 表示放行。
- `walk_failed_count` 表示 `visitOnce` 失败或栈深度校验未通过；`success_count` 沿用写入路径完成口径，不保证导出 ZIP 保留全部样本，也不等于完整栈校验通过。
- `pending_count` 表示快照时尚未完成的请求。跨会话的迟到结果不污染新会话。
- `types` 使用 SamplingRecord.h 的 SamplingType 数字值，其中 9 为对象分配、10 为 JNI。
- `session_start_ns/session_end_ns` 和桶使用 BOOTTIME；`clock_id/interval_ns` 是实际限流时钟和该线程首次请求时的间隔。
- `first_request_ms/last_request_ms` 相对于会话开始；`max_request_gap_ms` 不包含首尾空白，尾部单列 `tail_gap_ms`。
- `initial_gate_age_ms` 为首个请求时距离此前放行的时间，使用限流时钟；没有前次放行时为 N/A，并非精确的会话起点年龄。
- 空桶也输出。最多记录 64 个线程及每线程前 10 秒的桶，`omitted_count` 非零表示诊断不完整；超时后的已跟踪线程仍累计汇总。

连续空桶说明缺乏 Hook 请求；持续有请求且失败多说明抓栈失败；持续有请求但放行少应检查实际间隔、时钟与 force 行为。
10 ms 是事件触发采样的最小间隔，桶边界不是限流边界。诊断会增加锁、计时与内存开销，不宜作为无扰动性能基准。
`RequestDiagnostics.h` 在统计锁内记录请求入口和完成状态，以 BOOTTIME 按线程分 10 ms 桶。
入口记数、出口补记结果；结束快照中的未完成请求显示为 pending，迟到结果通过 epoch 校验丢弃。
原有成功耗时、限流汇总保留；分桶上限不影响原汇总。已移除逐次抓栈调试日志。
字段、上限和判断方法见 [SDK 请求分布诊断](app-sdk.md#请求分布诊断)。

2026-09-08：诊断主体实现的 Debug/Release 双 ABI（arm64-v8a、armeabi-v7a）构建和 JVM 测试通过；随后补充分桶上限不影响原总计并恢复原有栈完整性拒绝判断，完整 Gradle 构建需在正常构建环境重跑。
新增 RequestDiagnosticsTest 在 Pixel 4 XL、API 33、arm64-v8a 上通过，覆盖空桶、失败、线程隔离、pending 和上限。
该测试直接调用分桶逻辑，未验证 ART Hook 到 Java 统计输出的完整链路，未重采原卡顿事件。
