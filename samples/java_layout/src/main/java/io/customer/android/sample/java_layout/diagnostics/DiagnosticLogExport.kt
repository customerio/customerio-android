package io.customer.android.sample.java_layout.diagnostics

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import java.util.Locale

/**
 * Getting the files off the phone.
 *
 * A drive that produced perfect data and then lost it to a wiped device or a forgotten export is a
 * drive wasted, and drives are the expensive part. Three independent routes out, because which one
 * works depends on what is to hand at the end of a drive:
 *
 * - **Share sheet** (this type) — Drive, email, Nearby Share; no cable, works standing by the car.
 * - **`adb pull`** — the directory is under `getExternalFilesDir`, so no `run-as` and no root.
 * - **MTP over USB** — the same directory is visible in Finder or Explorer.
 *
 * Security is deliberately relaxed here. This is a sample app whose entire job is producing
 * diagnostics; none of it ships in the SDK.
 */
object DiagnosticLogExport {
    /**
     * Shares the newest log file.
     *
     * One file rather than the whole directory: `ACTION_SEND_MULTIPLE` is inconsistently handled by
     * receiving apps, and the newest file is the drive that just finished. Older ones come off with
     * `adb pull`.
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
