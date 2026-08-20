# 配置参考

> 适用对象：配置构建、调整采集参数或核对公开参数行为的 SDK 使用者和维护者。

## 正文

### 构建配置

| 配置 | 当前值/默认值 | 作用 |
| --- | --- | --- |
| `enable_btrace` | `true`（本仓当前值） | 示例 App 在真实库与 noop 库间切换 |
| `POM_VERSION_NAME` | `3.0.0` | 三个发布模块的版本 |
| `compileSdk` / `targetSdk` | 30 / 30 | Android 编译与目标版本 |
| `minSdk` | 21 | Gradle 可编译下限，不等于公开运行支持承诺 |
| `ndkVersion` | 21.1.6352462 | Native 构建 NDK |
| Android Library ABI | `armeabi-v7a`, `arm64-v8a` | CMake 构建过滤；32 位运行支持仍待核实 |
| Java compatibility | 1.8 | Android 和 CLI 源码/目标兼容级别 |
| C++ 标准 | C++17 | `rhea-inhouse` Native 编译 |

`precise_instrumentation_enable` 在当前已检索源码中没有形成 btrace 3.0 主采集链路的配置入口，保留为仓库历史/外部构建配置，不在本文赋予额外语义。

### Android 系统属性

CLI 在采集前设置，shutdown hook 中清零：

| 属性 | CLI 来源 | App 默认/回退 | 作用 |
| --- | --- | --- | --- |
| `persist.traced.enable` | 固定设置为 `1` | 无 | 尝试启用 traced；teardown 当前不会还原 |
| `debug.rhea3.startWhenAppLaunch` | `-r` 或 `-w` 时为 `1` | 未设置为 false | Application 初始化时立即开始采集 |
| `debug.rhea3.waitTraceTimeout` | `-waitTraceTimeout` | 20 秒 | HTTP download 等待 dump ready 的秒数 |
| `debug.rhea3.methodIdMaxSize` | `-maxAppTraceBufferSize` | 200000 条 | Sampling RingBuffer 容量 |
| `debug.rhea3.sampleInterval` | `-sampleInterval` | 1000000 ns | 主线程和其他线程最小抓栈间隔 |

属性通过反射调用隐藏 API `android.os.SystemProperties.get`；读取异常会静默回退默认值。手工调试后可用 `adb shell setprop <key> 0` 清理 debug 属性。

### CLI 专用参数

| 参数 | 必需/默认 | 当前源码行为 |
| --- | --- | --- |
| `-a <package>` | 必需 | 目标包名，同时传给系统 Trace 脚本 |
| `-o <path>` | 默认自动生成 | 最终 `.pb` 路径；工作目录位于其同级 `rheatrace.workspace` |
| `-t <seconds>` | Windows 必需；其他支持中断键的平台可省略 | 定时采集；整数会重写成系统脚本所需的 `<n+1>s`，App 等待仍为原秒数 |
| `-s <serial>` | 多设备时必需 | 由 `Adb.init` 预扫描选择设备 |
| `-m <mapping>` | 可选 | ProGuard/R8 mapping；文件必须存在 |
| `-mode perfetto\|simple` | API >= 28 默认 perfetto，否则 simple | 强制系统采集模式 |
| `-r` | 可选 | force-stop 并重启 launcher，采集启动阶段 |
| `-w` | 可选 | 等待 App 由外部启动并依赖启动采集属性 |
| `-launcher <component>` | 自动解析 | 为 `-r` 指定 `package/Class` launcher component |
| `-maxAppTraceBufferSize <count>` | 200000 | 正整数时写入 App buffer 属性 |
| `-sampleInterval <ns>` | 1000000 | 正整数时写入采样间隔属性 |
| `-waitTraceTimeout <seconds>` | 20 | App dump ready 等待时间；解析处当前缺少值存在性检查 |
| `-port <port>` | 自动探测 9000～9099 | 本机 ADB forward 端口 |
| `-debug` | 关闭 | 输出更详细 CLI 日志/堆栈 |
| `-v` | 无 | 打印版本和简短 usage 后退出 |

### 传给 Perfetto 脚本的参数

除上述被过滤的 btrace 参数外，其余参数会传给打包的 `record_android_trace`。常用项包括 category（如 `sched`）、`-b`、`--list`、`--list-ftrace` 和 `-h`。没有显式 category 时解析器尝试追加 `sched`；没有 `-b` 时 PerfettoCapture 追加 `100mb`。

系统脚本支持项可能随资源版本和设备变化，应通过 `--list`/`-h` 以当前 jar 和设备输出为准。

### 参数组合建议

- 普通前台区间：`-a <package> -t 10 -o output.pb sched`。
- 冷启动：增加 `-r`；launcher 自动解析失败时增加 `-launcher`。
- 外部控制启动：使用 `-w`，并确保 App 随后确实启动主进程。
- 无 Perfetto 支持：使用 `-mode simple`。
- Release 包：增加 `-m mapping.txt`。
- 多设备：增加 `-s <serial>`。

不要组合 `-r` 与 `-w`。当前源码也没有经过专用解析的 `-mainThreadOnly`；公开 README 中该参数属于待核实项。

## 相关源码

- [gradle.properties](../gradle.properties)
- [根构建配置](../build.gradle)
- [Arguments](../rhea-tool/rhea-trace-processor/src/main/java/com/bytedance/rheatrace/core/Arguments.java)
- [TraceProperties](../rhea-library/rhea-inhouse/src/main/java/com/bytedance/rheatrace/prop/TraceProperties.java)

## 验证方式

1. 对每个专用参数从 `Arguments.Parser` 跟踪到实际消费者，避免只依据 README。
2. 采集期间执行 `adb shell getprop | findstr rhea3`，核对 CLI 参数到系统属性映射。
3. 分别在 Windows 与支持交互中断的平台验证省略 `-t` 的行为。
4. 修改默认值时同时运行 CLI、端上 debug query 和文档校验。

## 相关文档

- [快速开始](getting-started.md)
- [CLI 处理器](cli-processor.md)
- [协议与数据格式](protocol-and-data-formats.md)
- [排障指南](troubleshooting.md)
