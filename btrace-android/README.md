# btrace Android

btrace Android 是基于 Perfetto 的 Android 性能追踪实现。本目录同时包含端上采集 SDK、无操作替代库、桌面端 Trace 处理器和示例应用。

本页是 Android 子项目的知识库入口。跨平台产品介绍、发行包和公开使用说明请参阅上级仓库的 [中文 README](../README.zh-CN.md)；采样方案背景与原理请参阅 [中文原理介绍](../INTRODUCTION.zh-CN.MD)。

## 我想使用 btrace

1. 阅读[快速开始](docs/getting-started.md)，完成依赖、初始化和第一次采集。
2. 如果要线上采集卡顿，阅读[在线卡顿采集](docs/online-jank.md)并接入异步导出。
3. 按[配置参考](docs/configuration-reference.md)选择 CLI 参数和系统属性。
4. 遇到问题时查阅[排障指南](docs/troubleshooting.md)。

## 我想阅读或修改源码

1. 从[总体架构](docs/architecture.md)理解模块、控制流和数据流。
2. 分别阅读 [App 端 SDK](docs/app-sdk.md)、[Native 实现](docs/native-runtime.md)和 [CLI 处理器](docs/cli-processor.md)。
3. 使用[源码参考](docs/source-reference.md)定位具体类和组件，提交前遵循[开发与发布](docs/development-and-release.md)。

## 模块

| 模块 | 角色 |
| --- | --- |
| `app` | 演示 SDK 初始化、手动抓栈及测试场景的示例应用 |
| `rhea-inhouse` | 真正执行采样、Hook、缓存和数据导出的 Android Library |
| `rhea-inhouse-noop` | 保持公共 API 兼容但不执行采集的替代 Android Library |
| `rhea-trace-processor` | 控制设备采集、解析 App 数据并生成 Perfetto Trace 的 Java CLI |

## 常用命令

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat :rhea-trace-processor:build
.\gradlew.bat test
.\gradlew.bat verifyKnowledgeBase
```

完整目录和推荐阅读顺序见[知识库首页](docs/README.md)。本文档以当前 3.0 源码为事实基准；无法由源码确认的公开能力会明确标注为“待核实”。
