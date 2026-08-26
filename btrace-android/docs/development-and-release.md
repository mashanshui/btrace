# 开发与发布

> 适用对象：准备修改、测试、发布 btrace Android 或维护本知识库的贡献者。

## 正文

### 本地环境与构建

Windows 优先使用 Gradle Wrapper：

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat :rhea-trace-processor:build
.\gradlew.bat test
.\gradlew.bat connectedAndroidTest
.\gradlew.bat verifyKnowledgeBase
```

线上堆栈导出与解析的 app 端到端测试需要已连接的 API 26+、64 位设备：

```powershell
.\gradlew.bat --no-daemon -Ponline_trace_test=true -Pdevice=设备序列号 :app:parseOnlineStackFlow
```

该任务会运行 `app` instrumentation 测试，拉取 RANGE/ALL 产物，再调用 `rhea-trace-processor analyze-stack` 生成完整 JSON、聚合调用树 JSON、HTML 和 Perfetto PB。输出位于 `app/build/online-stack-flow/`，不应提交。

构建真实库需要 `local.properties` 中的 Android SDK 路径和指定 NDK。`local.properties`、密钥、`build/`、`.cxx/` 及本机产物不得提交。

### 按改动类型验证

| 改动 | 最低验证 |
| --- | --- |
| `RheaTrace3` 或 noop | 两种 `enable_btrace` 构建；公共类、方法和行为兼容 |
| TraceManager/HTTP | 普通、`-r`、`-w` 生命周期；重复 start/stop；下载超时/清理 |
| Sampling 配置 | Java deflate 与 C++ 解析顺序；默认值与系统属性 |
| Native Hook | arm64 真机；至少记录 API/ABI；命中、失败、恢复和压力场景 |
| RingBuffer/格式 | 小 buffer 覆盖；旧/新样本解码；Perfetto UI 打开 |
| CLI 参数 | Windows 与 macOS 相关分支；过滤/转发数组；非法输入 |
| ProGuard retrace | 未混淆、R8 mapping、同名重载和 synthesized 方法 |
| Perfetto proto | runtime/生成版本；perfetto/simple 输出；Perfetto UI |
| 文档 | `verifyKnowledgeBase`；角色路径和 Mermaid 人工抽查 |

当前自动化测试主要是 Android 模板测试，核心 JNI、线程、格式和 Trace 行为覆盖有限。涉及这些区域时，不能用 `test` 通过替代真机和兼容样本验证。

### Native 调试

1. 使用 Debug 构建启用 `RHEA_DEBUG` 日志。
2. 通过 `adb logcat` 过滤 `RheaTrace`、`RheaServer` 和 Sampling 标签。
3. 崩溃时保留设备 API、ABI、ROM、App 位数、完整 tombstone 和符号化 so。
4. Hook 失败时核对 ART/系统库符号、ShadowHook 结果和停止恢复路径。
5. dump 失败时核对内部文件目录、errno、token 范围和 buffer 容量。

### 发布边界

三个发布模块使用 `POM_GROUP_ID=com.bytedance.btrace` 和统一版本：

- `rhea-inhouse`：AAR，包含真实 Java/Native 实现；
- `rhea-inhouse-noop`：AAR，公共 API 空实现；
- `rhea-trace-processor`：带依赖的可执行 jar，manifest Main-Class 为 `com.bytedance.rheatrace.Main`。

发布前至少确认：

1. 真实/noop 公共 API 一致；
2. CLI 与端上 sampling 格式版本兼容；
3. POM 版本、公开下载链接和文档版本一致；
4. AAR ABI、consumer ProGuard 规则和依赖完整；
5. jar 包含两个平台的 `record_android_trace` 资源及正确 manifest；
6. 没有凭据、本机路径或生成产物进入提交。

### 文档维护矩阵

| 源码变化 | 必须复查的文档 |
| --- | --- |
| 公共 API/noop | 快速开始、App 端 SDK、源码参考 |
| 模块或依赖 | 子项目首页、总体架构、开发与发布 |
| CLI 参数/默认值 | 快速开始、CLI 处理器、配置参考、排障指南 |
| HTTP action/端口发现 | 总体架构、CLI 处理器、协议与数据格式、排障指南 |
| 系统属性 | App 端 SDK、配置参考、排障指南 |
| Native Hook/采样字段 | Native 实现、协议与数据格式、源码参考、术语表 |
| 文件格式/proto | CLI 处理器、协议与数据格式、开发与发布 |
| 支持版本/ABI | 快速开始、配置参考、排障指南、知识库首页待核实项 |

### 文档写作约定

- 正文使用中文，代码标识符保持原样。
- 文件名使用英文小写加连字符。
- 每篇主题文档包含“适用对象、正文、相关源码、验证方式、相关文档”。
- 只陈述源码可证实的行为；产品承诺不明确时标记“待核实”。
- 相对链接指向文件而非行号；改名时同步更新所有入口。
- Mermaid 图描述稳定关系，不塞入容易变化的字段明细。

## 相关源码

- [模块声明](../settings.gradle)
- [根构建配置](../build.gradle)
- [Android Library 构建](../rhea-library/rhea-inhouse/build.gradle)
- [CLI 构建](../rhea-tool/rhea-trace-processor/build.gradle)

## 验证方式

1. 运行本页列出的与改动类型对应的 Gradle 命令。
2. 对 Android 行为在 PR 中记录设备 API/ABI；对 UI/Trace 结果保留截图或样本。
3. 运行 `verifyKnowledgeBase` 并人工检查所有修改过的 Mermaid 图。

## 相关文档

- [知识库首页](README.md)
- [源码参考](source-reference.md)
- [排障指南](troubleshooting.md)
- [协议与数据格式](protocol-and-data-formats.md)
