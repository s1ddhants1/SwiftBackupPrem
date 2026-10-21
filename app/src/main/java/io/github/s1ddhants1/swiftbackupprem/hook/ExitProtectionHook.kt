package io.github.s1ddhants1.swiftbackupprem.hook

import android.content.Context
import android.util.Log
import androidx.annotation.Keep
import io.github.s1ddhants1.swiftbackupprem.Consts
import io.github.s1ddhants1.swiftbackupprem.util.PreferencesManager
import io.github.s1ddhants1.swiftbackupprem.util.attempt
import java.util.concurrent.atomic.AtomicBoolean

@Keep
object ExitProtectionHook : HookHandler {

    private val hooked = AtomicBoolean(false)

    fun reset() {
        hooked.set(false)
    }

    override fun apply(
        module: HookContext,
        context: Context,
        classLoader: ClassLoader,
        targets: ResolvedTargets,
        prefs: PreferencesManager
    ) {
        neutralizeSystemExit(module)
    }

    fun applyEarly(module: HookContext, classLoader: ClassLoader) {
        neutralizeSystemExit(module)
    }

    private fun neutralizeSystemExit(module: HookContext) {
        if (!hooked.compareAndSet(false, true)) return
        attempt("neutralize System.exit", silent = true) {
            val m = System::class.java.getDeclaredMethod("exit", Int::class.javaPrimitiveType)
            module.hookTracked(
                m,
                idPrefix = "exit-system-exit",
                priority = PRIORITY_HIGHEST,
                deoptimize = true
            ).intercept { chain ->
                val code = chain.getArg(0)
                Log.w(Consts.TAG, "Neutralized System.exit($code)")
                null
            }
        }
        attempt("neutralize Runtime.exit", silent = true) {
            val m = Runtime::class.java.getDeclaredMethod("exit", Int::class.javaPrimitiveType)
            module.hookTracked(
                m,
                idPrefix = "exit-runtime-exit",
                priority = PRIORITY_HIGHEST,
                deoptimize = true
            ).intercept { chain ->
                val code = chain.getArg(0)
                Log.w(Consts.TAG, "Neutralized Runtime.exit($code)")
                null
            }
        }
    }
}
