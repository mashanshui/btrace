# 源码参考

> 适用对象：需要从行为快速定位到手写 Java/C++ 实现或评估改动影响面的贡献者。

## 正文

本页覆盖 `rhea-inhouse`、`rhea-inhouse-noop` 和 `rhea-trace-processor` 的全部手写 Java 类，以及 `rhea-inhouse/src/main/cpp` 的全部 Native 功能组件。表中的“上游/下游”表示主要调用方向，不代表完整调用图。

### App SDK：入口与运行期

| 类 | 职责 | 主要上游 → 下游/扩展点 |
| --- | --- | --- |
| [`RheaTrace3`](../rhea-library/rhea-inhouse/src/main/java/com/bytedance/rheatrace/RheaTrace3.java) | 唯一公开 API，初始化和手动抓栈 | Application → `TraceManager`/`TraceGlobal`；签名需与 noop 同步 |
| [`RheaTrace3` noop](../rhea-library/rhea-inhouse-noop/src/main/java/com/bytedance/rheatrace/RheaTrace3.java) | 公共 API 的空实现 | 业务调用 → 无副作用；公共签名兼容点 |
| [`TraceManager`](../rhea-library/rhea-inhouse/src/main/java/com/bytedance/rheatrace/TraceManager.java) | 管理 start/stop、token、异步 dump 和目录 | `RheaTrace3`/HTTP → Ability/HTTP 回调；新增 TraceMeta 的编排点 |
| [`TraceProperties`](../rhea-library/rhea-inhouse/src/main/java/com/bytedance/rheatrace/prop/TraceProperties.java) | 反射读取 Android 系统属性并回退默认值 | 配置创建器/HTTP → SystemProperties；新增端上开关入口 |
| [`HttpServer`](../rhea-library/rhea-inhouse/src/main/java/com/bytedance/rheatrace/server/HttpServer.java) | 随机端口服务、控制 action、查询和文件下载 | CLI HTTP → TraceManager/文件；协议扩展点 |
| [`ProcessUtils`](../rhea-library/rhea-inhouse/src/main/java/com/bytedance/rheatrace/utils/ProcessUtils.java) | 判断当前是否主进程 | `RheaTrace3` → ActivityManager |
| [`HandlerThreadUtils`](../rhea-library/rhea-inhouse/src/main/java/com/bytedance/rheatrace/utils/HandlerThreadUtils.java) | 懒创建 Collector HandlerThread/Handler | TraceManager → Android Looper |

### App SDK：Ability 和配置

| 类 | 职责 | 主要上游 → 下游/扩展点 |
| --- | --- | --- |
| [`TraceMeta`](../rhea-library/rhea-inhouse/src/main/java/com/bytedance/rheatrace/trace/base/TraceMeta.java) | 注册能力元数据、offset、类型和配置创建器 | TraceManager → AbilityCenter/Configurations；新增能力注册点 |
| [`TraceAbility`](../rhea-library/rhea-inhouse/src/main/java/com/bytedance/rheatrace/trace/base/TraceAbility.java) | activeCount、Collector 指针、JNI start/mark/dump/stop | TraceManager → JNI；能力公共基类 |
| [`TraceConfig`](../rhea-library/rhea-inhouse/src/main/java/com/bytedance/rheatrace/trace/base/TraceConfig.java) | 配置抽象，定义 deflate/update | ConfigCreator → JNI 数组；Java/Native 配置契约 |
| [`TraceConfigCreator`](../rhea-library/rhea-inhouse/src/main/java/com/bytedance/rheatrace/trace/base/TraceConfigCreator.java) | 创建和更新配置的接口 | TraceConfigurations → 具体配置创建器 |
| [`TraceGlobal`](../rhea-library/rhea-inhouse/src/main/java/com/bytedance/rheatrace/trace/base/TraceGlobal.java) | 加载 Native、一次性全局初始化、手动 capture | RheaTrace3/TraceManager → TraceGlobalJni |
| [`TraceAbilityCenter`](../rhea-library/rhea-inhouse/src/main/java/com/bytedance/rheatrace/trace/TraceAbilityCenter.java) | 反射创建并按 offset 缓存 Ability | TraceManager → 具体 Ability |
| [`TraceConfigurations`](../rhea-library/rhea-inhouse/src/main/java/com/bytedance/rheatrace/trace/TraceConfigurations.java) | 反射创建并按 offset 缓存/更新配置 | TraceAbility → ConfigCreator |
| [`SamplingTrace`](../rhea-library/rhea-inhouse/src/main/java/com/bytedance/rheatrace/trace/sampling/SamplingTrace.java) | Sampling Ability 的 meta 与额外 start 配置 | AbilityCenter → TraceAbility JNI |
| [`SamplingConfig`](../rhea-library/rhea-inhouse/src/main/java/com/bytedance/rheatrace/trace/sampling/SamplingConfig.java) | 定义容量、间隔、时钟和开关并压缩成 long 数组 | SamplingConfigCreator → Native SamplingConfig |
| [`SamplingConfigCreator`](../rhea-library/rhea-inhouse/src/main/java/com/bytedance/rheatrace/trace/sampling/SamplingConfigCreator.java) | 从系统属性生成/更新 SamplingConfig | TraceConfigurations → TraceProperties |
| [`JNIHook`](../rhea-library/rhea-inhouse/src/main/java/com/bytedance/rheatrace/trace/utils/JNIHook.java) | 为 Native JNI hook 提供 Java Method 占位与初始化入口 | TraceGlobal → Native JNIHook |

