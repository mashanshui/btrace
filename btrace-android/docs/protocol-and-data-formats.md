# 协议与数据格式

> 适用对象：维护 App/CLI 通信、采样编码、解码兼容或 Trace 合并的贡献者。

## 正文

### HTTP 控制协议

端上使用 NanoHTTPD 在随机端口启动 HTTP/1.x 服务。CLI 通过 ADB forward 后以 GET 请求访问根路径。该协议仅用于本机调试链路，没有鉴权、TLS 或稳定公共 API 承诺。

| 请求 | 参数 | 成功响应 | 主要副作用 |
| --- | --- | --- | --- |
| `?action=start` | 无 | `200 start trace` | 异步 `startTracing(true)`，重置 ready/error/debug 状态 |
| `?action=stop` | 无 | `200 stop trace` | stop 并异步 dump token 区间 |
| `?action=clean` | 无 | `200 clear trace` | 删除当前 PID 的内部 Trace 目录 |
| `?action=query&name=error` | `name=error` | 错误文本或 `no error` | 无 |
| `?action=query&name=debug` | `name=debug` | JSON 或空字符串 | 返回各 TraceMeta 的 start/end/capacity |
| `?action=download&name=sampling` | 文件名 | octet-stream | 等待 dump ready 后读取采样文件 |
| `?action=download&name=sampling-mapping` | 文件名 | octet-stream | 等待 dump ready 后读取映射文件 |

缺少 action/name、未知 action/query name、等待超时或文件不存在返回 404。`download` 的 name 直接相对 `hostDir` 构造文件，当前调用方只传固定文件名；不要把服务暴露到非受信网络。

### 端口发现

App 将端口表示为目录：

```text
<externalFilesDir>/rhea-port/<listeningPort>
```

CLI 使用固定的 `/storage/emulated/0/Android/data/<package>/files/rhea-port` 路径执行 `adb shell ls`，把第一条可解析的正整数作为端口。不同 ROM 的存储映射、权限策略或多残留目录都可能导致发现失败。

### sampling 文件

`sampling` 使用 Native 当前端字节序写入，Android 支持 ABI 上按小端序运行；Java 解码器显式使用 `ByteOrder.LITTLE_ENDIAN`。头部固定 28 字节：

| 顺序 | 类型 | 含义 |
| --- | --- | --- |
| 1 | `uint32` | magic，当前为 `0x01020304` |
| 2 | `uint32` | TraceMeta type/offset，当前 Sampling 为 0 |
| 3 | `uint32` | 数据格式 version |
| 4 | `uint64` | dump 时刻 |
| 5 | `uint32` | 记录数量 |
| 6 | `int32` | extra JSON 字节数 |
| 7 | `byte[]` | extra JSON，当前至少包含 `processId` |

后续为变长 `SamplingRecord`。每条记录依次编码事件类型、16 位 tid、消息 ID、六个 64 位时间/分配字段、三个 32 位 rusage 字段和栈信息。具体栈编码和不同 version 的分支由 `StackList.decode` 定义。

在线导出在这些既有文件外增加 ZIP 封装：`manifest.json`、`sampling.bin`、`sampling-mapping.bin`。manifest 的 `schemaVersion=1`、`samplingFormatVersion=5`、事件单调时钟范围、mappingId、文件大小和 SHA-256 供服务端校验；详见[在线卡顿采集](online-jank.md)。

解码器当前读取但不验证 magic 和 type。格式维护者仍必须保留正确值，并在新增版本时实现显式校验/兼容，不能依赖“旧解码器碰巧能读”。

### sampling-mapping 文件

mapping 同样为小端序：

| 顺序 | 类型 | 含义 |
| --- | --- | --- |
| 1 | `uint64` | magic，当前写 0 |
| 2 | `uint32` | version，当前写 1 |
| 3 | `uint32` | 方法条目数 |
| 4 | 重复条目 | `uint64 pointer` + `uint16 nameLength` + UTF-8/默认字符集符号字节 |
| 5 | 直到 EOF | `uint16 tid` + `uint8 nameLength` + 线程名字节 |

方法条目只包含本次 dump 记录引用过的指针。符号通过 `Stack::toString` 在端上解析；CLI 可再用 ProGuard/R8 mapping 还原 Java 混淆名。线程名从 `/proc/self/task/<tid>/comm` 读取，缓冲上限为 16 字节左右，可能包含换行或被截断。

Native 写入和 Java `new String(byte[])` 都没有显式声明字符集，默认依赖运行环境。修改协议时应新增明确字符集和版本策略，而不是悄悄改变现有 version 1。

### 最终 Perfetto Trace

`StackTraceConvertor` 把采样记录转为进程/线程 descriptor、TrackEvent slice 和 counter。`Trace.marshal` 追加 ProcessTree packet。

- Perfetto 模式：先把 `systemTrace.trace` 原始字节写到输出流，再序列化 App 侧 Trace packet。
- simple 模式：只序列化 App 侧 Trace。
- 输出扩展名通常为 `.pb`，实质是 Perfetto Trace protobuf 数据流。

### 兼容性规则

1. 增加或重排头字段、SamplingRecord 字段、栈编码时必须提升 version。
2. Native writer 和 Java decoder 必须在同一变更中更新，并增加旧样本测试。
3. HTTP action 或文件名变化必须同步更新 `HttpServer`、`Main`、`Adb.Http` 和本文档。
4. 生成的 Perfetto proto 版本由 `protobuf-java` 依赖和生成源码共同决定，升级时先验证 `Trace.writeTo` 能与系统 Trace 拼接并被 Perfetto UI 读取。

## 相关源码

- [HttpServer](../rhea-library/rhea-inhouse/src/main/java/com/bytedance/rheatrace/server/HttpServer.java)
- [PerfBuffer](../rhea-library/rhea-inhouse/src/main/cpp/base/PerfBuffer.h)
- [SamplingRecord](../rhea-library/rhea-inhouse/src/main/cpp/sampling/SamplingRecord.h)
- [SamplingTraceDecoder](../rhea-tool/rhea-trace-processor/src/main/java/com/bytedance/rheatrace/trace/SamplingTraceDecoder.java)

## 验证方式

1. 保存一组真实 `sampling`、`sampling-mapping` 和最终 `.pb` 作为兼容样本。
2. 使用十六进制查看器核对 28 字节 sampling 头与小端字段。
3. 分别在有/无 ProGuard mapping、有/无线程名、RingBuffer 覆盖场景解码。
4. 用 Perfetto UI 打开 perfetto/simple 两种模式产物，并检查进程、线程、slice 和 counter。

## 相关文档

- [总体架构](architecture.md)
- [Native 实现](native-runtime.md)
- [CLI 处理器](cli-processor.md)
- [配置参考](configuration-reference.md)
