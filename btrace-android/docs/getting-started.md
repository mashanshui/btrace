# 快速开始

> 适用对象：第一次在 Android 应用中接入或运行 btrace 3.0 的开发者。

## 正文

### 1. 准备环境

- JDK 8：Android 模块和 CLI 的源码/目标兼容级别均为 Java 8。
- Android SDK：项目当前 `compileSdk`/`targetSdk` 为 30。
- Android NDK 21.1.6352462 与 CMake：构建 `rhea-inhouse` 的 `librheatrace.so`。
- ADB：必须能从 `PATH` 找到，并能通过 `adb devices` 识别目标设备。
- 设备上的目标 App：必须集成真实 `rhea-inhouse`，且运行主进程。

公开支持口径是 Android 8.0 及以上、64 位设备和 64 位应用。Gradle 仍声明 `minSdk 21` 且配置了 `armeabi-v7a`；两者属于[待核实差异](README.md#已知待核实项)，不要仅凭构建成功推断运行支持。

### 2. 选择真实库或 noop 库

项目示例通过 `gradle.properties` 中的开关选择实现：

```properties
enable_btrace=true
```

```groovy
if (enable_btrace == 'true') {
    implementation project(':rhea-inhouse')
} else {
    implementation project(':rhea-inhouse-noop')
}
```

业务项目使用发布制品时沿用相同思路：Trace 构建依赖 `rhea-inhouse`，普通构建依赖 `rhea-inhouse-noop`。两个制品都暴露 `RheaTrace3`，因此业务初始化代码不需要条件编译。

### 3. 尽早初始化

在 Application 的 `attachBaseContext` 中初始化：

```kotlin
override fun attachBaseContext(base: Context?) {
    super.attachBaseContext(base)
    RheaTrace3.init(base)
}
```

`RheaTrace3.init` 只在主进程执行。它会根据系统属性决定是否立即开始启动阶段采集，并异步启动一个随机端口的 HTTP 服务。端口号写入外部文件目录下的 `rhea-port/<port>`，供 PC 端发现。

需要补充同步采样点时，可以在合适的方法中调用：

```kotlin
RheaTrace3.captureStackTrace(false)
```

传入 `true` 会忽略最小采样间隔并强制尝试抓栈；调用成功仍依赖 Native Collector 已启动且未暂停。

线上卡顿采集不要启动调试 HTTP 服务，改用 `RheaTrace3.initOnline` 和异步 `dumpJankTrace`；完整接入代码、ZIP 上传和 `analyze-jank` 命令见[在线卡顿采集](online-jank.md)。

### 4. 构建示例与 CLI

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat :rhea-trace-processor:build
```

CLI fat jar 位于 `rhea-tool/rhea-trace-processor/build/libs/`。实际文件名由 Gradle 的项目名和版本决定，可先列出该目录确认。

### 5. 安装并检查设备

```powershell
adb devices
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

若同时连接多台设备，采集命令必须使用 `-s <serial>`。目标 App 必须至少启动一次，使端上初始化完成并创建端口目录；使用 `-r` 时 CLI 会负责 force-stop 和重新启动 launcher Activity。

### 6. 采集 Trace

Windows 上建议显式指定采集时长：

```powershell
java -jar rhea-trace-processor-3.0.0.jar -a rhea.sample.android -t 10 -o output.pb -r sched
```

常用变化：

```powershell
# 指定设备
java -jar rhea-trace-processor-3.0.0.jar -s SERIAL -a rhea.sample.android -t 10 -o output.pb sched

# Release 包解混淆
java -jar rhea-trace-processor-3.0.0.jar -a your.package -t 10 -m mapping.txt -o output.pb sched

# 仅采集 App 数据，绕过 Perfetto 系统采集
java -jar rhea-trace-processor-3.0.0.jar -a your.package -t 10 -mode simple -o output.pb
```

CLI 会设置临时系统属性、建立 ADB 端口转发、控制 App 采集、下载数据、解析堆栈并输出 Perfetto Trace。退出时会清理端口转发、App 临时文件和临时系统属性。

### 7. 查看结果

使用 [Perfetto UI](https://ui.perfetto.dev/) 打开 `output.pb`。重点检查：

- 目标进程和线程名称是否存在；
- 调用栈 slice 是否覆盖预期时间段；
- `sched` 等系统轨道是否存在；
- Release 包的方法名是否已通过 `-m` 还原；
- CLI 是否报告 App 采样 RingBuffer 覆盖。

## 相关源码

- [示例 Application](../app/src/main/java/rhea/sample/android/app/App.kt)
- [真实公共 API](../rhea-library/rhea-inhouse/src/main/java/com/bytedance/rheatrace/RheaTrace3.java)
- [noop 公共 API](../rhea-library/rhea-inhouse-noop/src/main/java/com/bytedance/rheatrace/RheaTrace3.java)
- [CLI 入口](../rhea-tool/rhea-trace-processor/src/main/java/com/bytedance/rheatrace/Main.java)

## 验证方式

1. 将 `enable_btrace` 分别设为 `true` 和 `false`，确认两种构建都能解析 `RheaTrace3`。
2. 真实库构建安装后执行 10 秒采集，确认生成的 `.pb` 可被 Perfetto UI 打开。
3. 运行 `adb forward --list`，确认 CLI 结束后没有残留本次端口转发。

## 相关文档

- [配置参考](configuration-reference.md)
- [总体架构](architecture.md)
- [排障指南](troubleshooting.md)