### CLI：入口、设备与基础设施

| 类 | 职责 | 主要上游 → 下游/扩展点 |
| --- | --- | --- |
| [`Main`](../rhea-tool/rhea-trace-processor/src/main/java/com/bytedance/rheatrace/Main.java) | CLI 入口和完整采集编排 | 用户命令 → ADB/SystemLevelCapture/Decoder |
| [`Adb`](../rhea-tool/rhea-trace-processor/src/main/java/com/bytedance/rheatrace/Adb.java) | 定位 adb、选择设备、执行命令和 HTTP 下载 | Main → OS Process/localhost HTTP |
| [`Log`](../rhea-tool/rhea-trace-processor/src/main/java/com/bytedance/rheatrace/Log.java) | 彩色/级别化控制台输出 | 所有 CLI 类 → stdout/stderr |
| [`Arguments`](../rhea-tool/rhea-trace-processor/src/main/java/com/bytedance/rheatrace/core/Arguments.java) | 解析 btrace 参数、生成工作目录和系统脚本参数 | Main → Workspace/OS/Adb |
| [`AdbProp`](../rhea-tool/rhea-trace-processor/src/main/java/com/bytedance/rheatrace/core/AdbProp.java) | 设置并清理采集系统属性 | Main → Adb shell setprop |
| [`Debug`](../rhea-tool/rhea-trace-processor/src/main/java/com/bytedance/rheatrace/core/Debug.java) | 解析 `-debug` 并控制详细日志 | Main/Log |
| [`SystemLevelCapture`](../rhea-tool/rhea-trace-processor/src/main/java/com/bytedance/rheatrace/core/SystemLevelCapture.java) | 系统采集 start/stop/process/cleanup 接口 | Main → PerfettoCapture/LiteCapture；新增模式接口 |
| [`TraceError`](../rhea-tool/rhea-trace-processor/src/main/java/com/bytedance/rheatrace/core/TraceError.java) | 携带错误消息、建议和 cause | CLI 各层 → Main 顶层处理 |
| [`Version`](../rhea-tool/rhea-trace-processor/src/main/java/com/bytedance/rheatrace/core/Version.java) | 从 jar manifest 读取版本 | Main |
| [`Workspace`](../rhea-tool/rhea-trace-processor/src/main/java/com/bytedance/rheatrace/core/Workspace.java) | 管理临时文件和最终输出路径 | Arguments/Capture/Decoder → 文件系统 |

### CLI：平台、采集和 Trace 模型

