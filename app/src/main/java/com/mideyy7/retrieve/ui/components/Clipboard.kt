package com.mideyy7.retrieve.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build

/**
 * Copies [text]. Returns true when the app should confirm it itself: Android 13+ shows its own
 * "Copied" preview, so a second message there would be noise.
 */
fun copyText(context: Context, label: String, text: String): Boolean {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
    return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
}

/** The system share sheet with [text], e.g. a passage and where it's from, for a study group chat. */
fun shareText(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Opens the original file in another app (a full PDF reader). False if no app can open it. */
fun openOriginal(context: Context, uri: String, mime: String): Boolean {
    val view = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), mime)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        context.startActivity(view)
        true
    } catch (_: android.content.ActivityNotFoundException) {
        false
    }
}
