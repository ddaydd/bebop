package io.dayd.bebop

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FileLogger {

    private var writer: PrintWriter? = null
    private val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    var file: File? = null; private set

    @Synchronized
    fun init(context: Context) {
        if (writer != null) return
        val dir = context.getExternalFilesDir(null) ?: return
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        file = File(dir, "bebop-log-$ts.txt")
        writer = PrintWriter(FileWriter(file!!, true), true)
        i("FileLogger", "=== démarré — ${file!!.absolutePath} ===")
    }

    @Synchronized
    fun i(tag: String, msg: String) {
        Log.i(tag, msg)
        writer?.println("${sdf.format(Date())} I/$tag: $msg")
    }

    @Synchronized
    fun w(tag: String, msg: String) {
        Log.w(tag, msg)
        writer?.println("${sdf.format(Date())} W/$tag: $msg")
    }

    @Synchronized
    fun d(tag: String, msg: String) {
        Log.d(tag, msg)
        writer?.println("${sdf.format(Date())} D/$tag: $msg")
    }

    @Synchronized
    fun close() {
        writer?.close()
        writer = null
    }
}
