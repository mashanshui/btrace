/*
 * Copyright (C) 2021 ByteDance Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package rhea.sample.android

import android.app.Application
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bytedance.rheatrace.RheaTrace3
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Enumeration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipFile

/**
 * 线上堆栈端到端设备测试：采集、范围导出、全量导出和产物自校验。
 *
 * 该测试由 :app:parseOnlineStackFlow 间接运行。测试完成后，Gradle 任务从设备拉取
 * range.rheatrace.zip 和 all.rheatrace.zip，并调用 rhea-trace-processor 生成报告。
 */
@RunWith(AndroidJUnit4::class)
class OnlineStackExportFlowTest {

    private val context by lazy {
        InstrumentationRegistry.getInstrumentation().targetContext
    }

    @Before
    fun setUp() {
        // 必须先清理上一次设备产物；即使当前设备不支持而跳过测试，也不能让 Gradle
        // 误拉取旧 ZIP 并报告假成功。
        context.getExternalFilesDir(OUTPUT_DIRECTORY)?.let { outputDir ->
            File(outputDir, "range.rheatrace.zip").delete()
            File(outputDir, "all.rheatrace.zip").delete()
            File(outputDir, "jank.rheajank.zip").delete()
            File(outputDir, "android-test-jank-1.rheajank.zip").delete()
            File(outputDir, "android-test-jank-2.rheajank.zip").delete()
        }
        assumeTrue(
            "线上端到端测试需要 -Ponline_trace_test=true",
            BuildConfig.ONLINE_TRACE_TEST
        )
        assumeTrue("线上采集最低支持 API 26", Build.VERSION.SDK_INT >= 26)
        assumeTrue("线上采集只支持 64 位进程", Process.is64Bit())

        val launchIntent = context.packageManager
            .getLaunchIntentForPackage(context.packageName)
        assertNotNull("示例 app 没有可启动 Activity", launchIntent)
        launchIntent!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        InstrumentationRegistry.getInstrumentation().startActivitySync(launchIntent)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        val application = context.applicationContext as Application
        val onlineConfig = RheaTrace3.OnlineTraceConfig.builder()
            .setBufferSizeBytes(1024 * 1024)
            .setMinSampleIntervalMs(5)
            .setDiskQuotaBytes(8L * 1024L * 1024L)
            .setMaxArtifactBytes(4L * 1024L * 1024L)
            .setMappingId("app-online-test")
            .setAnonymousDeviceId("device-anonymous-online-test")
            .setBuildId("app-online-test-build")
            .setEnvironment("test")
            .setChannel("instrumentation")
            .setProcessId(PROCESS_ID)
            .build()
        val result = RheaTrace3.initOnline(application, onlineConfig)
        assertTrue(
            "线上初始化失败：$result",
            result == RheaTrace3.InitResult.STARTED
                    || result == RheaTrace3.InitResult.ALREADY_STARTED
        )
        assertEquals(
            RheaTrace3.InitResult.ALREADY_STARTED,
            RheaTrace3.initOnline(application, onlineConfig)
        )
        val conflictingConfig = RheaTrace3.OnlineTraceConfig.builder()
            .setBufferSizeBytes(1024 * 1024)
            .setMinSampleIntervalMs(5)
            .setDiskQuotaBytes(8L * 1024L * 1024L)
            .setMaxArtifactBytes(4L * 1024L * 1024L)
            .setMappingId("app-online-test")
            .setAnonymousDeviceId("device-anonymous-online-test")
            .setBuildId("app-online-test-build")
            .setEnvironment("test")
            .setChannel("instrumentation")
            .setProcessId(CONFLICT_PROCESS_ID)
            .build()
        assertEquals(
            RheaTrace3.InitResult.MODE_CONFLICT,
            RheaTrace3.initOnline(application, conflictingConfig)
        )
        RheaTrace3.getPendingJankFiles().forEach { RheaTrace3.deleteJankFile(it) }
    }

    @After
    fun tearDown() {
        RheaTrace3.stopOnlineTracing()
    }

