package rhea.sample.android

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bytedance.rheatrace.RheaTrace3

import org.junit.Test
import org.junit.runner.RunWith

import org.junit.Assert.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {

    @Test
    fun useAppContext() {
        // Context of the app under test.
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("rhea.sample.android", appContext.packageName)
    }

    @Test
    fun onlineTraceCanDumpArtifact() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val appContext = instrumentation.targetContext
        val config = RheaTrace3.OnlineConfig.Builder()
            .setBufferSizeBytes(4 * 1024 * 1024)
            .setMinSampleIntervalMs(10L)
            .setPreRollMs(2_000L)
            .setDumpCooldownMs(5_000L)
            .setDiskQuotaBytes(20L * 1024L * 1024L)
            .setArtifactTtlMs(24L * 60L * 60L * 1000L)
            .setForegroundOnly(true)
            .setEnabled(true)
            .setMappingId("rhea-instrumented-test")
            .build()
        val initResult = RheaTrace3.initOnline(appContext, config)
        assertTrue(
            "线上采集初始化失败：$initResult",
            initResult == RheaTrace3.OnlineInitResult.STARTED
                    || initResult == RheaTrace3.OnlineInitResult.ALREADY_STARTED
        )

        // 抓栈必须在目标进程主线程执行；两次调用间隔超过在线采样间隔。
        val startNanos = SystemClock.elapsedRealtimeNanos()
        instrumentation.runOnMainSync { RheaTrace3.captureStackTrace(false) }
        Thread.sleep(20L)
        instrumentation.runOnMainSync { RheaTrace3.captureStackTrace(false) }
        val endNanos = SystemClock.elapsedRealtimeNanos()

        val callbackLatch = CountDownLatch(1)
        var dumpResult: RheaTrace3.DumpResult? = null
        val event = RheaTrace3.JankEvent.builder(
            "instrumented-${SystemClock.uptimeMillis()}", startNanos, endNanos
        ).setScene("instrumented-test").setReason("online-api-smoke").build()
        val requestResult = RheaTrace3.dumpJankTrace(event) { result ->
            dumpResult = result
            callbackLatch.countDown()
        }
        assertEquals(RheaTrace3.DumpRequestResult.ACCEPTED, requestResult)
        assertTrue("线上产物导出超时", callbackLatch.await(10L, TimeUnit.SECONDS))
        assertNotNull(dumpResult)
        assertEquals(RheaTrace3.DumpStatus.SUCCESS, dumpResult?.getStatus())
        assertNotNull(dumpResult?.getArtifact())
        assertTrue(dumpResult?.getArtifact()?.isFile == true)
    }
}
