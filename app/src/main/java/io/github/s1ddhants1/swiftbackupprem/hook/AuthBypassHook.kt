package io.github.s1ddhants1.swiftbackupprem.hook

import android.content.Context
import android.util.Log
import androidx.annotation.Keep
import io.github.s1ddhants1.swiftbackupprem.Consts
import io.github.s1ddhants1.swiftbackupprem.util.PreferencesManager
import io.github.s1ddhants1.swiftbackupprem.util.attempt

@Keep
object AuthBypassHook : HookHandler {

    override fun apply(
        module: HookContext,
        context: Context,
        classLoader: ClassLoader,
        targets: ResolvedTargets,
        prefs: PreferencesManager
    ) {
        Log.d(Consts.TAG, "Applying core AuthBypassHook")
        targets.clientIdClass?.let { cIdClass ->
            val candidateMethods = cIdClass.declaredMethods.filter { m ->
                m.parameterCount == 2 && (m.returnType == Void.TYPE || m.returnType == java.lang.Void.TYPE)
            }
            for (m in candidateMethods) {
                attempt("hook legacy FirebaseAuth bypass method ${m.name}") {
                    module.hookTracked(
                        m,
                        idPrefix = "auth-bypass-clientid-${m.name}"
                    ).intercept { chain ->
                        val task = chain.getArg(1)
                        val isSuccessful = attempt("check task isSuccessful", silent = true) {
                            if (task != null) task.javaClass.getMethod("isSuccessful").invoke(task) as? Boolean ?: true else true
                        } ?: true

                        if (!isSuccessful && task != null) {
                            val callback = chain.getArg(0)
                            if (callback != null) {
                                attempt("set auth success callback") {
                                    for (nested in cIdClass.declaredClasses) {
                                        for (inner in nested.declaredClasses) {
                                            val successInstance = attempt("get static success instance", silent = true) {
                                                (inner.declaredFields.firstOrNull { java.lang.reflect.Modifier.isStatic(it.modifiers) && inner.isAssignableFrom(it.type) }
                                                    ?: inner.declaredFields.firstOrNull { java.lang.reflect.Modifier.isStatic(it.modifiers) })
                                                    ?.apply { isAccessible = true }?.get(null)
                                            }
                                            if (successInstance != null) {
                                                val invokeMethod = callback.javaClass.methods.firstOrNull { it.name == "invoke" && it.parameterCount == 1 }
                                                invokeMethod?.invoke(callback, successInstance)
                                                return@intercept null
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        chain.proceed()
                    }
                }
            }
        }
    }
}
