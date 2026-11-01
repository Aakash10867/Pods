package com.aakash.pods

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException
import java.io.OutputStream

object Downloads {

    fun cleanName(raw: String): String =
        raw.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().take(120).ifEmpty { "download" }

    /** Saves a file through [write] and returns a human-readable location. */
    fun save(ctx: Context, fileName: String, mime: String?, write: (OutputStream) -> Unit): String {
        val name = cleanName(fileName)
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime ?: "application/octet-stream")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = ctx.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Could not create the file")
            try {
                val out = resolver.openOutputStream(uri) ?: throw IOException("Could not open the file")
                out.use { write(it) }
                val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                resolver.update(uri, done, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            return "Downloads/$name"
        }
        val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: ctx.filesDir
        var f = File(dir, name)
        var n = 1
        while (f.exists()) {
            f = File(dir, "$n-$name")
            n++
        }
        f.outputStream().use { write(it) }
        return f.absolutePath
    }
}
