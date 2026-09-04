# 服务端堆栈与卡顿产物解析接入

> 适用对象：接收 Android 线上堆栈/卡顿产物、选择混淆 mapping、生成分析报告并把 v3 卡顿事实映射到服务端存储的 Java 服务端开发者。

## 正文

### 能力与边界

`rhea-trace-processor` 提供不依赖 Spring、Servlet 或特定存储系统的 `StackParser` Java 接口。Processor 仅支持 v1 `.rheatrace.zip` 和最新 v3 `.rheajank.zip`：它执行 ZIP 安全检查、manifest 和 SHA-256 校验、Sampling v5 解码、可选的 ProGuard/R8 retrace、时间窗口裁剪和耗时估算，最终返回兼容的 `RHEA_STACK_REPORT` JSON。旧 v2 卡顿产物（无论是否使用 `packageName`）会在解析入口被拒绝。

v3 输入的 `schemaVersion=3 / artifactType=RHEA_JANK` 已经由 Processor 严格校验。Sampling v5 的小端序、`ELAPSED_REALTIME_NANOS` 时钟和 `RANGE` 选择类型由协议固定，不需要服务端从客户端重复传参。完整报告在 v3 时额外包含经过校验的 `sourceManifest`，供服务端取得事件身份和落库维度；报告自身仍保持 `schemaVersion=1`，这是输出报告版本，不是输入产物版本。

~~~text
Android .rheatrace.zip / .rheajank.zip
        │ 上传
        ▼
业务鉴权/大小限制/项目隔离
        │ InputStream + 可选 mapping File
        ▼
StackParser.parse 或 parseWithMappingResolver
        │ 校验、回调选 mapping、解码、聚合
        ▼
RHEA_STACK_REPORT JSON
        │
        ├── threads[].segments：按时间排列的堆栈明细
        └── threads[].callTree：按线程聚合的调用树
        │
        └── v3 sourceManifest：事件事实、查询维度和文件校验信息
~~~

Processor 不负责 HTTP 路由、鉴权、项目 Key、mapping 注册中心、对象存储、任务队列或结果持久化。解析是同步操作；超时、并发上限、幂等和数据库事务由服务端控制。服务端应把 v3 `sourceManifest` 与报告中的分析字段映射为自己的卡顿事件、事实和详情模型；本模块不会直接写入服务端数据库。

### 添加依赖

同一 Gradle 工程直接依赖模块：

~~~groovy
dependencies {
    implementation project(':rhea-trace-processor')
}
~~~

作为外部 Maven 依赖时，坐标由当前 POM 配置定义：

~~~groovy
dependencies {
    implementation 'io.github.mashanshui:rhea-trace-processor:<version>'
}
~~~

`<version>` 必须替换为服务端实际可获取并经过验证的版本。仓库存在 Maven 发布配置不等于该版本已经在 Maven Central 可下载；正式接入前应在目标制品仓库核对坐标和版本。

本地联调时只发布到当前用户的 Maven Local，不会上传到 Central：

~~~powershell
.\gradlew.bat :rhea-trace-processor:publishToMavenLocal --no-daemon
~~~

发布后，服务端测试工程应把 `mavenLocal()` 放在远程仓库之前，并使用与构件一致的版本，例如 `1.0.1`。本地构件默认位于 Windows 的 `%USERPROFILE%\\.m2\\repository\\io\\github\\mashanshui\\rhea-trace-processor\\<version>`；该目录属于开发机缓存，不应提交到仓库或作为正式发布凭据。

### 框架无关调用

`StackAnalyzer` 是无共享请求状态的 `StackParser` 实现，可以由服务端单例复用：

~~~java
import com.bytedance.rheatrace.stack.StackAnalyzer;
import com.bytedance.rheatrace.stack.StackMappingResolver;
import com.bytedance.rheatrace.stack.StackParser;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

public final class RheaStackService {
    private final StackParser parser = new StackAnalyzer();

    public String parse(InputStream artifactInput, File proguardMapping)
            throws IOException {
        // parser 不关闭 artifactInput；本层仍然负责它的生命周期。
        return parser.parse(artifactInput, proguardMapping);
    }

    public String parse(InputStream artifactInput) throws IOException {
        return parser.parse(artifactInput);
    }

