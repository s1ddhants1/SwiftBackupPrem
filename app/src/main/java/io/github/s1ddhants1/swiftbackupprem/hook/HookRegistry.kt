package io.github.s1ddhants1.swiftbackupprem.hook

import io.github.s1ddhants1.swiftbackupprem.util.attempt
import java.lang.reflect.Executable

const val PRIORITY_DEFAULT = 50
const val PRIORITY_LOWEST = -10000
const val PRIORITY_HIGHEST = 10000

enum class ExceptionMode {
    DEFAULT,
    PROTECTIVE,
    PASSTHROUGH
}

interface Chain {
    val thisObject: Any?
    val args: List<Any?>
    fun getArg(index: Int): Any? = args.getOrNull(index)
    fun proceed(): Any?
    fun proceed(args: Array<Any?>): Any?
}

fun interface HookHandle {
    fun unhook()
}

interface HookBuilder {
    fun setPriority(priority: Int): HookBuilder = this
    fun setExceptionMode(mode: ExceptionMode): HookBuilder = this
    fun setId(id: String?): HookBuilder = this
    fun intercept(hooker: (Chain) -> Any?): HookHandle
}

interface HookContext {
    fun hookTracked(
        executable: Executable,
        idPrefix: String = "${executable.declaringClass.name}#${executable.name}",
        priority: Int = PRIORITY_DEFAULT,
        deoptimize: Boolean = false
    ): HookBuilder
}

fun Any.getFieldValue(name: String): Any? = attempt("get field $name", silent = true) {
    var c: Class<*>? = this.javaClass
    while (c != null && c != Any::class.java) {
        try {
            val f = c.getDeclaredField(name)
            f.isAccessible = true
            return@attempt f.get(this)
        } catch (_: NoSuchFieldException) {
            c = c.superclass
        }
    }
    null
}

fun Any.setFieldValue(name: String, value: Any?): Boolean = attempt("set field $name", silent = true) {
    var c: Class<*>? = this.javaClass
    while (c != null && c != Any::class.java) {
        try {
            val f = c.getDeclaredField(name)
            f.isAccessible = true
            f.set(this, value)
            return@attempt true
        } catch (_: NoSuchFieldException) {
            c = c.superclass
        }
    }
    false
} ?: false
