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
import android.os.SystemClock
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import com.bytedance.rheatrace.RheaTrace3
import com.bytedance.rheatrace.RheaTrace3.exportStackData
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import rhea.sample.android.R

private const val TAG = "MainActivity"
class MainActivity : AppCompatActivity() {
    private var messageStartNs: Long = 0
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Tasks.doTasks()
        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById(R.id.toolbar))
        LooperMonitor.sMainMonitor.register(object : LooperMonitor.LooperListener {
            override fun onMessageBegin(log: String) {
                messageStartNs = SystemClock.elapsedRealtimeNanos()
            }

            override fun onMessageEnd(log: String) {
                val endNs = SystemClock.elapsedRealtimeNanos()
                if (endNs > messageStartNs + 100000000) {
                    Log.e(TAG, "onMessageEnd: ")
                    val exportStackData =
                        RheaTrace3.exportStackData(messageStartNs, endNs, RheaTrace3.ExportCallback { result: RheaTrace3.ExportResult? ->
                            Log.e(TAG, "onMessageEnd: " + result?.artifact?.path)
                        })
                    Log.e(TAG, "onMessageEnd: "+exportStackData.name)
                }
            }
        })
        findViewById<FloatingActionButton>(R.id.fab).setOnClickListener { view ->
            var i = 0
            view.post {
                Log.e(TAG, "onCreate: 1")
                repeat(400000) {
                    i = ThreadTest().sdtdsf(i)
                }
                Log.e(TAG, "onCreate: 2")
            }
            Snackbar.make(view, "Replace with your own action", Snackbar.LENGTH_LONG)
                .setAction("Action", null).show()
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