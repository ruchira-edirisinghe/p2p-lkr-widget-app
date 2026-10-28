package dev.dfanso.lkrp2p.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Shares [csv] as a real .csv attachment. Putting a month of history in
 * EXTRA_TEXT overflows the binder transaction limit (about 1 MB, shared by the
 * whole process) and crashes with TransactionTooLargeException.
 */
internal suspend fun shareCsv(context: Context, fileName: String, subject: String, csv: suspend () -> String) {
    val file = withContext(Dispatchers.IO) {
        File(context.cacheDir, "exports").apply { mkdirs() }.resolve(fileName).apply { writeText(csv()) }
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/csv")
        .putExtra(Intent.EXTRA_SUBJECT, subject)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    // The chooser needs the grant too, to show a preview of the file.
    send.clipData = ClipData.newRawUri(fileName, uri)
    context.startActivity(Intent.createChooser(send, subject))
}
