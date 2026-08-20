# Repository Guidelines

## 项目结构与模块组织

- `app/` 是示例 Android 应用；业务代码在 `src/main/java`，资源在 `src/main/res`，单元测试和设备测试分别位于 `src/test` 与 `src/androidTest`。
- `rhea-library/rhea-inhouse/` 是实际的 Android 追踪库，包含 Java API 和 `src/main/cpp` 下的 C++/CMake 原生实现；`rhea-inhouse-noop/` 提供无操作替代实现。
- `rhea-tool/rhea-trace-processor/` 是 Java 8 命令行处理工具，入口为 `com.bytedance.rheatrace.Main`，附带脚本资源位于 `src/main/resources`。
- 根目录的 `settings.gradle`、`build.gradle` 和 `gradle/` 维护多模块构建及发布配置。不要提交 `build/`、`.cxx/` 或本机配置文件。

## 构建、测试与本地开发

在 Windows 上优先使用 Gradle Wrapper：

```powershell
.\gradlew.bat assembleDebug                 # 构建调试 APK 和相关模块
.\gradlew.bat :rhea-trace-processor:build   # 构建并测试处理工具
.\gradlew.bat test                          # 运行主机上的 JUnit 测试
.\gradlew.bat connectedAndroidTest          # 在已连接设备/模拟器上运行设备测试
```

本地构建需要 Android SDK；原生库使用 `gradle.properties` 中指定的 NDK 版本和 `local.properties` 中的 SDK 路径。可通过 `enable_btrace=true/false` 切换真实库与 noop 库。

## 编码风格与命名约定

Kotlin 遵循 `kotlin.code.style=official`，Java/Kotlin/C++ 默认使用 4 空格缩进，并保持现有文件的换行与括号风格。包名使用小写，类/接口使用 PascalCase，方法和变量使用 camelCase，常量使用 `UPPER_SNAKE_CASE`。新增源文件应保留 Apache 2.0 版权头；仓库未配置独立格式化或静态检查工具，提交前请使用 Android Studio/IDE 的格式化功能并人工检查 native 代码。

## 测试指南

单元测试使用 JUnit 4，设备测试使用 AndroidX JUnit4（必要时配合 Espresso）。测试类以 `Test` 结尾，放在与被测代码对应的包路径下；测试方法应明确描述行为，例如 `addition_isCorrect`。涉及 JNI、线程或追踪行为的改动，应补充设备测试并记录设备 ABI/API；当前未设置覆盖率门槛。

## 提交与合并请求

历史提交多采用简短英文动词开头的主题，如 `add ...`、`fix ...`、`rename ...`；请保持单一主题、命令式且不超过一行。合并请求应说明问题、实现方式和影响模块，列出已运行的 Gradle 命令；涉及 UI 时附截图，涉及 Android 行为时注明设备/API/ABI，并关联对应 issue。提交前确认未包含 `local.properties`、密钥或生成产物。
