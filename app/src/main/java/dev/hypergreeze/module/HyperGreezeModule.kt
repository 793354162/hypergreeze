package dev.hypergreeze.module

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * HyperGreeze module entry (libxposed API 102).
 *
 * Declared in `META-INF/xposed/java_init.list`; scope (`system`) in `META-INF/xposed/scope.list`;
 * metadata in `META-INF/xposed/module.prop`. Nothing is initialised before [onModuleLoaded].
 */
class HyperGreezeModule : XposedModule() {

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        log(Log.INFO, TAG, "loaded: process=${param.processName} systemServer=${param.isSystemServer}")
        log(Log.INFO, TAG, "framework=$frameworkName $frameworkVersion api=$apiVersion props=$frameworkProperties")
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        log(Log.INFO, TAG, "system server starting; capSystem=${(frameworkProperties and XposedInterface.PROP_CAP_SYSTEM) != 0L}")
        log(Log.INFO, TAG, "boot classloader=${param.classLoader}")
    }

    private companion object {
        const val TAG = "HyperGreeze"
    }
}
