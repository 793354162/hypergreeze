package dev.hypergreeze.module

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Defensive reflection helpers.
 *
 * The whole point of resolving by **name + arity** instead of a hard-coded descriptor is OTA
 * survivability: MIUI moves parameters around between HyperOS builds (`long delay` becomes
 * `int delay`, an extra `boolean freezeByUid` appears, …). A descriptor-exact lookup would turn
 * that into a dead module; an arity-tolerant lookup degrades into "the hook still fires".
 */
object R {

    fun classOrNull(loader: ClassLoader?, name: String): Class<*>? = try {
        Class.forName(name, false, loader)
    } catch (_: Throwable) {
        null
    }

    fun classesOrNull(loader: ClassLoader?, vararg names: String): Class<*>? {
        for (name in names) classOrNull(loader, name)?.let { return it }
        return null
    }

    fun declaredMethods(cls: Class<*>): Array<Method> = try {
        cls.declaredMethods
    } catch (_: Throwable) {
        emptyArray()
    }

    /**
     * Finds the best method for [name].
     *
     * Order of preference: exact arity + preferred return type -> exact arity -> sole name match.
     * Returns null when the name does not exist at all, so callers can report `missing` instead
     * of failing at call time.
     */
    fun method(
        cls: Class<*>?,
        name: String,
        arity: Int? = null,
        returnType: Class<*>? = null,
    ): Method? {
        if (cls == null) return null
        val candidates = declaredMethods(cls).filter { it.name == name }
        if (candidates.isEmpty()) return null
        var pool = candidates
        if (arity != null) {
            val byArity = pool.filter { it.parameterCount == arity }
            if (byArity.isNotEmpty()) pool = byArity
        }
        if (returnType != null) {
            val byReturn = pool.filter { it.returnType == returnType }
            if (byReturn.isNotEmpty()) pool = byReturn
        }
        val picked = pool.firstOrNull { !Modifier.isAbstract(it.modifiers) } ?: pool.first()
        return try {
            picked.isAccessible = true
            picked
        } catch (_: Throwable) {
            picked
        }
    }

    fun anyMethod(cls: Class<*>?, vararg names: String, arity: Int? = null): Method? {
        for (name in names) method(cls, name, arity)?.let { return it }
        return null
    }

    fun field(cls: Class<*>?, vararg names: String): Field? {
        if (cls == null) return null
        var c: Class<*>? = cls
        while (c != null) {
            for (name in names) {
                val f = try {
                    c.getDeclaredField(name)
                } catch (_: Throwable) {
                    null
                }
                if (f != null) {
                    return try {
                        f.isAccessible = true
                        f
                    } catch (_: Throwable) {
                        f
                    }
                }
            }
            c = c.superclass
        }
        return null
    }

    fun allFields(cls: Class<*>?): List<Field> {
        if (cls == null) return emptyList()
        val out = ArrayList<Field>()
        var c: Class<*>? = cls
        while (c != null) {
            try {
                out.addAll(c.declaredFields)
            } catch (_: Throwable) {
                // ignore
            }
            c = c.superclass
        }
        return out
    }

    fun readField(target: Any?, field: Field?): Any? {
        if (field == null) return null
        return try {
            field.get(target)
        } catch (_: Throwable) {
            null
        }
    }

    fun writeField(target: Any?, field: Field?, value: Any?): Boolean {
        if (field == null) return false
        return try {
            field.set(target, value)
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun invoke(target: Any?, method: Method?, vararg args: Any?): Any? {
        if (method == null) return null
        return method.invoke(target, *args)
    }

    /** Coerces a reflected result into the expected primitive type, tolerating widening. */
    fun asInt(value: Any?, def: Int = 0): Int = when (value) {
        is Int -> value
        is Long -> value.toInt()
        is Short -> value.toInt()
        is Boolean -> if (value) 1 else 0
        else -> def
    }

    fun asLong(value: Any?, def: Long = 0L): Long = when (value) {
        is Long -> value
        is Int -> value.toLong()
        is Short -> value.toLong()
        else -> def
    }

    fun asBool(value: Any?, def: Boolean = false): Boolean = when (value) {
        is Boolean -> value
        is Int -> value != 0
        else -> def
    }

    fun asString(value: Any?): String? = value as? String

    /** Static singleton fields commonly used by MIUI system services, in priority order. */
    val SINGLETON_FIELDS = arrayOf(
        "sInstance", "INSTANCE", "sInstanceHolder", "mInstance", "sGreezeManagerService",
        "sGreezeManager", "sManager", "sService", "mSingleton",
    )
}