| 类 | 职责 | 主要上游 → 下游/扩展点 |
| --- | --- | --- |
| [`OS`](../rhea-tool/rhea-trace-processor/src/main/java/com/bytedance/rheatrace/os/OS.java) | 操作系统抽象：adb 名、脚本名、命令、端口和交互能力 | Adb/PerfettoCapture → MacOS/Windows |
| [`MacOS`](../rhea-tool/rhea-trace-processor/src/main/java/com/bytedance/rheatrace/os/MacOS.java) | macOS 命令和 SIGINT/可执行权限实现 | OS → shell/process |
| [`Windows`](../rhea-tool/rhea-trace-processor/src/main/java/com/bytedance/rheatrace/os/Windows.java) | Windows 命令、端口和非交互限制实现 | OS → cmd/process |
| [`PerfettoCapture`](../rhea-tool/rhea-trace-processor/src/main/java/com/bytedance/rheatrace/perfetto/PerfettoCapture.java) | 执行资源脚本、采集系统 Trace、拼接 App packet | Main → OS/Workspace/Decoder |
| [`LiteCapture`](../rhea-tool/rhea-trace-processor/src/main/java/com/bytedance/rheatrace/lite/LiteCapture.java) | simple 模式计时并仅输出 App Trace | Main → Decoder/Trace |
| [`Trace`](../rhea-tool/rhea-trace-processor/src/main/java/com/bytedance/rheatrace/perfetto/Trace.java) | 构造 descriptor、slice、counter、ProcessTree protobuf | Convertor → Perfetto 生成类型/OutputStream |

### CLI：采样解码与转换

| 类 | 职责 | 主要上游 → 下游/扩展点 |
| --- | --- | --- |
| [`SamplingTraceDecoder`](../rhea-tool/rhea-trace-processor/src/main/java/com/bytedance/rheatrace/trace/SamplingTraceDecoder.java) | 读取 sampling/mapping/extra，组织解码和 retrace | Capture → MappingDecoder/StackList/Convertor |
| [`SamplingMappingDecoder`](../rhea-tool/rhea-trace-processor/src/main/java/com/bytedance/rheatrace/trace/SamplingMappingDecoder.java) | 解码方法指针符号和线程名 | SamplingTraceDecoder → MethodSymbol |
| [`ProguardMappingDecoder`](../rhea-tool/rhea-trace-processor/src/main/java/com/bytedance/rheatrace/trace/ProguardMappingDecoder.java) | 解析 R8/ProGuard mapping 并还原方法签名 | SamplingTraceDecoder → MappingClass/Method 内部模型 |
| [`StackList`](../rhea-tool/rhea-trace-processor/src/main/java/com/bytedance/rheatrace/trace/StackList.java) | 按格式版本解码单条采样记录和堆栈 | SamplingTraceDecoder → MethodSymbol/CallNode |
| [`StackTraceConvertor`](../rhea-tool/rhea-trace-processor/src/main/java/com/bytedance/rheatrace/trace/StackTraceConvertor.java) | 按线程将采样记录转换为 slice/counter | SamplingTraceDecoder → Trace |
| [`CallNode`](../rhea-tool/rhea-trace-processor/src/main/java/com/bytedance/rheatrace/trace/CallNode.java) | 表示转换过程中的调用节点/嵌套关系 | StackTraceConvertor 内部 |
| [`MethodSymbol`](../rhea-tool/rhea-trace-processor/src/main/java/com/bytedance/rheatrace/trace/MethodSymbol.java) | 保存方法指针、偏移和符号文本 | MappingDecoder/StackList |
| [`ReservedMethodManager`](../rhea-tool/rhea-trace-processor/src/main/java/com/bytedance/rheatrace/trace/ReservedMethodManager.java) | 管理特殊保留方法/事件名称映射 | StackList/Convertor |
| [`ByteFormatter`](../rhea-tool/rhea-trace-processor/src/main/java/com/bytedance/rheatrace/trace/utils/ByteFormatter.java) | 按固定格式读取/展示字节数据的辅助逻辑 | Trace 解码工具层 |

### Native：公共基础设施与 JNI