    @Test
    fun exportRangeAndAllArtifacts_canBeParsedByProcessor() {
        val captureDone = CountDownLatch(1)
        val mainHandler = Handler(Looper.getMainLooper())
        val mainTid = AtomicInteger(0)
        val worker = HandlerThread("online-stack-worker")
        worker.start()
        val workerDone = CountDownLatch(1)
        Handler(worker.looper).post {
            try {
                repeat(5) {
                    RheaTrace3.captureStackTrace(true)
                }
            } finally {
                workerDone.countDown()
            }
        }
        assertTrue("后台线程抓栈未完成", workerDone.await(5, TimeUnit.SECONDS))
        worker.quitSafely()
        mainHandler.post {
            try {
                mainTid.set(Process.myTid())
                // Native 线上模式只接受主线程抓栈；间隔高于 5ms 以避开限流。
                repeat(40) {
                    RheaTrace3.captureStackTrace(true)
                    SystemClock.sleep(7)
                }
            } finally {
                captureDone.countDown()
            }
        }
        assertTrue("主线程抓栈未完成", captureDone.await(15, TimeUnit.SECONDS))

        val available = waitForAvailableRange()
        assertTrue("RingBuffer 没有有效记录：$available", available.recordCount > 0)
        assertTrue(available.endElapsedRealtimeNanos > available.startElapsedRealtimeNanos)

        val now = SystemClock.elapsedRealtimeNanos()
        val requestedStart = maxOf(0L, available.startElapsedRealtimeNanos - 1_000_000L)
        val requestedEnd = minOf(now, available.endElapsedRealtimeNanos + 1_000_000L)
        assertTrue(requestedEnd > requestedStart)

        val rangeResult = awaitRangeExport(requestedStart, requestedEnd)
        val allResult = awaitAllExport()
        val jankEvent = RheaTrace3.JankEvent.builder()
            .setEventId("android-test-jank-1")
            .setOccurredAt(System.currentTimeMillis())
            .setSessionId("android-test-session")
            .setScene("online_stack_flow")
            .setMessageStartNs(requestedStart)
            .setMessageEndNs(requestedEnd)
            .setThresholdNs(minOf(100_000_000L, requestedEnd - requestedStart))
            .setAttemptedSampleCount(40)
            .build()
        val jankResult = awaitJankExport(jankEvent)
        assertNotNull(jankResult.artifact)
        val firstJankBytes = jankResult.artifact!!.readBytes()
        val firstJankManifest = readManifest(jankResult.artifact!!)
        val reusedJankResult = awaitJankExport(jankEvent)
        val secondJankEvent = RheaTrace3.JankEvent.builder()
            .setEventId("android-test-jank-2")
            .setOccurredAt(System.currentTimeMillis())
            .setSessionId("android-test-session-2")
            .setScene("online_stack_flow_2")
            .setMessageStartNs(requestedStart)
            .setMessageEndNs(requestedEnd)
            .setThresholdNs(minOf(100_000_000L, requestedEnd - requestedStart))
            .setAttemptedSampleCount(40)
            .build()
        val secondJankResult = awaitJankExport(secondJankEvent)
        assertTrue("范围导出失败：${rangeResult.message}", rangeResult.isSuccess())
        assertTrue("全量导出失败：${allResult.message}", allResult.isSuccess())
        assertTrue("卡顿导出失败：${jankResult.message}", jankResult.isSuccess())
        assertTrue("卡顿复用失败：${reusedJankResult.message}", reusedJankResult.isSuccess())
        assertTrue("第二个卡顿导出失败：${secondJankResult.message}", secondJankResult.isSuccess())
        assertNotNull(rangeResult.artifact)
        assertNotNull(allResult.artifact)
        assertNotNull(secondJankResult.artifact)
        assertEquals(jankResult.artifact, reusedJankResult.artifact)
        assertEquals(jankResult.recordCount, reusedJankResult.recordCount)
        assertTrue(firstJankBytes.contentEquals(reusedJankResult.artifact!!.readBytes()))
        assertEquals(firstJankManifest, readManifest(reusedJankResult.artifact!!))
        assertTrue(jankResult.artifact!!.isFile)
        assertTrue(secondJankResult.artifact!!.isFile)
        assertTrue(jankResult.artifact != secondJankResult.artifact)
        assertTrue(jankResult.artifact!!.name != secondJankResult.artifact!!.name)
        assertTrue(rangeResult.recordCount > 0)
        assertTrue(allResult.recordCount > 0)
        assertTrue(rangeResult.actualRange.recordCount == rangeResult.recordCount)
        assertTrue(allResult.actualRange.recordCount == allResult.recordCount)
        assertTrue(rangeResult.actualRange.startElapsedRealtimeNanos >= requestedStart)
        assertTrue(rangeResult.actualRange.endElapsedRealtimeNanos <= requestedEnd)

        val outputDir = context.getExternalFilesDir(OUTPUT_DIRECTORY)
        assertNotNull("无法创建设备测试产物目录", outputDir)
        val rangeFile = File(outputDir!!, "range.rheatrace.zip")
        val allFile = File(outputDir, "all.rheatrace.zip")
        // 保留 SDK 生成的 <eventId>.rheajank.zip 文件名，验证完成后再复制固定别名
        // 供 Gradle 任务拉取，避免把契约中的幂等文件名覆盖成测试别名。
        val jankFile = File(outputDir, "${jankEvent.eventId}.rheajank.zip")
        val secondJankFile = File(outputDir, "${secondJankEvent.eventId}.rheajank.zip")
        val jankPullFile = File(outputDir, "jank.rheajank.zip")
        rangeResult.artifact!!.copyTo(rangeFile, overwrite = true)
        allResult.artifact!!.copyTo(allFile, overwrite = true)
        jankResult.artifact!!.copyTo(jankFile, overwrite = true)
        val processId = validateStackArtifact(rangeFile, "RANGE", mainTid.get())
        assertEquals(processId,
            validateStackArtifact(allFile, "ALL", mainTid.get()))
        assertEquals(processId,
            validateJankArtifact(jankFile, jankEvent, mainTid.get()))
        secondJankResult.artifact!!.copyTo(secondJankFile, overwrite = true)
        assertEquals(processId,
            validateJankArtifact(secondJankFile, secondJankEvent, mainTid.get()))
        jankFile.copyTo(jankPullFile, overwrite = true)
    }

