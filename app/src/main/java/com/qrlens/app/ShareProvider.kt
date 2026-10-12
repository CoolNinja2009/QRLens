package com.qrlens.app

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/** Minimal read-only provider so viewer apps can open the files we generate from QR content. */
class ShareProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    private fun fileFor(uri: Uri): File {
        val ctx = context ?: throw FileNotFoundException()
        val name = uri.lastPathSegment ?: throw FileNotFoundException()
        val dir = File(ctx.cacheDir, DIR).canonicalFile
        val f = File(dir, name).canonicalFile
        if (f.parentFile != dir || !f.exists()) throw FileNotFoundException(uri.toString())
        return f
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val f = fileFor(uri)
        val cols = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val c = MatrixCursor(cols, 1)
        c.addRow(arrayOf<Any>(f.name, f.length()))
        return c
    }

    override fun getType(uri: Uri): String {
        val name = uri.lastPathSegment ?: return "application/octet-stream"
        return when (name.substringAfterLast('.', "").lowercase()) {
            "vcf" -> "text/x-vcard"
            "ics" -> "text/calendar"
            "txt" -> "text/plain"
            "png" -> "image/png"
            else -> "application/octet-stream"
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        return ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val DIR = "shared"
        fun uriFor(ctx: Context, f: File): Uri =
            Uri.parse("content://${ctx.packageName}.files/${Uri.encode(f.name)}")
    }
}