| 组件 | 文件 | 职责与扩展点 |
| --- | --- | --- |
| Native 构建 | [`CMakeLists.txt`](../rhea-library/rhea-inhouse/src/main/cpp/CMakeLists.txt) | 汇总编译单元、C++17、ShadowHook 和系统库 |
| 加载入口 | [`RheaOnLoad.cpp`](../rhea-library/rhea-inhouse/src/main/cpp/RheaOnLoad.cpp) | JNI_OnLoad、VM 保存和 Native 注册 |
| 全局上下文 | [`RheaContext.cpp/.h`](../rhea-library/rhea-inhouse/src/main/cpp/RheaContext.cpp) | 保存跨组件上下文和初始化状态 |
| 全局 JNI | [`TraceGlobalJni.cpp`](../rhea-library/rhea-inhouse/src/main/cpp/TraceGlobalJni.cpp) | TraceGlobal init/capture Native 实现 |
| Ability JNI | [`TraceAbilityJni.cpp`](../rhea-library/rhea-inhouse/src/main/cpp/TraceAbilityJni.cpp) | Collector create/start/update/mark/dump/stop 分发 |
| Collector 接口 | [`PerfCollector.h`](../rhea-library/rhea-inhouse/src/main/cpp/base/PerfCollector.h) | Native Collector 生命周期接口 |
| Collector 模板 | [`PerfCollectorBaseImpl.h`](../rhea-library/rhea-inhouse/src/main/cpp/base/PerfCollectorBaseImpl.h) | 文件名、dump/dumpPart 和 Dumper 创建模板 |
| 缓存编排 | [`PerfBuffer.h`](../rhea-library/rhea-inhouse/src/main/cpp/base/PerfBuffer.h) | 主/备 RingBuffer、头部、mmap 和区间导出 |
| 环形缓存 | [`RingBuffer.h`](../rhea-library/rhea-inhouse/src/main/cpp/base/RingBuffer.h) | ticket、槽位覆盖、时间/有效区间查找 |
| 编码辅助 | [`common_write.h`](../rhea-library/rhea-inhouse/src/main/cpp/base/common_write.h) | POD 字段写入缓冲区 |

### Native：Sampling、统计与 Hook

| 组件 | 文件 | 职责与扩展点 |
| --- | --- | --- |
| Sampling Collector | [`sampling/SamplingCollector.cpp/.h`](../rhea-library/rhea-inhouse/src/main/cpp/sampling/SamplingCollector.cpp) | 限流、抓栈、统计、写入、dump 和 mapping |
| Sampling 配置 | [`sampling/SamplingConfig.cpp/.h`](../rhea-library/rhea-inhouse/src/main/cpp/sampling/SamplingConfig.cpp) | 解析 Java long 数组并更新采样间隔 |
| Sampling 记录 | [`sampling/SamplingRecord.h`](../rhea-library/rhea-inhouse/src/main/cpp/sampling/SamplingRecord.h) | 事件枚举、记录字段和变长编码 |
| 栈模型 | [`sampling/Stack.cpp/.h`](../rhea-library/rhea-inhouse/src/main/cpp/sampling/Stack.cpp) | 栈帧存储、编码和符号字符串 |
| 栈访问 | [`sampling/StackVisitor.cpp/.h`](../rhea-library/rhea-inhouse/src/main/cpp/sampling/StackVisitor.cpp) | ART 同步栈访问与初始化 |
| 分配统计 | [`stat/JavaObjectStat.cpp/.h`](../rhea-library/rhea-inhouse/src/main/cpp/stat/JavaObjectStat.cpp) | 当前线程对象数/字节统计 |
| 采样入口 | [`trace/SamplingTrace.cpp/.h`](../rhea-library/rhea-inhouse/src/main/cpp/trace/SamplingTrace.cpp) | 统一事件到 SamplingCollector request 的桥接 |
| Binder Hook | [`trace/TraceBinderCall.cpp/.h`](../rhea-library/rhea-inhouse/src/main/cpp/trace/TraceBinderCall.cpp) | Binder 调用观测 |
| GC Hook | [`trace/TraceGC.cpp/.h`](../rhea-library/rhea-inhouse/src/main/cpp/trace/TraceGC.cpp) | GC 事件/区间观测 |
| JNI 调用 Hook | [`trace/TraceJNICall.cpp/.h`](../rhea-library/rhea-inhouse/src/main/cpp/trace/TraceJNICall.cpp) | JNI trampoline 观测 |
| Monitor Hook | [`trace/TraceJavaMonitor.cpp/.h`](../rhea-library/rhea-inhouse/src/main/cpp/trace/TraceJavaMonitor.cpp) | Java monitor/锁观测 |
| 动态库 Hook | [`trace/TraceLoadLibrary.cpp/.h`](../rhea-library/rhea-inhouse/src/main/cpp/trace/TraceLoadLibrary.cpp) | dlopen/库加载观测 |
| 消息 ID Hook | [`trace/TraceMessageIDChange.cpp/.h`](../rhea-library/rhea-inhouse/src/main/cpp/trace/TraceMessageIDChange.cpp) | 消息边界与 messageIndex |
| wait/notify Hook | [`trace/TraceObjectWait.cpp/.h`](../rhea-library/rhea-inhouse/src/main/cpp/trace/TraceObjectWait.cpp) | Object.wait/notify 观测 |
| park/unpark Hook | [`trace/TraceUnsafePark.cpp/.h`](../rhea-library/rhea-inhouse/src/main/cpp/trace/TraceUnsafePark.cpp) | Unsafe park/unpark 观测 |
| Java 分配 Hook | [`trace/java_alloc/TraceJavaAlloc.cpp/.h`](../rhea-library/rhea-inhouse/src/main/cpp/trace/java_alloc/TraceJavaAlloc.cpp) | ART 分配路径 Hook 与统计入口 |
| 分配 checkpoint | [`trace/java_alloc/checkpoint.cpp/.h`](../rhea-library/rhea-inhouse/src/main/cpp/trace/java_alloc/checkpoint.cpp) | ART checkpoint 辅助 |
| ART 线程表 | [`trace/java_alloc/thread_list.cpp/.h`](../rhea-library/rhea-inhouse/src/main/cpp/trace/java_alloc/thread_list.cpp) | ART ThreadList 辅助 |
| 分配公共定义 | [`trace/java_alloc/java_alloc_common.h`](../rhea-library/rhea-inhouse/src/main/cpp/trace/java_alloc/java_alloc_common.h) | 分配 Hook 共享类型/声明 |

