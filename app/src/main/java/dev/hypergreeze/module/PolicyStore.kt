package dev.hypergreeze.module

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import dev.hypergreeze.core.Channel
import dev.hypergreeze.core.Keys
import dev.hypergreeze.core.Policy
import dev.hypergreeze.core.PolicyCodec

/**
 * Reads the policy document inside `system_server`.
 *
 * Two sources, in this order:
 *
 *  1. **`content://dev.hypergreeze.policy/policy`** — the app owns the data (SharedPreferences) and
 *     serves it read-only. This is the primary path because `Settings.System.putString` can be
 *     refused for a third-party app on some HyperOS builds even with `WRITE_SETTINGS` granted.
 *  2. **`Settings.System["greeze_ctl_policy_v1"]`** — fallback and adb-friendly mirror.
 *
 * Reading the provider can start the app process, so [reload] takes `allowProvider`: the periodic
 * tick reads only Settings (cheap, never starts anything), while boot, content-observer callbacks
 * and command handling are allowed to query the provider.
 */
class PolicyStore(private val context: Context) {

    @Volatile
    private var cached: Policy = Policy.EMPTY

    /** Rebuilt only when the document actually changes: hook hot paths ask for it constantly. */
    @Volatile
    private var cachedEngine: PolicyEngine = PolicyEngine(Policy.EMPTY)

    @Volatile
    private var rawCache: String? = null

    /** Which source produced [rawCache]; surfaced in the heartbeat. */
    @Volatile
    var lastChannel: String = Channel.NONE
        private set

    @Volatile
    var lastReloadAt: Long = 0L
        private set

    @Volatile
    var reloadCount: Long = 0L
        private set

    private var observer: ContentObserver? = null

    val engine: PolicyEngine get() = cachedEngine
    val policy: Policy get() = cached
    val raw: String? get() = rawCache

    fun start() {
        registerObservers()
    }

    fun stop() {
        observer?.let {
            try {
                context.contentResolver.unregisterContentObserver(it)
            } catch (_: Throwable) {
                // ignore
            }
        }
        observer = null
    }

    private fun registerObservers() {
        if (observer != null) return
        val obs = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                try {
                    reload(allowProvider = true)
                } catch (t: Throwable) {
                    Logx.w("policy observer reload failed", t)
                }
            }
        }
        observer = obs
        // Provider notifications (app -> hook) plus the Settings mirror, plus the command URI.
        for (uri in listOf(
            Keys.URI_POLICY,
            Keys.URI_COMMAND,
            Settings.System.getUriFor(Keys.POLICY).toString(),
            Settings.System.getUriFor(Keys.COMMAND).toString(),
        )) {
            try {
                context.contentResolver.registerContentObserver(Uri.parse(uri), true, obs)
            } catch (t: Throwable) {
                Logx.w("registerContentObserver failed for $uri", t)
            }
        }
    }

    /** Last value seen in the Settings mirror, used to tell "deleted" from "cannot be read". */
    private var lastSettingsRaw: String? = null

    /** Returns true when the effective policy changed. */
    fun reload(allowProvider: Boolean = true): Boolean {
        val fromProvider = if (allowProvider) queryProvider(Keys.URI_POLICY) else null
        val fromSettings = readSettings(Keys.POLICY)
        val settingsDeleted = lastSettingsRaw != null && fromSettings == null
        lastSettingsRaw = fromSettings

        val raw: String? = when {
            !fromProvider.isNullOrBlank() -> {
                lastChannel = Channel.PROVIDER
                fromProvider
            }

            !fromSettings.isNullOrBlank() -> {
                lastChannel = Channel.SETTINGS
                fromSettings
            }

            settingsDeleted -> {
                // The mirror used to hold a policy and now does not: honour the deletion.
                lastChannel = Channel.NONE
                null
            }

            else -> {
                // No information at all (Settings write blocked on this ROM and no provider answer).
                // Keep the current policy instead of silently reverting to "everything follows".
                return false
            }
        }

        lastReloadAt = System.currentTimeMillis()
        if (raw == rawCache) return false

        val parsed = PolicyCodec.decode(raw)
        val changed = parsed != cached
        if (changed) {
            cachedEngine = PolicyEngine(parsed)
        }
        rawCache = raw
        cached = parsed
        reloadCount++
        if (changed) {
            Logx.i("policy reloaded via $lastChannel: ${cachedEngine.summary()}")
        }
        return changed
    }

    /** Command payload: provider first (app just wrote it), Settings as fallback. */
    fun readCommand(): String? {
        val fromProvider = queryProvider(Keys.URI_COMMAND)
        if (!fromProvider.isNullOrBlank()) return fromProvider
        return readSettings(Keys.COMMAND)
    }

    private fun queryProvider(uri: String): String? = try {
        context.contentResolver.query(
            Uri.parse(uri),
            arrayOf("value"),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        }
    } catch (t: Throwable) {
        // Provider not reachable yet (boot), app not installed, or SELinux: fall back silently.
        Logx.w("provider read failed: ${t.javaClass.simpleName}")
        null
    }

    private fun readSettings(key: String): String? = try {
        Settings.System.getString(context.contentResolver, key)
    } catch (t: Throwable) {
        Logx.w("read $key failed", t)
        null
    }
}
