package dev.hypergreeze.module

import android.util.Log
import dev.hypergreeze.BuildConfig
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * HyperGreeze module entry — libxposed **API 102**.
 *
 * Packaging contract (see docs/ARCHITECTURE.md):
 *  - `META-INF/xposed/java_init.list`      -> this class
 *  - `META-INF/xposed/module.prop`         -> minApiVersion / targetApiVersion / staticScope
 *  - `META-INF/xposed/scope.list`          -> `system` (the virtual scope name for system_server)
 *
 * The entry is instantiated once per module generation, has no constructor arguments, and must not
 * do any work before [onModuleLoaded] runs.
 */
class HyperGreezeModule : XposedModule() {

    private var controller: ModuleController? = null

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        Logx.attach { priority, tag, message, throwable -> log(priority, tag, message, throwable) }
        Logx.i(
            "onModuleLoaded process=${param.processName} systemServer=${param.isSystemServer} " +
                "api=$apiVersion module=${BuildConfig.VERSION_NAME} framework=$frameworkName $frameworkVersion",
        )
        if (!param.isSystemServer) {
            // Scoped to `system`, but the framework may still load us into sibling processes; stop
            // receiving callbacks there instead of paying for a probe that can never succeed.
            Logx.i("not system_server -> detach()")
            detach()
        }
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        try {
            Logx.i("onSystemServerStarting; classLoader=${param.classLoader}")
            val controller = ModuleController(
                module = this,
                classLoader = param.classLoader,
                moduleVersion = BuildConfig.VERSION_NAME,
            )
            this.controller = controller
            controller.start()
        } catch (t: Throwable) {
            // Last line of defence: nothing may escape into SystemServer.startBootstrapServices().
            Logx.e("bootstrap failed; module inert for this boot", t)
        }
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        // Not used: everything happens in onSystemServerStarting. Kept as a log breadcrumb so the
        // diagnostics page can prove the entry is alive on ROMs where GreeZe is missing.
        if (param.isFirstPackage()) {
            Logx.i("first package loaded: ${param.packageName}")
        }
    }
}