    public String parseWithMappingResolver(InputStream artifactInput,
                                           StackMappingResolver mappingResolver)
            throws IOException {
        return parser.parseWithMappingResolver(artifactInput, mappingResolver);
    }
}
~~~

`parse` 和 `parseWithMappingResolver` 返回格式化 JSON 字符串，字符语义为 UTF-8。解析器不会关闭 `artifactInput`，也不会修改或删除 mapping 文件。调用方应使用 try-with-resources 关闭上传流。

`parseWithMappingResolver` 的回调参数是不可变的 `StackArtifactMetadata`。回调发生在 ZIP、manifest、文件大小和 SHA-256 全部通过校验之后、Sampling 解码之前；服务端可以在回调中使用已认证的 `projectId` 加上 `metadata.getMappingId()` 选择项目隔离的 mapping 文件。v3 的该字段就是 manifest 的 `buildId`，v1 则是 manifest 的 `mappingId`。返回 `null` 表示允许以未解混淆符号继续解析。

### Spring Boot 适配示例

以下代码只展示适配边界，Spring 依赖不应加入 processor 模块：

~~~java
@Service
public final class RheaStackParseService {
    private final StackParser parser = new StackAnalyzer();
    private final MappingRegistry mappingRegistry;

    public RheaStackParseService(MappingRegistry mappingRegistry) {
        this.mappingRegistry = mappingRegistry;
    }

    public String parse(String projectId, InputStream artifactInput) throws IOException {
        return parser.parseWithMappingResolver(artifactInput, metadata -> {
            // MappingRegistry 必须按 projectId 隔离目录，并校验返回文件可读。
            return mappingRegistry.resolve(projectId, metadata.getMappingId());
        });
    }
}
~~~

示例中的 `MappingRegistry` 是业务侧接口。生产 HTTP 控制器应把原始 ZIP 请求体作为 `InputStream` 传入，不要先把 v3 manifest 解包一遍来选择 mapping；应在认证得到 `projectId` 后使用回调完成项目隔离。mapping 文件不存在、不可读或不属于当前应用版本时，由业务侧映射为明确错误；不要把用户提供的 `buildId`/`mappingId` 拼接为任意路径。

### Mapping 选择

`mappingId` 只是产物和报告中的业务标识，不等同于 ProGuard/R8 mapping 文件内容。v1 的 mapping 业务标识来自 manifest `mappingId`；v3 不再写 `mappingId`，而是使用 `buildId`。推荐使用 `parseWithMappingResolver`，让 Processor 在完成 manifest 校验后把规范化的 `StackArtifactMetadata` 交给服务端回调：

~~~java
String reportJson = parser.parseWithMappingResolver(input, metadata ->
        mappingRegistry.resolve(projectId, metadata.getMappingId()));
~~~

服务端必须以认证得到的 `projectId` 隔离 mapping 根目录，并在回调中校验 mapping 文件存在、可读且属于该项目/构建。`buildId` 和 `mappingId` 只能作为受约束的业务标识，不能直接作为绝对路径、相对路径或路径片段。解析完成后，应核对 v3 `sourceManifest.packageName/buildId` 与服务端选择结果。

找不到 mapping 时可以选择以下明确策略之一：

- 返回业务错误并要求先上传 mapping，保证查询结果始终已解混淆；
- 传入 `null` 完成解析，并把结果标记为未解混淆，后续不要假设方法名已经还原。

不要把 Android 产物中的 sampling mapping 当作 ProGuard/R8 mapping。前者只保存采样方法指针到运行时符号的映射，后者用于恢复混淆前的类和方法名。

### 输入限制与并发

- 上传 `.rheatrace.zip` 或 `.rheajank.zip` 的压缩输入上限为 64 MiB，解压后受校验文件的总量上限同样为 64 MiB。建议 HTTP 容器也配置不高于 64 MiB 的原始请求体限制，使超限请求在落盘和解析前被拒绝。
- 每次解析使用独立临时文件和解包目录，正常返回或抛出异常时都会清理；进程被强制终止不属于正常清理保证，运维侧仍应监控系统临时目录。
- `StackAnalyzer` 没有跨请求的解析状态，可以并发复用。实际并发数仍应根据 ZIP 大小、mapping 大小、CPU 和堆内存设置有界线程池。
- 解析器不提供内部超时。服务端应配置请求或任务超时，但取消外层请求不能替代进程资源和并发隔离。