    private fun waitForAvailableRange(): RheaTrace3.BufferTimeRange {
        val deadline = SystemClock.elapsedRealtime() + 5_000L
        var range = RheaTrace3.getAvailableStackTimeRange()
        while (range.recordCount == 0 && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(25)
            range = RheaTrace3.getAvailableStackTimeRange()
        }
        return range
    }

    private fun awaitRangeExport(startNs: Long, endNs: Long): RheaTrace3.ExportResult {
        val resultRef = AtomicReference<RheaTrace3.ExportResult>()
        val done = CountDownLatch(1)
        var request = RheaTrace3.ExportRequestResult.BUSY
        val deadline = SystemClock.elapsedRealtime() + 10_000L
        while (request == RheaTrace3.ExportRequestResult.BUSY
            && SystemClock.elapsedRealtime() < deadline) {
            request = RheaTrace3.exportStackData(startNs, endNs) {
                resultRef.set(it)
                done.countDown()
            }
            if (request == RheaTrace3.ExportRequestResult.BUSY) {
                SystemClock.sleep(50)
            }
        }
        assertEquals(RheaTrace3.ExportRequestResult.ACCEPTED, request)
        assertTrue("范围导出回调超时", done.await(30, TimeUnit.SECONDS))
        return requireResult(resultRef.get())
    }

    private fun awaitAllExport(): RheaTrace3.ExportResult {
        val resultRef = AtomicReference<RheaTrace3.ExportResult>()
        val done = CountDownLatch(1)
        var request = RheaTrace3.ExportRequestResult.BUSY
        val deadline = SystemClock.elapsedRealtime() + 10_000L
        while (request == RheaTrace3.ExportRequestResult.BUSY
            && SystemClock.elapsedRealtime() < deadline) {
            request = RheaTrace3.exportAllStackData {
                resultRef.set(it)
                done.countDown()
            }
            if (request == RheaTrace3.ExportRequestResult.BUSY) {
                SystemClock.sleep(50)
            }
        }
        assertEquals(RheaTrace3.ExportRequestResult.ACCEPTED, request)
        assertTrue("全量导出回调超时", done.await(30, TimeUnit.SECONDS))
        return requireResult(resultRef.get())
    }

