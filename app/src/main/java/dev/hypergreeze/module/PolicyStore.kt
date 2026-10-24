package dev.hypergreeze.module

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import dev.hypergreeze.core.Keys
import dev.hypergreeze.core.Policy
import dev.hypergreeze.core.PolicyCodec

/**
 * Reads the policy document out of `Settings.System` inside `system_server`.
 *
 * Only ever reads: the app is the single writer, so there is no cross-process write race and no
 * need for a Binder service. A [ContentObserver] makes changes take effect in well under a second;
 * a slow poll in [PolicyScheduler] is the belt-and-braces fallback for ROMs where the observer
 * does not fire for a system-setting write made by another uid.
 */
class PolicyStore(private val context: Context) {

    @Volatile
    private var cached: Policy = Policy.EMPTY

    @Volatile
    private var rawCache: String? = null

    @Volatile
    var lastReloadAt: Long = 0L
        private set

    @Volatile
    var reloadCount: Long = 0L
        private set

    private var observer: ContentObserver? = null

    val engine: PolicyEngine get() = PolicyEngine(cached)
    val policy: Policy get() = cached
    val raw: String? get() = rawCache

    fun start() {
        reload()
        if (observer == null) {
            val obs = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    try {
                        reload()
                    } catch (t: Throwable) {
                        Logx.w("policy observer reload failed", t)
                    }
                }
            }
            observer = obs
            try {
                context.contentResolver.registerContentObserver(
                    Settings.System.getUriFor(Keys.POLICY),
                    true,
                    obs,
                )
            } catch (t: Throwable) {
                Logx.w("registerContentObserver failed, falling back to polling", t)
            }
        }
    }

    /** Returns true when the effective policy changed. */
    fun reload(): Boolean {
        val raw = readRaw()
        lastReloadAt = System.currentTimeMillis()
        if (raw == rawCache) return false
        val parsed = PolicyCodec.decode(raw)
        val changed = parsed != cached
        rawCache = raw
        cached = parsed
        reloadCount++
        if (changed) {
            Logx.i("policy reloaded: ${PolicyEngine(parsed).summary()}")
        }
        return changed
    }

    private fun readRaw(): String? = try {
        Settings.System.getString(context.contentResolver, Keys.POLICY)
    } catch (t: Throwable) {
        Logx.w("read policy failed", t)
        null
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
}