### v1/v3 输入支持矩阵

| 输入产物 | manifest | 二进制解释 | 分析窗口 | 完整报告附加数据 |
| --- | --- | --- | --- | --- |
| `.rheatrace.zip` | `schemaVersion=1`、`artifactType=RHEA_STACK` | 使用 manifest 声明的 Sampling v5 参数 | v1 的实际导出范围 | 保持现有报告结构 |
| `.rheajank.zip` | `schemaVersion=3`、`artifactType=RHEA_JANK` | Sampling v5、小端序、`ELAPSED_REALTIME_NANOS` | `messageStartNs` 到 `messageEndNs`，固定 RANGE | 增加完整 `sourceManifest` |

两类输入都返回 `schemaVersion=1 / RHEA_STACK_REPORT`。不要根据返回报告的 `schemaVersion` 判断输入是否为 v3；服务端应检查 v3 报告是否存在 `sourceManifest`，并再核对其中的 `schemaVersion` 和 `artifactType`。

### JSON 数据契约

顶层常用字段：

| 字段 | 含义 |
| --- | --- |
| `schemaVersion` | 当前报告 schema 版本，值为 `1` |
| `artifactType` | 完整报告固定为 `RHEA_STACK_REPORT` |
| `appName`、`mappingId`、`processId` | 产物标识和目标进程 |
| `actualStartNs`、`actualEndNs`、`durationNs` | 本次实际解析窗口，时间基准为 elapsed realtime 纳秒 |
| `recordCount`、`pointSampleCount`、`exactRecordCount` | 原始记录、点采样和精确 duration 记录数量 |
| `exactCoveredDurationNs` | 所有精确区间合并后的覆盖时长 |
| `estimatedCoveredDurationNs` | 所有估算区间合并后的覆盖时长 |
| `estimationPolicy` | 点采样估算间隔、上限和默认值来源 |
| `threads` | 按线程分别输出的时间明细和调用树 |
| `warnings` | 数据缺失、点采样语义、缓冲区覆盖等提示 |

#### v3 `sourceManifest`

当输入为 `schemaVersion=3 / RHEA_JANK` 时，完整报告增加 `sourceManifest` 对象。该对象是 Processor 已完成类型、范围和文件哈希校验后的 manifest 副本；服务端可以直接使用它构造卡顿事件事实，不需要再次解包 ZIP。v1 报告不包含该对象。

| 字段 | 类型/单位 | 服务端含义 |
| --- | --- | --- |
| `schemaVersion` | integer | 固定为 `3`，输入契约版本 |
| `artifactType` | string | 固定为 `RHEA_JANK`，输入产物类型 |
| `eventId` | string | 1～128 字符；与认证得到的 `projectId` 组成幂等键 |
| `occurredAt` | integer，Unix Epoch 毫秒 | 卡顿发生/确认的墙上时间；不能与单调纳秒时间相减 |
| `sessionId` | string | 客户端会话标识，用于会话维度统计 |
| `anonymousDeviceId` | string | 不可逆匿名设备标识，用于受影响设备统计 |
| `packageName` | string | Android 应用包名；报告中的 `appName` 由此映射 |
| `appVersion` | string | 应用版本筛选和回归比较 |
| `versionCode` | integer，非负 | 构建版本排序和定位 |
| `buildId` | string | 发布构建标识；默认同时作为 mapping 业务标识 |
| `environment` | string | `production`、`staging` 等环境维度 |
| `channel` | string | 发布渠道维度 |
| `osVersion` | string | Android 系统版本或约定的 API Level 字符串 |
| `deviceModel` | string | 脱敏后的设备型号维度 |
| `scene` | string | 稳定、低基数的卡顿场景名 |
| `messageStartNs` | integer，elapsed realtime 纳秒 | 主线程消息起点及报告 `requestedStartNs` |
| `messageEndNs` | integer，elapsed realtime 纳秒 | 主线程消息终点及报告 `requestedEndNs`；必须大于起点 |
| `thresholdNs` | integer，纳秒 | 客户端本次采用的卡顿阈值 |
| `minSampleIntervalNs` | integer，纳秒 | Native 采集器的最小采样请求间隔 |
| `attemptedSampleCount` | integer，非负 | 客户端在该消息区间实际发起的采样请求数 |
| `processId` | integer，正数 | 主进程 ID；用于识别报告中的主线程 |
| `files` | object | 两个二进制条目的期望大小和 SHA-256；Processor 已在返回前核对 |

