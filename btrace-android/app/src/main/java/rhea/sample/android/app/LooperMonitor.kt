package rhea.sample.android.app

import android.os.Looper
import android.util.Log
import android.util.Printer

/**
 * @author mashanshui
 * @since 2026/2/5
 */
class LooperMonitor(looper: Looper) {
    companion object {
        private const val TAG = "LooperMonitor"
        val sMainMonitor = LooperMonitor(Looper.getMainLooper())
    }

    interface LooperListener {
        fun onMessageBegin(log: String)
        fun onMessageEnd(log: String)
    }

    private val mListeners = mutableListOf<LooperListener>()

    init {
        looper.setMessageLogging(LooperPrinter())
    }

    fun register(listener: LooperListener) {
        synchronized(mListeners) {
            mListeners.add(listener)
        }
    }

    fun unregister(listener: LooperListener) {
        synchronized(mListeners) {
            mListeners.remove(listener)
        }
    }

    inner class LooperPrinter : Printer {
        var isHasChecked: Boolean = false
        var isValid: Boolean = false
        override fun println(x: String) {
            if (!isHasChecked) {
                isValid = x[0] == '>' || x[0] == '<'
                isHasChecked = true
                if (!isValid) {
                    Log.e(TAG, "[println] Printer is inValid! x:$x")
                }
            }

            if (isValid) {
                dispatch(x[0] == '>', x)
            }
        }
    }

    private fun dispatch(isBegin: Boolean, log: String) {
//        Log.d(TAG, "dispatch: $isBegin $log")
        if (isBegin) {
            synchronized(mListeners) {
                for (listener in mListeners) {
                    listener.onMessageBegin(log)
                }
            }
        } else {
            synchronized(mListeners) {
                for (listener in mListeners) {
                    listener.onMessageEnd(log)
                }
            }
        }
    }
}