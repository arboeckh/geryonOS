package io.github.arboeckh.geryon.nfc

import android.net.Uri
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import java.util.UUID

/** URI scheme written to geryon tags: geryon://tag/<id>. Must match the manifest intent filter. */
const val TAG_SCHEME = "geryon"
const val TAG_HOST = "tag"

data class TagScan(
    val uid: String,
    /** geryon tag id from geryon://tag/<id>, or null if the tag isn't a geryon tag. */
    val geryonId: String?,
    /** First URI found on the tag, if any. */
    val uri: String?,
    val source: Source,
    val at: Long = System.currentTimeMillis(),
) {
    enum class Source { LaunchedApp, ForegroundRead, Written }
}

fun ByteArray.toHex(): String = joinToString(":") { "%02X".format(it) }

fun geryonIdFrom(uri: Uri?): String? =
    uri?.takeIf { it.scheme == TAG_SCHEME && it.host == TAG_HOST }?.lastPathSegment

fun firstUri(message: NdefMessage?): Uri? =
    message?.records?.firstNotNullOfOrNull { it.toUri() }

/** Reads a tag delivered to the foreground reader callback. Uses the NDEF message cached at discovery. */
fun readTag(tag: Tag): TagScan {
    val uri = firstUri(Ndef.get(tag)?.cachedNdefMessage)
    return TagScan(
        uid = tag.id.toHex(),
        geryonId = geryonIdFrom(uri),
        uri = uri?.toString(),
        source = TagScan.Source.ForegroundRead,
    )
}

/**
 * Writes a fresh geryon://tag/<id> record plus an Android Application Record,
 * so tapping the tag always opens this app. Formats blank tags if needed.
 */
fun writeGeryonTag(
    tag: Tag,
    packageName: String,
    id: String = UUID.randomUUID().toString().substring(0, 8),
): Result<TagScan> = runCatching {
    val uri = Uri.Builder().scheme(TAG_SCHEME).authority(TAG_HOST).appendPath(id).build()
    val message = NdefMessage(
        arrayOf(
            NdefRecord.createUri(uri),
            NdefRecord.createApplicationRecord(packageName),
        )
    )

    val ndef = Ndef.get(tag)
    if (ndef != null) {
        ndef.use {
            it.connect()
            check(it.isWritable) { "Tag is read-only" }
            check(it.maxSize >= message.byteArrayLength) {
                "Tag too small (${it.maxSize} bytes, need ${message.byteArrayLength})"
            }
            it.writeNdefMessage(message)
        }
    } else {
        val formatable = NdefFormatable.get(tag) ?: error("Tag doesn't support NDEF")
        formatable.use {
            it.connect()
            it.format(message)
        }
    }

    TagScan(uid = tag.id.toHex(), geryonId = id, uri = uri.toString(), source = TagScan.Source.Written)
}