    private fun awaitJankExport(event: RheaTrace3.JankEvent): RheaTrace3.ExportResult {
        val resultRef = AtomicReference<RheaTrace3.ExportResult>()
        val done = CountDownLatch(1)
        var request = RheaTrace3.ExportRequestResult.BUSY
        val deadline = SystemClock.elapsedRealtime() + 10_000L
        while (request == RheaTrace3.ExportRequestResult.BUSY
            && SystemClock.elapsedRealtime() < deadline) {
            request = RheaTrace3.exportJankTrace(event) {
                resultRef.set(it)
                done.countDown()
            }
            if (request == RheaTrace3.ExportRequestResult.BUSY) {
                SystemClock.sleep(50)
            }
        }
        assertEquals(RheaTrace3.ExportRequestResult.ACCEPTED, request)
        assertTrue("卡顿导出回调超时", done.await(30, TimeUnit.SECONDS))
        return requireResult(resultRef.get())
    }

    private fun requireResult(result: RheaTrace3.ExportResult?): RheaTrace3.ExportResult {
        assertNotNull("导出回调没有结果", result)
        return result!!
    }

    private fun validateStackArtifact(
        file: File, expectedSelection: String, mainTid: Int
    ): String {
        assertTrue("产物不存在：$file", file.isFile)
        return ZipFile(file).use { zip ->
            val names = mutableSetOf<String>()
            val entries: Enumeration<*> = zip.entries()
            while (entries.hasMoreElements()) {
                names.add((entries.nextElement() as java.util.zip.ZipEntry).name)
            }
            assertEquals(
                setOf("manifest.json", "sampling.bin", "sampling-mapping.bin"),
                names
            )
            val manifest = JSONObject(
                zip.getInputStream(zip.getEntry("manifest.json"))
                    .bufferedReader(Charsets.UTF_8).use { it.readText() }
            )
            assertEquals(1, manifest.getInt("schemaVersion"))
            assertEquals("RHEA_STACK", manifest.getString("artifactType"))
            assertEquals(expectedSelection, manifest.getString("selectionType"))
            assertEquals("ELAPSED_REALTIME_NANOS", manifest.getString("clock"))
            assertTrue(manifest.getInt("recordCount") > 0)
            val processId = manifest.getString("processId")
            assertUuidV4(processId)
            assertEquals(PROCESS_ID, processId)
            assertEquals("main", manifest.getString("threadScope"))
            assertSamplingIdentity(zip, processId, mainTid)
            assertFileInfo(zip, "sampling.bin", manifest.getJSONObject("files"))
            assertFileInfo(zip, "sampling-mapping.bin", manifest.getJSONObject("files"))
            processId
        }
    }

