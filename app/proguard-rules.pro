# HyperGreeze is R8-minified off for reliability, but keep these rules so an
# `isMinifyEnabled = true` build still works: the framework instantiates the entry
# class by name and calls lifecycle callbacks reflectively.
-keep class dev.hypergreeze.module.HyperGreezeModule { *; }
-keep class dev.hypergreeze.hook.** { *; }

# The Xposed API is compileOnly: the framework supplies it at runtime.
-dontwarn io.github.libxposed.annotation.**

# If minification is ever enabled: the entry class is listed by name in
# META-INF/xposed/java_init.list, so either keep the name (our rule above) or let AGP rewrite
# the list. Both are wired up so flipping isMinifyEnabled stays safe.
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}

# Kotlin metadata / coroutines hints are irrelevant for a module.
-dontwarn kotlinx.coroutines.**
