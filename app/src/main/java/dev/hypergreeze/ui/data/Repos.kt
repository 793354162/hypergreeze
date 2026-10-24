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
 * The app half of the `Settings.System` channel.
 *
 * Everything here is a plain read/write plus honest failure reporting: if `WRITE_SETTINGS` was not
 * granted (or MIUI rejected the key), the caller gets `false` and the UI explains how to fix it
 * instead of pretending the policy was saved.
 */
class SettingsGateway(private val context: Context) {

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

    fun writeRaw(key: String, value: String?): Boolean {
        if (!canWrite()) return false
        return try {
            Settings.System.putString(context.contentResolver, key, value)
        } catch (_: Throwable) {
            false
        }
    }

    fun readPolicy(): Policy = PolicyCodec.decode(readRaw(Keys.POLICY))

    fun writePolicy(policy: Policy): Boolean = writeRaw(Keys.POLICY, PolicyCodec.encode(policy))

    fun readHeartbeat(): Heartbeat? = TelemetryCodec.decodeHeartbeat(readRaw(Keys.HEARTBEAT))

    fun readEvents(): List<EventRecord> = TelemetryCodec.decodeEvents(readRaw(Keys.EVENTS)).asReversed()

    fun readLogs(): List<String> = TelemetryCodec.decodeLogs(readRaw(Keys.LOGS))

    fun sendCommand(command: Command): Boolean = writeRaw(Keys.COMMAND, CommandCodec.encode(command))

    fun requestSafeMode(reason: String): Boolean {
        writeRaw(Keys.SAFE_MODE_REASON, reason)
        return writeRaw(Keys.SAFE_MODE_REQUEST, "1")
    }

    /** Round-trip self test used by the diagnostics page. */
    fun selfTest(): String {
        if (!canWrite()) return "未授予「修改系统设置」权限"
        val probe = "hypergreeze-probe-${System.currentTimeMillis()}"
        val ok = writeRaw(Keys.SAFE_MODE_REASON, probe)
        val back = readRaw(Keys.SAFE_MODE_REASON)
        return when {
            !ok -> "写入被拒绝（appop WRITE_SETTINGS 未生效？）"
            back != probe -> "写入成功但读回不一致: $back"
            else -> "写入/读回正常"
        }
    }

    fun currentSeq(): Long = System.currentTimeMillis()
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
    private val cache = object : android.util.LruCache<String, ImageBitmap>(96) {}

    fun icon(context: Context, pkg: String): ImageBitmap? {
        cache.get(pkg)?.let { return it }
        return try {
            val drawable: Drawable = context.packageManager.getApplicationIcon(pkg)
            val bitmap = drawable.toBitmap(width = 96, height = 96).asImageBitmap()
            cache.put(pkg, bitmap)
            bitmap
        } catch (_: Throwable) {
            null
        }
    }
}
