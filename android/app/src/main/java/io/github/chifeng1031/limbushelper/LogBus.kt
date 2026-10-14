package io.github.chifeng1031.limbushelper

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/** In-memory run log shown on the main screen; listeners are called on the main thread */
object LogBus {
    private const val MAX_LINES = 400
    private val lines = ArrayDeque<String>()
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun log(msg: String) {
        val line = "${fmt.format(Date())} $msg"
        Log.i("LimbusHelper", msg)
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
        main.post { listeners.forEach { it(line) } }
    }

    fun snapshot(): String = synchronized(lines) { lines.joinToString("\n") }

    fun listen(l: (String) -> Unit) = listeners.add(l)

    fun unlisten(l: (String) -> Unit) = listeners.remove(l)
}
