package io.github.s1ddhants1.swiftbackupprem.hook

import android.content.Context
import android.util.Log
import io.github.s1ddhants1.swiftbackupprem.Consts
import io.github.s1ddhants1.swiftbackupprem.util.PreferencesManager
import io.github.s1ddhants1.swiftbackupprem.util.attempt

object PremiumFeatureHook : HookHandler {

    override fun apply(
        module: HookContext,
        context: Context,
        classLoader: ClassLoader,
        targets: ResolvedTargets,
        prefs: PreferencesManager
    ) {
        Log.d(Consts.TAG, "Applying premium state: ${prefs.enablePremium}")

        targets.vClass?.let { hookVClass(module, it, prefs) }
        targets.homeViewModelClass?.let { hookHomeViewModelClass(module, it, prefs) }
        hookKnownClasses(module, classLoader, prefs, targets.vClass)
    }

    fun updateVpField(targets: ResolvedTargets, isPremium: Boolean) {
        targets.vClass?.let { targetClass ->
            attempt("update V.vp field", silent = true) {
                targetClass.getDeclaredField("vp").apply { isAccessible = true }.set(null, isPremium)
            }
        }
    }

    fun hookSwiftAppPremium(module: HookContext, swiftApp: Any?, prefs: PreferencesManager) {
        if (swiftApp == null) return
        attempt("hook SwiftApp premium LiveData") {
            for (field in swiftApp.javaClass.declaredFields) {
                field.isAccessible = true
                val liveDataObj = attempt("read field ${field.name}", silent = true) { field.get(swiftApp) } ?: continue
                val ldClass = liveDataObj.javaClass

                val isTarget = field.name == "mutablePremium" ||
                    (ldClass.name.contains("LiveData") && attempt("check LiveData value type", silent = true) {
                        ldClass.methods.firstOrNull { m -> m.parameterCount == 0 && m.returnType == Any::class.java }?.invoke(liveDataObj) is Boolean
                    } == true)

                if (isTarget) {
                    for (m in ldClass.methods) {
                        if (m.parameterCount == 1 && (m.name in listOf("setValue", "postValue") ||
                            (m.parameterTypes[0] == Any::class.java && (m.returnType == Void.TYPE || m.returnType == java.lang.Void::class.java)))) {
                            attempt("invoke LiveData setter ${m.name}", silent = true) { m.invoke(liveDataObj, prefs.enablePremium) }
                        }
                    }
                    for (m in ldClass.declaredMethods) {
                        if (m.parameterCount == 1 && (m.name in listOf("setValue", "postValue") ||
                            (m.parameterTypes[0] == Any::class.java && (m.returnType == Void.TYPE || m.returnType == java.lang.Void::class.java)))) {
                            attempt("hook LiveData setter ${m.name}") {
                                module.hookTracked(
                                    m,
                                    idPrefix = "premium-livedata-set-${m.name}",
                                    deoptimize = true
                                ).intercept { chain ->
                                    if (chain.thisObject === liveDataObj && (chain.getArg(0) is Boolean || chain.getArg(0) == null)) {
                                        chain.proceed(arrayOf(prefs.enablePremium))
                                    } else chain.proceed()
                                }
                            }
                        } else if (m.parameterCount == 0 && (m.name == "getValue" || m.returnType == Any::class.java)) {
                            attempt("hook LiveData getter ${m.name}") {
                                module.hookTracked(
                                    m,
                                    idPrefix = "premium-livedata-get-${m.name}",
                                    deoptimize = true
                                ).intercept { chain ->
                                    if (chain.thisObject === liveDataObj) prefs.enablePremium else chain.proceed()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun hookKnownClasses(module: HookContext, cl: ClassLoader, prefs: PreferencesManager, resolvedVClass: Class<*>?) {
        if (resolvedVClass?.name != "org.swiftapps.swiftbackup.common.V") {
            attempt("load and hook known V class fallback", silent = true) {
                hookVClass(module, cl.loadClass("org.swiftapps.swiftbackup.common.V"), prefs)
            }
        }
        attempt("load and hook known V\$a class", silent = true) {
            val vClassA = cl.loadClass("org.swiftapps.swiftbackup.common.V\$a")
            for (m in vClassA.declaredMethods) {
                if (m.name == "invoke") {
                    module.hookTracked(
                        m,
                        idPrefix = "premium-v-lambda-invoke",
                        deoptimize = true
                    ).intercept { prefs.enablePremium }
                    break
                }
            }
        }
    }

    private fun hookVClass(module: HookContext, targetClass: Class<*>, prefs: PreferencesManager) {
        Log.d(Consts.TAG, "Hooking V class: ${targetClass.name} (isPremium=${prefs.enablePremium})")
        attempt("set V.vp field") {
            targetClass.getDeclaredField("vp").apply { isAccessible = true }.set(null, prefs.enablePremium)
        }

        for (m in targetClass.declaredMethods) {
            if (m.name.startsWith("_get_") && (m.returnType == Boolean::class.javaPrimitiveType || m.returnType == Boolean::class.javaObjectType)) {
                attempt("hook V synthetic lambda ${m.name}") {
                    module.hookTracked(
                        m,
                        idPrefix = "premium-v-lambda-${m.name}",
                        deoptimize = true
                    ).intercept { prefs.enablePremium }
                }
            }
            when (m.name) {
                "getA", "getG", "getVp" -> attempt("hook V getter ${m.name}") {
                    module.hookTracked(
                        m,
                        idPrefix = "premium-v-${m.name}",
                        deoptimize = true
                    ).intercept { prefs.enablePremium }
                }
                "setA", "setVp" -> if (m.parameterCount == 1) attempt("hook V setter ${m.name}") {
                    module.hookTracked(
                        m,
                        idPrefix = "premium-v-${m.name}",
                        deoptimize = true
                    ).intercept { chain -> chain.proceed(arrayOf(prefs.enablePremium)) }
                }
                "getC" -> attempt("hook V.getC") {
                    module.hookTracked(
                        m,
                        idPrefix = "premium-v-getC",
                        deoptimize = true
                    ).intercept { java.lang.Boolean.FALSE }
                }
                "getB" -> attempt("hook V.getB") {
                    module.hookTracked(
                        m,
                        idPrefix = "premium-v-getB",
                        deoptimize = true
                    ).intercept { null }
                }
            }
        }
    }

    private fun hookHomeViewModelClass(module: HookContext, targetClass: Class<*>, prefs: PreferencesManager) {
        Log.d(Consts.TAG, "Hooking HomeViewModel class: ${targetClass.name} (isPremium=${prefs.enablePremium})")
        for (m in targetClass.declaredMethods) {
            if (m.parameterCount == 1 && (m.parameterTypes[0] == Boolean::class.javaPrimitiveType || m.parameterTypes[0] == Boolean::class.javaObjectType)) {
                attempt("hook HomeViewModel setter ${m.name}") {
                    module.hookTracked(
                        m,
                        idPrefix = "premium-homevm-set-${m.name}",
                        deoptimize = true
                    ).intercept { chain -> chain.proceed(arrayOf(prefs.enablePremium)) }
                }
            } else if (m.parameterCount == 0 && (m.returnType == Boolean::class.javaPrimitiveType || m.returnType == Boolean::class.javaObjectType)) {
                attempt("hook HomeViewModel getter ${m.name}") {
                    module.hookTracked(
                        m,
                        idPrefix = "premium-homevm-get-${m.name}",
                        deoptimize = true
                    ).intercept { prefs.enablePremium }
                }
            }
        }
    }
}
