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

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import com.bytedance.rheatrace.RheaTrace3
import rhea.sample.android.R

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "RheaTrace:SampleApp"
        private const val TEST_JANK_DURATION_MS = 80L
        private const val TEST_DUMP_DELAY_MS = 40L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var onlineTestRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Tasks.doTasks()
        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById(R.id.toolbar))

        findViewById<FloatingActionButton>(R.id.fab).apply {
            contentDescription = getString(R.string.online_trace_test)
            setOnClickListener { view -> runOnlineTraceTest(view) }
        }
    }

    /** 生成一次可重复的主线程卡顿，并请求线上采样产物导出。 */
    private fun runOnlineTraceTest(view: View) {
        if (onlineTestRunning) {
            Snackbar.make(view, R.string.online_trace_test_running, Snackbar.LENGTH_SHORT).show()
            return
        }
        onlineTestRunning = true
        val eventId = "manual-${SystemClock.uptimeMillis()}"
        Snackbar.make(view, R.string.online_trace_test_started, Snackbar.LENGTH_SHORT).show()

        // 先写入一个基线样本，避免初始化后立即导出时 startToken == endToken。
        RheaTrace3.captureStackTrace(false)
        mainHandler.post {
            val eventStart = SystemClock.elapsedRealtimeNanos()
            simulateMainThreadJank()
            val eventEnd = SystemClock.elapsedRealtimeNanos()
            // 卡顿结束后再写入一个样本，确保环形缓冲区有新记录。
            RheaTrace3.captureStackTrace(false)
            mainHandler.postDelayed({
                requestOnlineDump(view, eventId, eventStart, eventEnd)
            }, TEST_DUMP_DELAY_MS)
        }
    }

    private fun simulateMainThreadJank() {
        val deadline = SystemClock.uptimeMillis() + TEST_JANK_DURATION_MS
        var accumulator = 0L
        while (SystemClock.uptimeMillis() < deadline) {
            accumulator = accumulator * 31L + 1L
        }
        if (accumulator == Long.MIN_VALUE) {
            throw AssertionError("unreachable")
        }
    }

    private fun requestOnlineDump(view: View, eventId: String, startNanos: Long, endNanos: Long) {
        val event = RheaTrace3.JankEvent.builder(eventId, startNanos, endNanos)
            .setScene("sample-main-thread")
            .setReason("manual-fab-test")
            .build()
        val request = RheaTrace3.dumpJankTrace(event) { result ->
            runOnUiThread {
                onlineTestRunning = false
                val artifactPath = result.getArtifact()?.absolutePath ?: ""
                val message = "导出${result.getStatus()}：${result.getMessage()} $artifactPath"
                Snackbar.make(view, message, Snackbar.LENGTH_LONG).show()
                android.util.Log.i(TAG, message)
            }
        }
        android.util.Log.i(TAG, "online dump request eventId=$eventId result=$request")
        if (request != RheaTrace3.DumpRequestResult.ACCEPTED) {
            onlineTestRunning = false
            Snackbar.make(view, "导出请求未接受：$request", Snackbar.LENGTH_LONG).show()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the menu; this adds items to the action bar if it is present.
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        return when (item.itemId) {
            R.id.action_settings -> true
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
    }
}
