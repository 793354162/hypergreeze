package dev.hypergreeze.data

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import dev.hypergreeze.core.Keys

/**
 * Read-only downlink for the app -> `system_server` direction.
 *
 * Why this exists: on some HyperOS builds `Settings.System.putString` returns `false` for a
 * third-party app even though `Settings.System.canWrite()` is `true`, so the Settings channel
 * cannot be trusted as the only way to hand a policy over. The app owns this data (its own
 * SharedPreferences), so serving it through a provider has no permission dependency at all.
 *
 * Access control:
 *  - only `system` (uid 1000), `root` (0) and our own uid may read;
 *  - every write verb throws: the provider is read-only by construction, so exporting it cannot
 *    become a way to change policy.
 */
class PolicyProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val cursor = MatrixCursor(arrayOf(COLUMN))
        val caller = Binder.getCallingUid()
        val selfUid = context?.applicationInfo?.uid ?: -1
        if (caller != 1000 && caller != 0 && caller != selfUid) {
            // Empty cursor, not an exception: a probe from an unrelated app must not look like a
            // broken channel to us.
            return cursor
        }
        val key = when (uri.path) {
            "/policy" -> Keys.PREF_POLICY
            "/command" -> Keys.PREF_COMMAND
            "/ping" -> Keys.PREF_PING
            else -> return cursor
        }
        val prefs = context?.getSharedPreferences(Keys.PREFS, 0) ?: return cursor
        cursor.addRow(arrayOf(prefs.getString(key, null)))
        return cursor
    }

    override fun getType(uri: Uri): String = "text/plain"

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("HyperGreeze policy channel is read-only")

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("HyperGreeze policy channel is read-only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("HyperGreeze policy channel is read-only")

    private companion object {
        const val COLUMN = "value"
    }
}
