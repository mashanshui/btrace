# CLI 处理器

> 适用对象：运行采集命令、维护 ADB/Perfetto 控制流程或扩展 Trace 转换的 Java 贡献者。

## 正文

### 线上堆栈解析

`analyze-stack` 不连接设备，直接校验并解析 SDK 导出的 `.rheatrace.zip`，通过 `--output` 生成完整 JSON、默认同时生成同目录的 `report.call-tree.json` 聚合调用树 JSON，通过 `--html` 生成默认按估算耗时展示的聚合火焰图、通过 `--trace` 可选生成 Perfetto PB。可通过 `--call-tree-output` 指定调用树 JSON 路径。HTML 可切换样本数、估算耗时、精确区间、PB 式采样时间轴、调用树和时间明细；火焰图与时间轴支持 1～64 倍缩放和拖动平移。采样时间轴把相邻记录中未变化的公共调用前缀合并为连续横向 slice，仅在调用路径变化处形成子分支；时间空洞不会被强行连接。

点采样本身仍没有精确耗时。Processor 只为展示生成估算区间：当前点延伸到下一条同线程记录，最多为 manifest 中 `minSampleIntervalNs` 的两倍，末尾点按一个采样间隔计算，并裁剪到导出窗口。旧产物缺少采样间隔时回退为 10 ms。JSON 使用 `durationKind`、`estimateSource`、`estimatedDurationNs` 与原有 `exactDurationNs` 区分估算和精确证据。聚合调用树同时显示估算总耗时、未归属估算耗时和精确区间；未归属估算耗时不是 CPU 自耗时。完整命令见[线上堆栈缓冲与导出](online-stack.md)。

### 独立调用树 JSON

`report.call-tree.json` 的 `artifactType` 为 `RHEA_STACK_CALL_TREE`，只包含公共元数据、统计信息和 `threads[].callTree`，不包含完整报告中的 `segments`、`renderDefaults` 或 HTML 专用字段。所有线程分别聚合，同名方法不会跨线程合并。每个方法节点的 `estimatedDurationNs` 是估算区间并集后的估算总耗时，`estimatedSelfDurationNs` 是未归属到直接子节点的估算时间，不代表 CPU 自耗时；真实 duration Hook 仍通过 `exactDurationNs` 和 `selfDurationNs` 单独表达。

除 CLI 外，服务端或其他 Java 调用方可以只生成调用树 JSON 字符串：

~~~java
String callTreeJson = new StackAnalyzer().analyzeCallTree(request);
~~~

`analyze-stack` 在一次采样解码中同时生成完整 JSON 和调用树 JSON；独立 API 会跳过 Perfetto Trace 构建，但仍执行相同的格式校验、mapping retrace、时间裁剪和估算规则。

### 入口流程

`Main.main` 的执行顺序是：

1. 无参数或包含 `-v` 时打印版本和简短 usage；
2. 初始化 debug 开关、ADB 设备和业务参数；
3. 通过 `AdbProp.setup` 写入本次采集的 Android 系统属性；
4. 按 `-mode` 或设备 API 选择 `PerfettoCapture`/`LiteCapture`；
5. 启动系统 Trace 线程；
6. 根据 `-r`、`-w` 或普通模式启动/等待 App 采集；
7. 等待指定时长或交互输入；
8. 停止 App 与系统采集，下载 `sampling` 和 `sampling-mapping`；
9. 解析、解混淆、转换并写出最终 `.pb`；
10. finally 中清理 App 数据和 ADB forward，JVM shutdown hook 清理临时系统属性。

### 参数分层

参数同时供三个消费者使用：

- `Adb.init` 预扫描 `-s`，确定目标设备；
- `Arguments.Parser` 消费 btrace 专用参数；
- 剩余参数作为 `record_android_trace` 的 Perfetto/systrace 参数。

解析器从转发数组中剔除 `-m`、`-mode`、`-maxAppTraceBufferSize`、`-port`、`-sampleInterval`、`-waitTraceTimeout`、`-launcher`、`-debug`、`-r`、`-w`。`-a`、`-o`、`-t` 被保留或重写，供系统 Trace 脚本使用。没有显式 category 时自动添加 `sched`。

当前解析方式不是通用 CLI parser：未知参数通常会继续传给系统 Trace 脚本。新增 btrace 专用参数时必须同时更新第一次 switch 和第二次过滤列表，否则可能污染 Perfetto 命令。

### 模式选择

