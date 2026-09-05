package io.customer.android.sample.java_layout.diagnostics

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import java.util.Locale

/**
 * Getting the files off the phone.
 */
object DiagnosticLogExport {
    /**
     * Shares the newest log file.
     */
    @JvmStatic
    fun share(activity: Activity) {
        val newest = DiagnosticLog.sessionFiles().firstOrNull()
        if (newest == null) {
            Toast.makeText(activity, "No diagnostic logs on disk yet.", Toast.LENGTH_SHORT).show()
            return
        }

        val uri: Uri = runCatching {
            FileProvider.getUriForFile(activity, "${activity.packageName}.diagnostics", newest)
        }.getOrElse {
            Toast.makeText(activity, "Could not share the diagnostic log.", Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/x-ndjson"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, newest.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(Intent.createChooser(intent, "Share diagnostic log"))
    }

    /**
     * A one-line summary, so it is obvious before setting off whether the sink is capturing
     * anything at all.
     */
    @JvmStatic
    fun statusSummary(): String {
        val files = DiagnosticLog.sessionFiles()
        val newest = files.firstOrNull() ?: return "No log files yet."
        val kilobytes = newest.length() / 1024.0
        return String.format(Locale.US, "%d file(s) · newest %s (%.1f KB)", files.size, newest.name, kilobytes)
    }
}
