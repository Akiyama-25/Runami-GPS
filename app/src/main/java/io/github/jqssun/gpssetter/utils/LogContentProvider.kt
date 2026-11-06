package io.github.jqssun.gpssetter.utils

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle

class LogContentProvider : ContentProvider() {

    companion object {
        const val METHOD_APPEND_LOGS = "append_logs"
        const val KEY_OK = "ok"
    }

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method == METHOD_APPEND_LOGS && extras != null) {
            AppLogger.handleIncomingLogBundle(extras)
            return Bundle().apply {
                putBoolean(KEY_OK, true)
            }
        }
        return null
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0
}
