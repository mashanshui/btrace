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
package rhea.sample.android.app

import android.app.Application
import android.content.Context
import android.util.Log
import com.bytedance.rheatrace.RheaTrace3
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class App : Application() {
    companion object {
        private const val TAG = "RheaTrace:SampleApp"
        private const val ONLINE_BUFFER_SIZE_BYTES = 4 * 1024 * 1024
        private const val ONLINE_SAMPLE_INTERVAL_MS = 5L
        private const val ONLINE_PRE_ROLL_MS = 2_000L

        // 示例应用使用较短的冷却时间，便于在设备上重复验证；线上应按业务频率设置。
        const val ONLINE_DUMP_COOLDOWN_MS = 5_000L
        private const val ONLINE_DISK_QUOTA_BYTES = 20L * 1024L * 1024L
        private const val ONLINE_ARTIFACT_TTL_MS = 24L * 60L * 60L * 1000L
    }

    private val executor = Executors.newCachedThreadPool()
    private val latch = CountDownLatch(4)

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        if (base != null) {
            initOnlineTracing(base)
        }
        initSdks()
        latch.await()
    }

    private fun initOnlineTracing(context: Context) {
        val config = RheaTrace3.OnlineConfig.Builder()
            .setBufferSizeBytes(ONLINE_BUFFER_SIZE_BYTES)
            .setMinSampleIntervalMs(ONLINE_SAMPLE_INTERVAL_MS)
            .setPreRollMs(ONLINE_PRE_ROLL_MS)
            .setDumpCooldownMs(ONLINE_DUMP_COOLDOWN_MS)
            .setDiskQuotaBytes(ONLINE_DISK_QUOTA_BYTES)
            .setArtifactTtlMs(ONLINE_ARTIFACT_TTL_MS)
            .setForegroundOnly(true)
            .setEnabled(true)
            .setMappingId("rhea-sample-app-1.0")
            .build()
        val result = RheaTrace3.initOnline(context, config)
        Log.i(TAG, "online tracing init result=$result, cooldownMs=${config.getDumpCooldownMs()}")
    }

    private fun initSdks() {
        initCrashSdk()
        initImageLoaderAsync()
        initVideoDecoderAsyc()
        initVideoPlayerAsync()
        initRouterAsync()
        initNetwork()
    }

    private fun initRouterAsync() {
        executor.execute {
            fibonacci(12)
            latch.countDown()
        }
    }

    private fun initVideoPlayerAsync() {
        executor.execute {
            fibonacci(15)
            latch.countDown()
        }
    }

    private fun initVideoDecoderAsyc() {
        executor.execute {
            fibonacci(14)
            latch.countDown()
        }
    }

    private fun initImageLoaderAsync() {
        executor.execute {
            fibonacci(20)
            latch.countDown()
        }
    }

    private fun initNetwork() {
        println(fibonacci(10))
    }

    private fun initCrashSdk() {
        println(fibonacci(12))
    }

    private fun fibonacci(n: Int): Int {
        RheaTrace3.captureStackTrace(false)
        if (n == 0) return 0
        return if (n == 1) 1 else fibonacci(n - 1) + fibonacci(n - 2)
    }

    override fun onCreate() {
        super.onCreate()
        printApplicationName(this.javaClass.name)
    }

    private fun printApplicationName(appName: String) {
        Log.d("RheaTrace", appName)
    }
}