`sourceManifest.files.sampling` 和 `sourceManifest.files.sampling-mapping` 各自包含 `size`（integer）与 `sha256`（小写 64 位十六进制字符串）。所有纳秒和毫秒整数必须按 Java/Kotlin `Long` 或等价整数解析，禁止先转换成 `Double`；服务端落库时也不要经过 JavaScript number。

服务端派生字段建议按以下来源计算：

- `messageDurationNs = messageEndNs - messageStartNs`，不要使用 `occurredAt` 参与计算。
- `expectedSampleCount` 按消息耗时除以 `minSampleIntervalNs` 向上取整；溢出时使用服务端定义的饱和值。
- `successfulSampleCount` 统计主线程窗口内 `eventType=kCustom` 的成功采样，不能把其它 Hook、其它线程或报告 `recordCount` 当作成功采样数。
- `droppedSampleCount = max(0, attemptedSampleCount - successfulSampleCount)`；采样空洞、覆盖和残缺状态由服务端结合 `segments`、`warnings` 和质量计数决定。
- v3 卡顿算法标识固定为 `jank-artifact-v2`（算法标识沿用既有值）。报告中的 `recordCount` 是解码后的 Sampling 记录数，不等于客户端尝试次数。

`threads[]` 包含 `tid`、`threadName`、`sampleCount`、`estimatedCoveredDurationNs`、`segments` 和 `callTree`。不同线程的同名方法不会合并。

`segments[]` 表示按时间排序的单条堆栈证据：

| 字段 | 含义 |
| --- | --- |
| `startOffsetNs`、`endOffsetNs` | 相对实际窗口起点的开始和可选精确结束位置 |
| `exactDurationNs` | 仅 duration Hook 有值；点采样为 `null` |
| `estimatedEndOffsetNs`、`estimatedDurationNs` | 用于展示和采样归因的估算区间 |
| `durationKind` | `EXACT` 或 `ESTIMATED` |
| `estimateSource` | `EXACT`、`NEXT_SAMPLE`、`CAPPED` 或 `LAST_SAMPLE` |
| `eventType` | 采样事件类型 |
| `stack` | root 到 leaf 的方法帧数组 |

`callTree[]` 节点包含方法、源码位置、样本数、事件类型、耗时字段和 `children`：

| 字段 | 含义 |
| --- | --- |
| `method`、`displayName` | 原始/解混淆后的方法符号和展示名 |
| `sourceFile`、`lineNumber`、`nativeMethod` | 可用时提供的源码和 Native 标识 |
| `sampleCount` | 该公共调用前缀出现的记录数 |
| `exactDurationNs` | 节点精确区间并集；没有 duration Hook 时为 `null` |
| `selfDurationNs` | 精确区间扣除直接子节点精确区间后的未归属时间，不是 CPU 自耗时 |
| `estimatedDurationNs` | 节点估算区间并集，即估算总耗时 |
| `estimatedSelfDurationNs` | 估算区间扣除直接子节点后的未归属估算时间，不代表 CPU 自耗时 |

点采样只证明某个时刻观察到了调用栈。`estimatedDurationNs` 和 `estimatedSelfDurationNs` 只能用于估算归因；只有带真实开始与结束时间的 duration Hook 才能提供 `exactDurationNs` 证据。

### 服务端解析与落库顺序

建议服务端按以下顺序处理 v3 请求：