    private fun validateJankArtifact(
        file: File, event: RheaTrace3.JankEvent, mainTid: Int
    ): String {
        assertTrue("卡顿产物不存在：$file", file.isFile)
        assertEquals("${event.eventId}.rheajank.zip", file.name)
        return ZipFile(file).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toSet()
            assertEquals(
                setOf("manifest.json", "sampling.bin", "sampling-mapping.bin"),
                names
            )
            val manifest = JSONObject(
                zip.getInputStream(zip.getEntry("manifest.json"))
                    .bufferedReader(Charsets.UTF_8).use { it.readText() }
            )
            assertEquals(3, manifest.getInt("schemaVersion"))
            assertEquals("RHEA_JANK", manifest.getString("artifactType"))
            assertEquals(event.eventId, manifest.getString("eventId"))
            assertEquals(event.occurredAt, manifest.getLong("occurredAt"))
            assertEquals(event.sessionId, manifest.getString("sessionId"))
            assertEquals("device-anonymous-online-test",
                manifest.getString("anonymousDeviceId"))
            assertEquals(context.packageName, manifest.getString("packageName"))
            assertEquals("1.0", manifest.getString("appVersion"))
            assertEquals(1L, manifest.getLong("versionCode"))
            assertEquals("app-online-test-build", manifest.getString("buildId"))
            assertEquals("test", manifest.getString("environment"))
            assertEquals("instrumentation", manifest.getString("channel"))
            assertEquals(event.scene, manifest.getString("scene"))
            assertEquals(event.messageStartNs, manifest.getLong("messageStartNs"))
            assertEquals(event.messageEndNs, manifest.getLong("messageEndNs"))
            assertEquals(event.thresholdNs, manifest.getLong("thresholdNs"))
            assertEquals(5_000_000L, manifest.getLong("minSampleIntervalNs"))
            assertEquals(event.attemptedSampleCount,
                manifest.getLong("attemptedSampleCount"))
            val processId = manifest.getString("processId")
            assertUuidV4(processId)
            assertEquals(PROCESS_ID, processId)
            assertEquals("main", manifest.getString("threadScope"))
            assertSamplingIdentity(zip, processId, mainTid)
            assertTrue(manifest.getString("osVersion").isNotBlank())
            assertTrue(manifest.getString("deviceModel").isNotBlank())
            assertEquals(23, manifest.length())
            assertFileInfo(zip, "sampling.bin", manifest.getJSONObject("files"))
            assertFileInfo(zip, "sampling-mapping.bin", manifest.getJSONObject("files"))
            processId
        }
    }

    private fun assertUuidV4(value: String) {
        assertTrue(
            "不是 canonical UUID v4：$value",
            UUID_V4.matches(value)
        )
        val uuid = UUID.fromString(value)
        assertEquals(4, uuid.version())
        assertEquals(2, uuid.variant())
        assertEquals(value, uuid.toString())
    }

    private fun assertSamplingIdentity(zip: ZipFile, processId: String, mainTid: Int) {
        val sampling = zip.getInputStream(zip.getEntry("sampling.bin"))
            .use { it.readBytes() }
        val buffer = ByteBuffer.wrap(sampling).order(ByteOrder.LITTLE_ENDIAN)
        assertTrue("sampling header 不完整", buffer.remaining() >= 28)
        val version = buffer.getInt(8)
        val recordCount = buffer.getInt(20)
        buffer.position(24)
        val extraLength = buffer.int
        assertTrue("sampling extra 长度无效", extraLength > 0)
        val extraBytes = ByteArray(extraLength)
        buffer.get(extraBytes)
        val extra = JSONObject(String(extraBytes, Charsets.UTF_8))
        assertEquals(processId, extra.getString("processId"))
        assertEquals("main", extra.getString("threadScope"))

        val tids = mutableSetOf<Int>()
        repeat(recordCount) {
            val type = buffer.short.toInt() and 0xffff
            tids.add(buffer.short.toInt())
            buffer.int
            repeat(4) { buffer.long }
            if (type == 15) {
                buffer.long
            }
            if (version >= 4) {
                repeat(2) { buffer.long }
            }
            if (version >= 5) {
                repeat(3) { buffer.int }
            }
            val savedDepth = buffer.int
            buffer.int
            buffer.position(buffer.position() + savedDepth * 8)
        }
        assertEquals(setOf(mainTid), tids)
    }

    private fun readManifest(file: File): String {
        ZipFile(file).use { zip ->
            return zip.getInputStream(zip.getEntry("manifest.json"))
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
    }

    private fun assertFileInfo(zip: ZipFile, name: String, files: JSONObject) {
        val entry = zip.getEntry(name)
        assertNotNull(entry)
        val bytes = zip.getInputStream(entry!!).use { it.readBytes() }
        val info = files.getJSONObject(name.removeSuffix(".bin"))
        assertEquals(bytes.size.toLong(), info.getLong("size"))
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }
        assertEquals(digest, info.getString("sha256"))
    }

    companion object {
        private const val OUTPUT_DIRECTORY = "rhea-online-stack-test"
        private const val PROCESS_ID = "11111111-1111-4111-8111-111111111111"
        private const val CONFLICT_PROCESS_ID = "22222222-2222-4222-8222-222222222222"
        private val UUID_V4 = Regex(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}"
        )
    }
}
