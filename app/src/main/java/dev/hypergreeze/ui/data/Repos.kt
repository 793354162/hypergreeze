package dev.hypergreeze.ui.data

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.provider.Settings
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import dev.hypergreeze.core.Command
import dev.hypergreeze.core.CommandCodec
import dev.hypergreeze.core.EventRecord
import dev.hypergreeze.core.Heartbeat
import dev.hypergreeze.core.Keys
import dev.hypergreeze.core.Policy
import dev.hypergreeze.core.PolicyCodec
import dev.hypergreeze.core.TelemetryCodec

/**
 * The app half of the app -> hook channel.
 *
 * Primary path is the app's own SharedPreferences served by [dev.hypergreeze.data.PolicyProvider]:
 * it needs no permission and cannot be blocked by a ROM's SettingsProvider. Every write is also
 * mirrored into `Settings.System` on a best-effort basis (some ROMs accept it, and it keeps
 * `adb shell settings get` useful), but the mirror is never what we report as success.
 *
 * The hook -> app direction (heartbeat / events / logs / journal) is read-only from here and still
 * comes from `Settings.System`, where `system_server` is never blocked.
 */
class SettingsGateway(private val context: Context) {

    private val prefs by lazy { context.getSharedPreferences(Keys.PREFS, Context.MODE_PRIVATE) }

    fun canWrite(): Boolean = try {
        Settings.System.canWrite(context)
    } catch (_: Throwable) {
        false
    }

