package dev.hypergreeze.module

import java.lang.reflect.Method

/**
 * `android.os.SystemProperties` accessor.
 *
 * Used for the boot-loop guard and the safe-mode flag only: those must be readable **before**
 * a `Context` exists in `system_server` (that is precisely the window where a bad build would
 * boot-loop). Reading a property needs no permission, writing one works because we run as
 * `system`; both are wrapped so a locked-down build degrades to "guard unavailable" instead of
 * crashing the boot.
 */
object SysProp {
    private val getter: Method? = try {
        Class.forName("android.os.SystemProperties")
            .getMethod("get", String::class.java, String::class.java)
    } catch (_: Throwable) {
        null
    }

    private val setter: Method? = try {
        Class.forName("android.os.SystemProperties")
            .getMethod("set", String::class.java, String::class.java)
    } catch (_: Throwable) {
        null
    }

    val available: Boolean get() = getter != null && setter != null

    fun get(key: String, def: String = ""): String = try {
        (getter?.invoke(null, key, def) as? String) ?: def
    } catch (_: Throwable) {
        def
    }

    fun getInt(key: String, def: Int = 0): Int = get(key).toIntOrNull() ?: def

    fun getBoolean(key: String, def: Boolean = false): Boolean {
        val value = get(key)
        if (value.isEmpty()) return def
        return value == "1" || value.equals("true", ignoreCase = true)
    }

    fun set(key: String, value: String): Boolean = try {
        setter?.invoke(null, key, value)
        setter != null
    } catch (_: Throwable) {
        false
    }

    fun setInt(key: String, value: Int): Boolean = set(key, value.toString())
}
