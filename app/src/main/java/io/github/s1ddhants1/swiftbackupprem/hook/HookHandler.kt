package io.github.s1ddhants1.swiftbackupprem.hook

import android.content.Context
import io.github.s1ddhants1.swiftbackupprem.util.PreferencesManager

interface HookHandler {
    fun apply(
        module: HookContext,
        context: Context,
        classLoader: ClassLoader,
        targets: ResolvedTargets,
        prefs: PreferencesManager
    )
}