    fun writePermissionIntent(): Intent = Intent(
        Settings.ACTION_MANAGE_WRITE_SETTINGS,
        Uri.parse("package:${context.packageName}"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun readRaw(key: String): String? = try {
        Settings.System.getString(context.contentResolver, key)
    } catch (_: Throwable) {
        null
    }

    /** Settings mirror only. Used for the read-only telemetry keys and as a legacy fallback. */
    fun writeRaw(key: String, value: String?): Boolean {
        if (!canWrite()) return false
        return try {
            Settings.System.putString(context.contentResolver, key, value)
        } catch (_: Throwable) {
            false
        }
    }

    // ------------------------------------------------------------------ policy / command

    private fun writePref(key: String, value: String, uri: String): Boolean {
        val ok = try {
            prefs.edit().putString(key, value).commit()
        } catch (_: Throwable) {
            false
        }
        if (ok) {
            try {
                context.contentResolver.notifyChange(Uri.parse(uri), null)
            } catch (_: Throwable) {
                // The hook side also polls every 5s, so a lost notification only costs latency.
            }
        }
        return ok
    }

    /**
     * Policy as the app sees it. Prefs first (the authoritative copy), Settings as a migration
     * fallback for installs that were configured before the provider channel existed.
     */
    fun readPolicy(): Policy {
        val fromPrefs = prefs.getString(Keys.PREF_POLICY, null)
        val raw = if (!fromPrefs.isNullOrBlank()) fromPrefs else readRaw(Keys.POLICY)
        return PolicyCodec.decode(raw)
    }

    fun writePolicy(policy: Policy): Boolean {
        val json = PolicyCodec.encode(policy)
        val ok = writePref(Keys.PREF_POLICY, json, Keys.URI_POLICY)
        // Mirror: harmless if the ROM refuses it, and keeps adb tooling working.
        writeRaw(Keys.POLICY, json)
        return ok
    }

    fun sendCommand(command: Command): Boolean {
        val json = CommandCodec.encode(command)
        val ok = writePref(Keys.PREF_COMMAND, json, Keys.URI_COMMAND)
        writeRaw(Keys.COMMAND, json)
        return ok
    }

    fun requestSafeMode(reason: String): Boolean {
        writeRaw(Keys.SAFE_MODE_REASON, reason)
        return writeRaw(Keys.SAFE_MODE_REQUEST, "1")
    }

    fun currentSeq(): Long = System.currentTimeMillis()

    // ------------------------------------------------------------------ diagnostics

    fun readHeartbeat(): Heartbeat? = TelemetryCodec.decodeHeartbeat(readRaw(Keys.HEARTBEAT))

    fun readEvents(): List<EventRecord> = TelemetryCodec.decodeEvents(readRaw(Keys.EVENTS)).asReversed()

    fun readLogs(): List<String> = TelemetryCodec.decodeLogs(readRaw(Keys.LOGS))

    /**
     * Exercises the *real* downlink (prefs -> provider -> back) and reports the Settings mirror
     * separately, because those two can disagree on this ROM.
     */
    fun selfTest(): String {
        val probe = "probe-${System.currentTimeMillis()}"
        val wrote = try {
            prefs.edit().putString(Keys.PREF_PING, probe).commit()
        } catch (_: Throwable) {
            false
        }
        val viaPrefs = prefs.getString(Keys.PREF_PING, null) == probe
        val viaProvider = try {
            context.contentResolver.query(
                Uri.parse(Keys.URI_PING),
                arrayOf("value"),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (t: Throwable) {
            "error:${t.javaClass.simpleName}"
        }
        val settingsOk = writeRaw(Keys.SAFE_MODE_REASON, probe) &&
            readRaw(Keys.SAFE_MODE_REASON) == probe
        return buildString {
            append("provider 通道 ")
            append(if (wrote && viaPrefs && viaProvider == probe) "正常" else "异常（$viaProvider）")
            append("；Settings 镜像 ")
            append(if (settingsOk) "可用" else "被 ROM 拒绝（不影响主通道）")
        }
    }

    /** Human readable summary of which channel the hook side is actually using. */
    fun downlinkHint(): String = if (canWrite()) {
        "provider 为主，Settings 作镜像"
    } else {
        "provider 为主（无需 WRITE_SETTINGS）"
    }

    /**
     * Read-only view of both sides of the downlink: what we wrote into prefs, and what the provider
     * actually returns for the same URI the hook side queries. A mismatch here means the problem is
     * on the app side; a match means the hook side could not reach us.
     */
    fun channelSnapshot(): String {
        val fromPrefs = prefs.getString(Keys.PREF_POLICY, null)?.length ?: 0
        val fromProvider = queryProviderLength(Keys.URI_POLICY)
        val cmdPrefs = prefs.getString(Keys.PREF_COMMAND, null)?.length ?: 0
        val cmdProvider = queryProviderLength(Keys.URI_COMMAND)
        return "prefs=${fromPrefs}B provider=${fromProvider}B | cmd prefs=${cmdPrefs}B provider=${cmdProvider}B"
    }

    private fun queryProviderLength(uri: String): Int = try {
        context.contentResolver.query(
            Uri.parse(uri),
            arrayOf("value"),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0)?.length ?: 0 else 0
        } ?: -1
    } catch (_: Throwable) {
        -1
    }
}

data class AppEntry(
    val pkg: String,
    val label: String,
    val uid: Int,
    val system: Boolean,
)

class AppInventory(private val context: Context) {

    fun load(): List<AppEntry> {
        val pm = context.packageManager
        val installed = try {
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
        } catch (_: Throwable) {
            emptyList()
        }
        val out = ArrayList<AppEntry>(installed.size)
        for (info in installed) {
            val pkg = info.packageName ?: continue
            val label = try {
                pm.getApplicationLabel(info).toString()
            } catch (_: Throwable) {
                pkg
            }
            val system = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            out.add(AppEntry(pkg = pkg, label = label, uid = info.uid, system = system))
        }
        out.sortWith(compareBy({ it.system }, { it.label.lowercase() }))
        return out
    }

    fun uidOf(pkg: String): Int = try {
        context.packageManager.getPackageUid(pkg, 0)
    } catch (_: Throwable) {
        -1
    }
}

/** Tiny icon cache: `getApplicationIcon` is a Binder call, so it must not run per frame. */
object IconCache {
    private val cache = object : android.util.LruCache<String, ImageBitmap>(200) {}

    fun icon(context: Context, pkg: String): ImageBitmap? {
        cache.get(pkg)?.let { return it }
        return try {
            val drawable: Drawable = context.packageManager.getApplicationIcon(pkg)
            val bitmap = drawable.toBitmap(width = 72, height = 72).asImageBitmap()
            cache.put(pkg, bitmap)
            bitmap
        } catch (_: Throwable) {
            null
        }
    }
}