1. 在读取请求体前完成项目 Key 鉴权、媒体类型和原始 ZIP 大小限制，得到可信 `projectId`。
2. 使用 `parseWithMappingResolver` 解析一次；mapping 回调只使用 `projectId` 和已校验的 `metadata.getMappingId()`，不让产物字段构造任意路径。
3. 将返回 JSON 解析为对象并确认 `sourceManifest.schemaVersion=3`、`sourceManifest.artifactType=RHEA_JANK`；完整报告的 `schemaVersion=1` 不代表输入是 v1。
4. 以 `projectId + sourceManifest.eventId` 做幂等判断。重复事件应返回/记录 duplicate，不重新生成另一事件 ID；首次事件才写入卡顿事实、分析摘要和堆栈详情。
5. 事件事实字段来自 `sourceManifest`，分析证据来自 `report`；服务端应保留 `messageStartNs/messageEndNs` 的单调时钟语义，并单独保存 `occurredAt` 的墙上时间。
6. 文件是否进入对象存储、报告是否保留以及事务重试由服务端定义；Processor 本身不产生持久化副作用。

如果服务端仍需支持 v1，应按 `sourceManifest` 缺失且报告只有旧字段的分支处理，不要把 v1 的 `recordCount`、`mappingId` 或实际导出范围套用到 v3 卡顿事实。

### 错误处理建议

| 场景 | 建议 HTTP 结果 |
| --- | --- |
| 原始 ZIP 请求体在业务网关或容器处超过 64 MiB | `413 Payload Too Large` |
| 请求缺少 artifact 或参数非法 | `400 Bad Request` |
| ZIP 损坏、条目非法、manifest/校验和/协议不合法 | `422 Unprocessable Entity` |
| v1 `mappingId` 或 v3 `buildId` 不存在且业务要求必须解混淆 | 业务定义的 `404` 或 `422` |
| mapping 文件不可读、临时目录不可用等服务端故障 | `500 Internal Server Error` |

不要依赖 `IOException` 的中文文本作为长期稳定错误码。HTTP 层应先完成大小、必填参数和项目认证；mapping 解析回调中的注册表/存储故障应与上传产物校验失败区分，再把剩余解析失败统一映射为不可处理的产物；同时保留服务端日志中的异常链用于排障。v3 不要求服务端在调用 Processor 前自行解包 manifest。

## 相关源码

- [服务端解析接口](../rhea-tool/rhea-trace-processor/src/main/java/com/bytedance/rheatrace/stack/StackParser.java)
- [产物元数据视图](../rhea-tool/rhea-trace-processor/src/main/java/com/bytedance/rheatrace/stack/StackArtifactMetadata.java)
- [Mapping 解析回调](../rhea-tool/rhea-trace-processor/src/main/java/com/bytedance/rheatrace/stack/StackMappingResolver.java)
- [完整报告分析器](../rhea-tool/rhea-trace-processor/src/main/java/com/bytedance/rheatrace/stack/StackAnalyzer.java)
- [ZIP 安全校验](../rhea-tool/rhea-trace-processor/src/main/java/com/bytedance/rheatrace/stack/StackArtifact.java)
- [采样解码](../rhea-tool/rhea-trace-processor/src/main/java/com/bytedance/rheatrace/trace/SamplingTraceDecoder.java)

## 验证方式

1. 分别上传合法 v1 `.rheatrace.zip` 和 v3 `.rheajank.zip`，确认响应为 `RHEA_STACK_REPORT`，且每个线程同时包含 `segments` 和 `callTree`。
2. v3 使用 `parseWithMappingResolver`，确认回调在产物校验后收到 `buildId`，报告包含 22 个 `sourceManifest` 字段、文件大小和 SHA-256；v1 报告不出现 `sourceManifest`。
3. 对同一产物分别返回 mapping 和 `null`，确认前者的方法名已恢复、后者仍可解析，且 mapping 文件仍存在未被修改或删除。
4. 上传损坏 ZIP、条目缺失、SHA-256 不匹配、版本不支持和超过 64 MiB 的产物，确认请求被拒绝、mapping 回调未被调用且临时目录没有正常流程残留。
5. 使用大于 JavaScript 安全整数范围的 v3 纳秒值解析，确认 `sourceManifest` 中整数没有精度损失；并发解析多个不同产物，确认线程、时间范围和调用树不会串扰。
6. 运行 `./gradlew :rhea-trace-processor:test :rhea-trace-processor:jar verifyKnowledgeBase`。

## 相关文档

- [线上堆栈缓冲与导出](online-stack.md)
- [CLI 处理器](cli-processor.md)
- [协议与数据格式](protocol-and-data-formats.md)
- [排障指南](troubleshooting.md)
