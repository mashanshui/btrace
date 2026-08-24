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
import java.security.MessageDigest
import java.util.Enumeration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
        val result = RheaTrace3.initOnline(
            application,
            RheaTrace3.OnlineTraceConfig.builder()
                .setBufferSizeBytes(1024 * 1024)
                .setMinSampleIntervalMs(5)
                .setDiskQuotaBytes(8L * 1024L * 1024L)
                .setMaxArtifactBytes(4L * 1024L * 1024L)
                .setMappingId("app-online-test")
                .build()
        )
        assertTrue(
            "线上初始化失败：$result",
            result == RheaTrace3.InitResult.STARTED
                    || result == RheaTrace3.InitResult.ALREADY_STARTED
        )
    }

    @After
    fun tearDown() {
        RheaTrace3.stopOnlineTracing()
    }

    @Test
    fun exportRangeAndAllArtifacts_canBeParsedByProcessor() {
        val captureDone = CountDownLatch(1)
        val mainHandler = Handler(Looper.getMainLooper())
        mainHandler.post {
            try {
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
        assertTrue("范围导出失败：${rangeResult.message}", rangeResult.isSuccess())
        assertTrue("全量导出失败：${allResult.message}", allResult.isSuccess())
        assertNotNull(rangeResult.artifact)
        assertNotNull(allResult.artifact)
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
        rangeResult.artifact!!.copyTo(rangeFile, overwrite = true)
        allResult.artifact!!.copyTo(allFile, overwrite = true)
        validateArtifact(rangeFile, "RANGE")
        validateArtifact(allFile, "ALL")
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
        val request = RheaTrace3.exportStackData(startNs, endNs) {
            resultRef.set(it)
            done.countDown()
        }
        assertEquals(RheaTrace3.ExportRequestResult.ACCEPTED, request)
        assertTrue("范围导出回调超时", done.await(30, TimeUnit.SECONDS))
        return requireResult(resultRef.get())
    }

    private fun awaitAllExport(): RheaTrace3.ExportResult {
        val resultRef = AtomicReference<RheaTrace3.ExportResult>()
        val done = CountDownLatch(1)
        val request = RheaTrace3.exportAllStackData {
            resultRef.set(it)
            done.countDown()
        }
        assertEquals(RheaTrace3.ExportRequestResult.ACCEPTED, request)
        assertTrue("全量导出回调超时", done.await(30, TimeUnit.SECONDS))
        return requireResult(resultRef.get())
    }

    private fun requireResult(result: RheaTrace3.ExportResult?): RheaTrace3.ExportResult {
        assertNotNull("导出回调没有结果", result)
        return result!!
    }

    private fun validateArtifact(file: File, expectedSelection: String) {
        assertTrue("产物不存在：$file", file.isFile)
        ZipFile(file).use { zip ->
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
            assertFileInfo(zip, "sampling.bin", manifest.getJSONObject("files"))
            assertFileInfo(zip, "sampling-mapping.bin", manifest.getJSONObject("files"))
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
    }
}
