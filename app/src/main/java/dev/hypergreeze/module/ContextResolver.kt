package dev.hypergreeze.module

import android.content.Context
import dev.hypergreeze.module.greeze.GreezeFacade

/**
 * Getting a usable `Context` inside `system_server` without holding one.
 *
 * Order matters: GreeZe's own context is the one whose user/SELinux domain is guaranteed to be
 * able to talk to `SettingsProvider`, so it is preferred over the generic system context.
 */
object ContextResolver {

    fun resolve(facade: GreezeFacade?): Context? {
        facade?.resolveContext()?.let { return it }
        systemContext()?.let { return it }
        return currentApplication()
    }

    private fun systemContext(): Context? = try {
        val activityThread = Class.forName("android.app.ActivityThread")
        val current = activityThread.getMethod("currentActivityThread").invoke(null)
        val method = activityThread.getMethod("getSystemContext")
        method.invoke(current) as? Context
    } catch (_: Throwable) {
        null
    }

    private fun currentApplication(): Context? = try {
        val activityThread = Class.forName("android.app.ActivityThread")
        activityThread.getMethod("currentApplication").invoke(null) as? Context
    } catch (_: Throwable) {
        null
    }
}
