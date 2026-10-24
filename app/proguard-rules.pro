# HyperGreeze is R8-minified off for reliability, but keep these rules so an
# `isMinifyEnabled = true` build still works: the framework instantiates the entry
# class by name and calls lifecycle callbacks reflectively.
-keep class dev.hypergreeze.module.HyperGreezeModule { *; }
-keep class dev.hypergreeze.hook.** { *; }

# The Xposed API is compileOnly: the framework supplies it at runtime.
-dontwarn io.github.libxposed.**

# Kotlin metadata / coroutines hints are irrelevant for a module.
-dontwarn kotlinx.coroutines.**
