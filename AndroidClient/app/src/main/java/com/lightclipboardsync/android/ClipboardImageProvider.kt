package com.lightclipboardsync.android

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

class ClipboardImageProvider : ContentProvider() {
    override fun onCreate() = true

    private fun file(uri: Uri): File {
        val name = uri.lastPathSegment ?: throw FileNotFoundException()
        if (!name.matches(Regex("[1-9][0-9]*\\.(png|jpg|webp)")) || uri.pathSegments.size != 1) {
            throw FileNotFoundException()
        }
        val directory = File(requireNotNull(context).cacheDir, "clipboard").apply { mkdirs() }
        return File(directory, "remote_$name")
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r" && Binder.getCallingUid() !in listOf(Process.myUid(), Process.SYSTEM_UID)) {
            throw SecurityException("Only the clipboard sync owner may write images")
        }
        val target = file(uri)
        if (mode != "r") {
            target.parentFile?.listFiles()?.filter {
                it != target && it.lastModified() < System.currentTimeMillis() - 86_400_000
            }?.forEach { it.delete() }
        }
        return ParcelFileDescriptor.open(target, ParcelFileDescriptor.parseMode(mode))
    }

    override fun getType(uri: Uri): String = when (uri.lastPathSegment?.substringAfterLast('.')) {
        "jpg" -> "image/jpeg"
        "webp" -> "image/webp"
        else -> "image/png"
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val target = file(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply {
            addRow(columns.map { column -> when (column) {
                OpenableColumns.DISPLAY_NAME -> target.name
                OpenableColumns.SIZE -> target.length()
                else -> null
            } })
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()
}
