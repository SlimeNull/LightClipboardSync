package com.lightclipboardsync.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

sealed class ClipboardContent(val type: String, val mimeType: String, val bytes: ByteArray) {
    class Text(value: String) : ClipboardContent("text", "text/plain; charset=utf-8", value.toByteArray(Charsets.UTF_8))
    class Image(bytes: ByteArray, mimeType: String) : ClipboardContent("image", mimeType, bytes)
}

object ClipboardContentReader {
    private const val MAX_BYTES = 25 * 1024 * 1024

    fun readCurrent(context: Context): ClipboardContent? {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        return clipboard.primaryClip?.let { read(context, it) }
    }

    fun read(context: Context, clip: ClipData): ClipboardContent? {
        if (clip.itemCount == 0) return null
        val item = clip.getItemAt(0)
        val uri = item.uri
        if (uri != null) {
            val detectedMime = context.contentResolver.getType(uri)
            val mime = detectedMime?.takeIf { it.startsWith("image/") }
                ?: (0 until clip.description.mimeTypeCount)
                    .map { clip.description.getMimeType(it) }
                    .firstOrNull { it.startsWith("image/") }
            if (mime?.startsWith("image/") == true) {
                val data = context.contentResolver.openInputStream(uri)?.use { stream ->
                    val output = ByteArrayOutputStream()
                    val chunk = ByteArray(64 * 1024)
                    while (true) {
                        val count = stream.read(chunk)
                        if (count < 0) break
                        if (output.size() + count > MAX_BYTES) throw IOException("图片超过 25 MiB")
                        output.write(chunk, 0, count)
                    }
                    output.toByteArray()
                } ?: return null
                if (mime in listOf("image/png", "image/jpeg", "image/webp")) {
                    return ClipboardContent.Image(data, mime)
                }
                val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size) ?: return null
                val png = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, png)
                if (png.size() <= MAX_BYTES) return ClipboardContent.Image(png.toByteArray(), "image/png")
                val jpeg = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, jpeg)
                if (jpeg.size() <= MAX_BYTES) return ClipboardContent.Image(jpeg.toByteArray(), "image/jpeg")
                throw IOException("图片超过 25 MiB")
            }
        }
        val text = item.text?.toString() ?: item.coerceToText(context)?.toString()
        return text?.let { ClipboardContent.Text(it) }
    }
}

object RemoteClipboardWriter {
    fun writeText(context: Context, value: String) {
        context.getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("LightClipboardSync", value))
    }

    fun writeImage(context: Context, bytes: ByteArray, mimeType: String, id: Long) {
        val extension = when (mimeType.substringBefore(';')) {
            "image/jpeg" -> "jpg"
            "image/webp" -> "webp"
            else -> "png"
        }
        val directory = File(context.cacheDir, "clipboard").apply { mkdirs() }
        val file = File(directory, "remote_$id.$extension")
        file.writeBytes(bytes)
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        context.getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newUri(context.contentResolver, "LightClipboardSync", uri))
        directory.listFiles()?.filter { it != file && it.lastModified() < System.currentTimeMillis() - 86_400_000 }
            ?.forEach { it.delete() }
    }
}
