package com.pkos.app

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import org.robolectric.Robolectric
import java.io.File

/** Stands in for another app's FileProvider: shares files from the test cache dir as content:// URIs. */
class TestShareProvider : ContentProvider() {
    private fun file(uri: Uri) = File(context!!.cacheDir, uri.lastPathSegment!!)
    override fun onCreate() = true
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor =
        MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply { addRow(arrayOf<Any?>(uri.lastPathSegment, file(uri).length())) }
    override fun getType(uri: Uri): String? = if (uri.lastPathSegment!!.endsWith(".png")) "image/png" else null
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor = ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0

    companion object {
        const val AUTHORITY = "com.example.sender.files"
        fun install() { Robolectric.setupContentProvider(TestShareProvider::class.java, AUTHORITY) }
        fun uri(f: File): Uri = Uri.parse("content://$AUTHORITY/shared/${f.name}")
    }
}