| 模式 | 选择规则 | 处理方式 |
| --- | --- | --- |
| `perfetto` | 显式指定，或设备 API >= 28 时默认 | 解压并执行随 jar 打包的 `record_android_trace`；停止后将系统 Trace 字节和 App protobuf packet 写入同一输出 |
| `simple` | 显式指定，或设备 API < 28 时默认 | 不采集系统轨道；只解析 App sampling 并序列化为 Perfetto Trace |

`LiteCapture.start` 仅创建一个定时线程，不运行 atrace。因此当前源码中的 simple 模式是“仅 App Trace”，不要沿用历史说明把它描述为仍包含系统 atrace。

Perfetto 模式在用户没有指定 `-b` 时自动使用 `100mb` buffer。`--list`、`--list-ftrace`、`-h` 等快速命令只运行系统脚本并提前结束，不进入 App 采集流程。

### ADB 与端口转发

`Adb` 从 `PATH` 寻找 adb；多设备且未指定 `-s` 会报错。CLI 从：

```text
/storage/emulated/0/Android/data/<package>/files/rhea-port
```

列出目录名并解析端上 HTTP 端口，然后执行：

```text
adb forward tcp:<pcPort> tcp:<appPort>
```

本地端口默认在 9000～9099 范围内探测，也可用 `-port` 固定。所有 HTTP 请求访问 `localhost:<pcPort>`；finally 中移除该 forward。

### 启动模式

- 普通模式：先发现端口并请求 `action=start`。
- `-r`：force-stop App，解析或使用 `-launcher` 指定启动 Activity，设置启动采集属性后重新启动；停止前才建立 forward。
- `-w`：不主动启动 App，也不发送 start；等待外部启动触发启动采集，停止前建立 forward。
- `-r` 和 `-w` 同时出现时，代码优先进入 `-r` 分支；不建议组合使用。

### 工作目录与转换

`Workspace` 在最终输出文件同级创建 `rheatrace.workspace`，每次初始化都会清空：

| 文件 | 来源/用途 |
| --- | --- |
| `systemTrace.trace` | Perfetto 脚本输出；simple 模式不需要 |
| `sampling.bin` | 从 App 的 `sampling` 下载 |
| `sampling-mapping.bin` | 从 App 的 `sampling-mapping` 下载 |
| `record_android_trace*` | 从 jar resource 解压的系统采集脚本 |

`SamplingTraceDecoder` 先读取方法/线程 mapping，可选通过 `ProguardMappingDecoder` retrace，再解析采样记录。`StackTraceConvertor` 按线程和调用关系生成 slice/counter packet。Perfetto 模式直接先复制系统 Trace 文件，再写入 App 侧 packet；simple 模式只写 App 侧 `Trace`。

### 失败与清理

CLI 捕获顶层异常并打印 `TraceError.prompt`，当前不会重新抛出以形成可靠的非零退出码。自动化调用方不能只看进程退出码，应同时检查输出文件存在、大小和日志中的 `Error:`。

`Workspace.init` 会清空已有 `rheatrace.workspace`。不要将需要保留的手工文件放在该目录。异常退出仍可能留下工作目录或系统属性；可按[排障指南](troubleshooting.md)手工清理。

## 相关源码

- [Main](../rhea-tool/rhea-trace-processor/src/main/java/com/bytedance/rheatrace/Main.java)
- [Arguments](../rhea-tool/rhea-trace-processor/src/main/java/com/bytedance/rheatrace/core/Arguments.java)
- [Adb](../rhea-tool/rhea-trace-processor/src/main/java/com/bytedance/rheatrace/Adb.java)
- [PerfettoCapture](../rhea-tool/rhea-trace-processor/src/main/java/com/bytedance/rheatrace/perfetto/PerfettoCapture.java)

## 验证方式

1. 在单设备和多设备环境分别验证 `-s` 选择行为。
2. 分别运行 `-mode perfetto` 与 `-mode simple`，检查工作目录和最终输出差异。
3. 使用 `-r`、`-w`、普通模式各采集一次，观察 start 请求与端口转发建立时机。
4. 构造非法 mapping、不可写输出目录和不存在包名，确认日志提示能够定位阶段。
5. 使用同时包含点采样和 duration Hook 的产物生成 HTML，核对估算区间使用虚线、精确区间使用实线，并验证缩放不会改变 JSON 中的统计值。

## 相关文档

- [快速开始](getting-started.md)
- [协议与数据格式](protocol-and-data-formats.md)
- [配置参考](configuration-reference.md)
- [排障指南](troubleshooting.md)
