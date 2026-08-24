# btrace Android 中文知识库

> 适用对象：SDK 使用者、性能分析人员、Android/Java/C++ 项目贡献者。

## 正文

### 按角色阅读

SDK 使用者推荐按以下顺序阅读：

1. [快速开始](getting-started.md)
2. [配置参考](configuration-reference.md)
3. [线上堆栈缓冲与导出](online-stack.md)
4. [排障指南](troubleshooting.md)
5. [术语表](glossary.md)

项目贡献者推荐按以下顺序阅读：

1. [总体架构](architecture.md)
2. [App 端 SDK](app-sdk.md)
3. [Native 实现](native-runtime.md)
4. [CLI 处理器](cli-processor.md)
5. [协议与数据格式](protocol-and-data-formats.md)
6. [源码参考](source-reference.md)
7. [开发与发布](development-and-release.md)

### 文档清单

| 文档 | 主要回答的问题 | 状态 |
| --- | --- | --- |
| [快速开始](getting-started.md) | 如何构建、接入、采集并打开结果？ | 已按当前源码核对 |
| [总体架构](architecture.md) | 四个模块如何协作？控制流和数据流如何经过设备与 PC？ | 已按当前源码核对 |
| [App 端 SDK](app-sdk.md) | 公共 API、生命周期和 Trace Ability 如何工作？ | 已按当前源码核对 |
| [Native 实现](native-runtime.md) | JNI、Hook、抓栈、RingBuffer 和 dump 如何工作？ | 已按当前源码核对 |
| [CLI 处理器](cli-processor.md) | 参数、ADB、系统 Trace、解码和合并如何串联？ | 已按当前源码核对 |
| [协议与数据格式](protocol-and-data-formats.md) | HTTP action、采样文件和 mapping 文件如何约定？ | 已按当前源码核对 |
| [配置参考](configuration-reference.md) | 构建开关、系统属性和 CLI 参数分别控制什么？ | 已按当前源码核对 |
| [线上堆栈缓冲与导出](online-stack.md) | 如何常驻缓冲、按时间/全量导出并生成树形报告？ | 已按当前源码核对 |
| [源码参考](source-reference.md) | 每个手写 Java 类和 Native 组件负责什么？ | 已按当前源码核对 |
| [开发与发布](development-and-release.md) | 如何构建、测试、调试、发布和维护文档？ | 已按当前源码核对 |
| [排障指南](troubleshooting.md) | 不同阶段失败时如何定位？ | 已按当前源码核对 |
| [术语表](glossary.md) | 项目内关键名词是什么意思？ | 已按当前源码核对 |

### 事实来源与维护规则

1. 当前源码高于上级 README、历史 issue 或口头说明；文档描述行为时必须能指向源码、构建配置或可复现命令。
2. 公开文档与源码不一致时保留两种说法，并使用“待核实”标记，不在知识库中替项目做产品决策。
3. 源码引用使用仓库相对路径和符号名，不绑定行号。
4. 修改公共 API、CLI 参数、系统属性、HTTP action、文件格式、模块依赖或支持范围时，必须同步更新相关文档。
5. Perfetto proto Java 文件属于生成代码，仅记录来源、依赖版本和项目使用入口，不维护逐类说明。
6. 提交前运行 `verifyKnowledgeBase`，确保索引、相对链接、源码引用和必需章节完整。

### 已知待核实项

- Gradle 配置的 `minSdk` 是 21，而上级公开 README 声明实际使用需 Android 8.0 及以上；知识库区分“可编译下限”和“公开支持下限”。
- 构建配置包含 `armeabi-v7a`，上级公开 README 又声明 32 位设备或应用无法采集；运行支持口径需要项目维护者确认。
- 上级中文 README 列出 `-mainThreadOnly`，当前 `Arguments.Parser` 没有专门解析该参数；不要将它视为当前 CLI 的已验证专用参数。
- 上级 README 对 `-t` 默认值的描述与当前解析器行为不同；当前源码在支持中断键的平台进入交互模式，在 Windows 要求显式传入。

## 相关源码

- [模块声明](../settings.gradle)
- [根构建配置](../build.gradle)
- [公开中文 README](../../README.zh-CN.md)
- [方案原理介绍](../../INTRODUCTION.zh-CN.MD)

## 验证方式

```powershell
.\gradlew.bat verifyKnowledgeBase
```

人工抽查两个阅读路径，确认从子项目首页到首次采集、从架构图到任一源码组件均不超过三次点击。

## 相关文档

- [子项目首页](../README.md)
- [开发与发布](development-and-release.md)
- [术语表](glossary.md)
