package dev.hypergreeze.core

/**
 * The whole policy document. Immutable: every edit produces a new instance with a bumped
 * [revision], which is what the hook side watches to invalidate its cached engine.
 */
data class Policy(
    val version: Int = PolicyCodec.VERSION,
    val revision: Long = 0L,
    val updatedAt: Long = 0L,
    val global: GlobalPolicy = GlobalPolicy(),
    val apps: Map<String, AppPolicy> = emptyMap(),
) {
    fun app(pkg: String): AppPolicy? = apps[pkg]

    fun appOrDefault(pkg: String): AppPolicy = apps[pkg] ?: AppPolicy()

    fun withApp(pkg: String, value: AppPolicy): Policy {
        val next = apps.toMutableMap()
        if (value == AppPolicy()) next.remove(pkg) else next[pkg] = value
        return copy(
            apps = next,
            revision = revision + 1,
            updatedAt = System.currentTimeMillis(),
        )
    }

    fun withoutApp(pkg: String): Policy = withApp(pkg, AppPolicy())

    fun withGlobal(value: GlobalPolicy): Policy =
        copy(global = value, revision = revision + 1, updatedAt = System.currentTimeMillis())

    val configuredCount: Int get() = apps.count { it.value.isEffective }

    companion object {
        val EMPTY = Policy()
    }
}
