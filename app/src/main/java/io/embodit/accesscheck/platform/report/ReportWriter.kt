package io.embodit.accesscheck.platform.report

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import io.embodit.accesscheck.platform.AcLog
import java.io.File

/**
 * Saves the report as UTF-8 text in the public Download/AccessCheck folder through MediaStore (no
 * storage permission on API 29+; design `rules.report`). Whether the PICO file manager lists it is a
 * `deviceOnly` check of the design. When MediaStore refuses, the app's own external documents folder
 * takes the file so that nothing is lost.
 */
object ReportWriter {
    const val FOLDER = "AccessCheck"

    /** @return the path to show the user ("Download/AccessCheck/<name>" or an absolute fallback path). */
    fun write(context: Context, fileName: String, text: String): Result<String> {
        val viaStore =
            runCatching {
                val resolver = context.contentResolver
                val values =
                    ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER)
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("insert returned null")
                resolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) } ?: error("no output stream")
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER/$fileName"
            }
        if (viaStore.isSuccess) {
            AcLog.i("REPORT saved mediastore path=${viaStore.getOrNull()}")
            return viaStore
        }
        AcLog.w("REPORT mediastore failed: ${viaStore.exceptionOrNull()}")
        return runCatching {
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), FOLDER)
            dir.mkdirs()
            val file = File(dir, fileName)
            file.writeText(text, Charsets.UTF_8)
            AcLog.i("REPORT saved fallback path=${file.absolutePath}")
            file.absolutePath
        }
    }
}
