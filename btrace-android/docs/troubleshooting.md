# 排障指南

> 适用对象：接入、连接、采集、下载、解码或展示阶段遇到问题的使用者与维护者。

## 正文

先判断失败发生在哪一层，不要从 Perfetto 展示问题直接跳到 Native Hook：

```mermaid
flowchart LR
    Build[构建/接入] --> Init[App 初始化]
    Init --> Connect[ADB/端口连接]
    Connect --> Capture[App/系统采集]
    Capture --> Download[停止/dump/下载]
    Download --> Decode[解码/retrace/合并]
    Decode --> View[Perfetto 展示]
```

### 构建与接入

**找不到 `RheaTrace3`**

- 检查真实库或 noop 是否至少依赖一个；
- 检查 `enable_btrace` 字符串值和依赖分支；
- 业务代码只导入 `com.bytedance.rheatrace.RheaTrace3`。

**Native 构建失败**

- 确认 SDK、NDK 21.1.6352462 和 CMake 可用；
- 清理时只删除明确模块的 `build/`/`.cxx/`，不要删除整个工作区；
- 检查 ShadowHook 依赖和 ABI 过滤。

**noop 构建仍在采集**

- 用 Gradle dependency report 确认没有其他传递依赖引入真实 AAR；
- 检查 APK 中是否仍包含 `librheatrace.so`。

### App 初始化

**没有 `rhea-port`**

- 确认 `RheaTrace3.init` 在 `attachBaseContext` 被调用；
- 确认当前是主进程，远程进程调用会直接返回；
- 检查 `RheaServer` logcat 是否有启动异常；
- 确认 App 外部文件目录可用且目标 App 已实际启动。

**启动即崩溃**

- 切到 noop 验证是否确由 btrace 引起；
- 收集 API、ABI、位数、ROM、tombstone 和 Native 符号；
- 优先检查 ART Hook/内部符号兼容性，不把 `minSdk` 当作运行支持保证。

### ADB 与连接

**`adb not found in PATH`**

- 将 Android SDK `platform-tools` 加入 `PATH`；
- 在同一终端先执行 `adb version`。

**多设备错误**

- 执行 `adb devices`，使用 `-s <serial>`；
- 离线/未授权设备也应先处理或断开。

**`server port not found`**

- 执行 `adb shell ls /storage/emulated/0/Android/data/<package>/files/rhea-port`；
- 确认包名正确、App 已启动主进程且外部存储路径可访问；
- 删除残留端口目录前先 force-stop App，重新启动让 Server 重建；
- 某些 ROM 限制 `/Android/data` shell 访问时，需要结合设备策略处理。

**本机端口占用**

- 使用 `-port <freePort>`；
- 执行 `adb forward --list` 检查残留，再只移除确认属于本次任务的 forward。

### 采集

**Windows 提示不支持交互模式**

- 显式传 `-t <seconds>`。当前 Windows 实现不支持通过回车结束。

**普通采集 HTTP 200 但没有数据**

- 查看 `RheaTrace:Manager` 是否报告重复 start、Native init 失败或无记录；
- HTTP start 当前忽略 `startTracing` 的布尔结果，200 只表示请求被处理；
- 确保目标代码在采集窗口执行，必要时加入手动 `captureStackTrace`。

**`-r` 没有抓到冷启动**

- 检查系统属性 `debug.rhea3.startWhenAppLaunch=1`；
- 用 `-launcher package/Class` 绕过 launcher 自动解析；
- 确认初始化足够早且调用发生在主进程。

**没有 sched/系统轨道**

- 确认使用 perfetto 模式且设备支持；
- 用 `--list`/`--list-ftrace` 查看设备能力；
- simple 模式按当前源码只输出 App Trace；
- 检查 `systemTrace.trace` 是否生成。

### dump 与下载

**`wait for trace ready timeout`**

- 增大 `-waitTraceTimeout`；
- 查看 Collector HandlerThread 是否仍在 dump；
- 检查内部目录创建、`mmap`/`ftruncate` 和 Native errno 日志；
- 大 buffer、符号解析和线程名 dump 都会增加停止耗时。

**`trace file not exists`**

- 检查 `TraceManager` 中各 Ability 的 dump result 日志；
- 确认请求名是固定的 `sampling` 或 `sampling-mapping`；
- 检查 `<filesDir>/rhea/tracing/<pid>` 是否有文件。

**buffer 覆盖**

- CLI 若显示 `end - start > capacity`，提高 `-maxAppTraceBufferSize`、缩短采集时间或增大 `-sampleInterval`；
- 增大 buffer 会增加 Native 内存和 dump 成本，需要真机评估。

### 解码与解混淆

**`sample trace file is empty` / buffer underflow**

- 检查下载文件大小是否至少包含 28 字节头；
- 保留原始工作目录，不要只保留最终 `.pb`；
- 核对端上 SDK 与 CLI 是否来自兼容版本。

**Release 方法名未还原**

- 使用与 APK 完全匹配的 R8/ProGuard `mapping.txt`；
- `-m` 不是 btrace 2.0 method mapping；
- 混淆文件不存在时 CLI 会在解析参数阶段失败。

### Perfetto 展示

**输出文件不存在但 CLI 退出码正常**

- 当前 Main 捕获异常后通常不会形成可靠非零退出码；
- 检查日志中的 `Error:`、输出文件存在性和非零大小。

**文件存在但无法打开**

- 分别尝试 `-mode simple`，区分系统 Trace 与 App protobuf 问题；
- 保存 `systemTrace.trace`、`sampling.bin`、`sampling-mapping.bin`；
- 核对 protobuf runtime 与生成代码版本，以及 writer/decoder 格式兼容。

### 手工清理

仅在确认目标设备、包名和端口后执行：

```powershell
adb forward --list
adb shell setprop debug.rhea3.startWhenAppLaunch 0
adb shell setprop debug.rhea3.waitTraceTimeout 0
adb shell setprop debug.rhea3.methodIdMaxSize 0
adb shell setprop debug.rhea3.sampleInterval 0
```

`persist.traced.enable` 当前 teardown 不还原。是否恢复应依据设备原始状态和团队调试规范决定，不要机械设置未知值。

## 相关源码

- [Main](../rhea-tool/rhea-trace-processor/src/main/java/com/bytedance/rheatrace/Main.java)
- [Adb](../rhea-tool/rhea-trace-processor/src/main/java/com/bytedance/rheatrace/Adb.java)
- [HttpServer](../rhea-library/rhea-inhouse/src/main/java/com/bytedance/rheatrace/server/HttpServer.java)
- [TraceManager](../rhea-library/rhea-inhouse/src/main/java/com/bytedance/rheatrace/TraceManager.java)

## 验证方式

1. 保留失败命令、完整 CLI 日志、logcat、设备 API/ABI 和 `rheatrace.workspace`。
2. 按流程图逐层确认输入与输出，不跨层猜测。
3. 修复后使用同一设备和命令复现，并增加相应测试或兼容样本。

## 相关文档

- [快速开始](getting-started.md)
- [配置参考](configuration-reference.md)
- [CLI 处理器](cli-processor.md)
- [协议与数据格式](protocol-and-data-formats.md)