### Native：底层工具

| 组件 | 文件 | 职责 |
| --- | --- | --- |
| JNI Hook | [`utils/JNIHook.cpp/.h`](../rhea-library/rhea-inhouse/src/main/cpp/utils/JNIHook.cpp) | JNI function table Hook 与恢复 |
| 动态链接辅助 | [`utils/npth_dl.c/.h`](../rhea-library/rhea-inhouse/src/main/cpp/utils/npth_dl.c) | Native 动态符号查找辅助 |
| scoped dlopen | [`utils/scoped_dlopen.h`](../rhea-library/rhea-inhouse/src/main/cpp/utils/scoped_dlopen.h) | RAII 动态库句柄封装 |
| 时间工具 | [`utils/time.h`](../rhea-library/rhea-inhouse/src/main/cpp/utils/time.h) | boottime、CPU time 等时间读取 |
| 通用工具 | [`utils/misc.h`](../rhea-library/rhea-inhouse/src/main/cpp/utils/misc.h) | tid、线程等小型辅助 |
| 日志工具 | [`utils/log.h`](../rhea-library/rhea-inhouse/src/main/cpp/utils/log.h) | Debug/Release Native 日志宏 |

### Perfetto 生成代码边界

`rhea-trace-processor/src/main/java/perfetto/protos` 下文件头标记为 protobuf compiler 生成代码，当前声明 protobuf Java 4.29.3。项目手写代码主要直接使用：

- `TraceOuterClass.Trace`、`TracePacketOuterClass.TracePacket`；
- `TrackEventOuterClass`、`TrackDescriptorOuterClass`；
- `ProcessDescriptorOuterClass`、`ThreadDescriptorOuterClass`、`ProcessTreeOuterClass`；
- `CounterDescriptorOuterClass`、`DebugAnnotationOuterClass`；
- `FtraceEventOuterClass`、`FtraceEventBundleOuterClass`。

升级流程应从 Perfetto proto 源重新生成，而不是手改 Java 文件；同时保持 `protobuf-java` runtime 与生成代码版本兼容，并验证最终 Trace 可打开。

## 相关源码

- [Android Library 源码根](../rhea-library/rhea-inhouse/src/main/java/com/bytedance/rheatrace/RheaTrace3.java)
- [Native CMake 清单](../rhea-library/rhea-inhouse/src/main/cpp/CMakeLists.txt)
- [CLI 手写源码入口](../rhea-tool/rhea-trace-processor/src/main/java/com/bytedance/rheatrace/Main.java)
- [CLI 构建依赖](../rhea-tool/rhea-trace-processor/build.gradle)

## 验证方式

1. 使用 `rg --files` 列出三个模块的手写 Java 和 Native 文件，与本页逐项比对。
2. 对任一表项沿“上游 → 下游”至少跟踪一次调用，发现偏差时先修正文档。
3. 升级 proto 或新增 Native 编译单元时，确认生成代码边界/CMake 表同步更新。

## 相关文档

- [总体架构](architecture.md)
- [App 端 SDK](app-sdk.md)
- [Native 实现](native-runtime.md)
- [CLI 处理器](cli-processor.md)
