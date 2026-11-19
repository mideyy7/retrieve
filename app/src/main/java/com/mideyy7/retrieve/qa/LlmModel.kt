package com.mideyy7.retrieve.qa

import android.content.Context
import com.google.android.play.core.aipacks.AiPackManager
import com.google.android.play.core.aipacks.AiPackManagerFactory
import com.google.android.play.core.aipacks.AiPackState
import com.google.android.play.core.aipacks.AiPackStateUpdateListener
import com.google.android.play.core.aipacks.model.AiPackStatus
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.io.File
import java.security.MessageDigest

/** Where the answering model is, as the Ask screen needs to know it. */
sealed interface ModelAvailability {
    data object Ready : ModelAvailability
    /** Not on the device; can be downloaded through Play ([sizeBytes] if Play said). */
    data class Downloadable(val sizeBytes: Long?) : ModelAvailability
    data class Downloading(val bytes: Long, val total: Long) : ModelAvailability
    /** Over 200 MB on mobile data, or a sideloaded build: Play needs the user's OK first. */
    data object NeedsConfirmation : ModelAvailability
    /** Neither the Play pack nor a development copy is available (e.g. a sideloaded build). */
    data class Unavailable(val reason: String) : ModelAvailability
}

/**
 * The answering model: Gemma 3 1B int4 for LiteRT-LM. Store builds get it as the on-demand
 * `gemma_pack` AI pack (Play for On-device AI); development builds can use a copy pushed with
 * scripts/push_llm.sh. Either way its SHA-256 is checked before it's loaded, once per file version.
 */
object LlmModel {
    const val FILE_NAME = "gemma3-1b-it-int4.litertlm"
    const val PACK = "gemma_pack"
    const val SHA256 = "1325ae366d31950f137c9c357b9fa89448b176d76998180c08ceaca78bba98be"
    const val SIZE_BYTES = 584_417_280L

    private fun packs(context: Context): AiPackManager = AiPackManagerFactory.getInstance(context)

    /** The Play pack's copy if it's installed (never cached across launches, as Play advises). */
    private fun packFile(context: Context): File? = runCatching {
        packs(context).getPackLocation(PACK)?.assetsPath()?.let { File(it, FILE_NAME) }?.takeIf { it.isFile }
    }.getOrNull()

    /** A development copy pushed over adb. */
    fun devFile(context: Context): File = File(context.getExternalFilesDir(null), "llm/$FILE_NAME")

    fun file(context: Context): File? = packFile(context) ?: devFile(context).takeIf { it.isFile && it.length() == SIZE_BYTES }

    fun isInstalled(context: Context): Boolean = file(context) != null

    /**
     * The model file if it's present and its SHA-256 matches. Hashing 584 MB takes a few seconds, so
     * a match is remembered for that exact file (path, size, modification time).
     */
    fun verifiedFile(context: Context): File? {
        val f = file(context) ?: return null
        val prefs = context.getSharedPreferences("llm", Context.MODE_PRIVATE)
        val id = "${f.absolutePath}|${f.length()}|${f.lastModified()}"
        if (prefs.getString("verified", null) == id) return f
        val digest = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        if (hex != SHA256) throw IllegalStateException("The answering model is damaged (checksum mismatch); download it again.")
        prefs.edit().putString("verified", id).apply()
        return f
    }

    /** Asks Play to download the pack. Progress arrives through [states]. */
    fun download(context: Context) {
        packs(context).fetch(listOf(PACK))
    }

    /** The pack's download state as it changes, mapped for the UI. */
    fun states(context: Context): Flow<ModelAvailability> = callbackFlow {
        if (isInstalled(context)) trySend(ModelAvailability.Ready)
        val manager = runCatching { packs(context) }.getOrNull()
        if (manager == null) {
            if (!isInstalled(context)) trySend(ModelAvailability.Unavailable("Downloads need the Play Store version of Retrieve."))
            awaitClose { }
            return@callbackFlow
        }
        val listener = AiPackStateUpdateListener { s -> if (s.name() == PACK) trySend(map(context, s)) }
        manager.registerListener(listener)
        if (!isInstalled(context)) {
            manager.getPackStates(listOf(PACK))
                .addOnSuccessListener { st -> st.packStates()[PACK]?.let { trySend(map(context, it)) } ?: trySend(ModelAvailability.Downloadable(null)) }
                .addOnFailureListener { trySend(ModelAvailability.Unavailable("Downloads need the Play Store version of Retrieve.")) }
        }
        awaitClose { manager.unregisterListener(listener) }
    }

    private fun map(context: Context, s: AiPackState): ModelAvailability = when (s.status()) {
        AiPackStatus.COMPLETED -> if (isInstalled(context)) ModelAvailability.Ready else ModelAvailability.Downloadable(s.totalBytesToDownload())
        AiPackStatus.PENDING, AiPackStatus.DOWNLOADING, AiPackStatus.TRANSFERRING ->
            ModelAvailability.Downloading(s.bytesDownloaded(), s.totalBytesToDownload().takeIf { it > 0 } ?: SIZE_BYTES)
        AiPackStatus.WAITING_FOR_WIFI, AiPackStatus.REQUIRES_USER_CONFIRMATION -> ModelAvailability.NeedsConfirmation
        AiPackStatus.FAILED -> ModelAvailability.Unavailable("The download failed (error ${s.errorCode()}). Try again on Wi-Fi.")
        else -> if (isInstalled(context)) ModelAvailability.Ready else ModelAvailability.Downloadable(s.totalBytesToDownload().takeIf { it > 0 })
    }

    /** Shows Play's "download over mobile data?" dialog; returns false if there was nothing to confirm. */
    fun confirm(context: Context, launcher: androidx.activity.result.ActivityResultLauncher<androidx.activity.result.IntentSenderRequest>): Boolean =
        runCatching { packs(context).showConfirmationDialog(launcher) }.getOrDefault(false)
}
