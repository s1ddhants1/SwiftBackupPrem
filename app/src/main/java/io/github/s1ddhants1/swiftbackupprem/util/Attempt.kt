package io.github.s1ddhants1.swiftbackupprem.util

import android.util.Log
import io.github.s1ddhants1.swiftbackupprem.Consts

inline fun <T> attempt(operation: String, silent: Boolean = false, block: () -> T): T? = try {
    block()
} catch (t: Throwable) {
    if (!silent) Log.w(Consts.TAG, "Failed $operation: ${t.message}", t)
    null
}

inline fun <T> attemptOrDefault(operation: String, default: T, silent: Boolean = false, block: () -> T): T =
    attempt(operation, silent, block) ?: default

fun loadClassFlexible(cl: ClassLoader, name: String): Class<*>? {
    val clean = name.removePrefix("defpackage.")
    return attempt("load $clean", silent = true) { cl.loadClass(clean) }
        ?: attempt("load defpackage.$clean", silent = true) { cl.loadClass("defpackage.$clean") }
        ?: attempt("load $name", silent = true) { cl.loadClass(name) }
}
