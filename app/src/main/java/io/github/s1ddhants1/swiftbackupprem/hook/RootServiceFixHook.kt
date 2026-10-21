package io.github.s1ddhants1.swiftbackupprem.hook

import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.annotation.Keep
import io.github.s1ddhants1.swiftbackupprem.Consts
import io.github.s1ddhants1.swiftbackupprem.util.LSPatchHelper
import io.github.s1ddhants1.swiftbackupprem.util.PreferencesManager
import io.github.s1ddhants1.swiftbackupprem.util.attempt
import java.io.ByteArrayOutputStream
import java.io.FilterOutputStream
import java.io.OutputStream
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
@Keep
object RootServiceFixHook : HookHandler {

    override fun apply(
        module: HookContext,
        context: Context,
        classLoader: ClassLoader,
        targets: ResolvedTargets,
        prefs: PreferencesManager
    ) {
        val originPath = LSPatchHelper.resolveOriginApkPath(classLoader)
        if (originPath == null) {
            Log.w(Consts.TAG, "RootServiceFixHook: could not resolve origin APK path")
            return
        }

        val installedApkPath = resolveInstalledApkPath(context, originPath)
        Log.i(Consts.TAG, "RootServiceFixHook: origin APK: $originPath, installed APK: $installedApkPath")

        if (installedApkPath != null && installedApkPath != originPath) {
            hookRootServiceManager(module, targets.rootServiceManagerClass, classLoader, originPath, installedApkPath)
        }

        hookGetPackageCodePath(module, originPath)
        patchSourceDir(context.applicationInfo, originPath)
    }

    fun resolveInstalledApkPath(context: Context, originPath: String): String? {
        val pmPath = attempt("get sourceDir from PackageManager", silent = true) {
            context.packageManager.getApplicationInfo(Consts.packageName, 0).sourceDir
        }
        if (!pmPath.isNullOrBlank() && pmPath != originPath && pmPath.endsWith(".apk")) {
            return pmPath
        }

        val codePath = attempt("get packageCodePath", silent = true) {
            context.packageCodePath
        }
        if (!codePath.isNullOrBlank() && codePath != originPath && codePath.endsWith(".apk")) {
            return codePath
        }

        val publicSource = attempt("get publicSourceDir", silent = true) {
            context.applicationInfo.publicSourceDir
        }
        if (!publicSource.isNullOrBlank() && publicSource != originPath && publicSource.endsWith(".apk")) {
            return publicSource
        }

        return pmPath ?: codePath
    }

    private fun hookRootServiceManager(
        module: HookContext,
        targetManagerClass: Class<*>?,
        classLoader: ClassLoader,
        originPath: String,
        installedApkPath: String
    ) {
        val managerClass = targetManagerClass ?: attempt("load RootServiceManager", silent = true) {
            classLoader.loadClass("com.topjohnwu.superuser.internal.RootServiceManager")
        } ?: return

        val startRootProcessMethod = managerClass.declaredMethods.firstOrNull {
            it.name == "startRootProcess"
        } ?: managerClass.declaredMethods.firstOrNull {
            it.parameterTypes.size == 2 &&
                it.parameterTypes[0] == ComponentName::class.java &&
                it.parameterTypes[1] == String::class.java
        }

        if (startRootProcessMethod != null) {
            attempt("hook RootServiceManager.startRootProcess", silent = true) {
                module.hookTracked(
                    startRootProcessMethod,
                    idPrefix = "rootfix-startRootProcess",
                    deoptimize = true
                ).intercept { chain ->
                    val task = chain.proceed() ?: return@intercept null
                    if (Proxy.isProxyClass(task.javaClass)) return@intercept task
                    wrapShellTask(task, classLoader, originPath, installedApkPath)
                }
            }
        }
    }

    fun wrapShellTask(
        originalTask: Any,
        classLoader: ClassLoader,
        originPath: String,
        installedApkPath: String
    ): Any {
        val interfaces = originalTask.javaClass.interfaces
        if (interfaces.isEmpty()) return originalTask

        return Proxy.newProxyInstance(
            classLoader,
            interfaces,
            object : InvocationHandler {
                override fun invoke(proxy: Any, method: Method, args: Array<out Any>?): Any? {
                    if (args != null && args.size == 3 &&
                        args[0] is OutputStream && args[1] is java.io.InputStream && args[2] is java.io.InputStream
                    ) {
                        val originalStdin = args[0] as OutputStream
                        val interceptingStdin = createInterceptingOutputStream(originalStdin, originPath, installedApkPath)
                        val newArgs = args.clone()
                        @Suppress("UNCHECKED_CAST")
                        (newArgs as Array<Any>)[0] = interceptingStdin
                        return method.invoke(originalTask, *newArgs)
                    }
                    return if (args != null) method.invoke(originalTask, *args) else method.invoke(originalTask)
                }
            }
        )
    }

    fun createInterceptingOutputStream(
        originalStdin: OutputStream,
        originPath: String,
        installedApkPath: String
    ): OutputStream {
        return object : FilterOutputStream(originalStdin) {
            private val buffer = ByteArrayOutputStream()

            override fun write(b: Int) {
                if (b == '\n'.code) {
                    flushBuffer()
                } else {
                    buffer.write(b)
                }
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                var start = off
                val end = off + len
                for (i in off until end) {
                    if (b[i] == '\n'.code.toByte()) {
                        buffer.write(b, start, i - start)
                        flushBuffer()
                        start = i + 1
                    }
                }
                if (start < end) {
                    buffer.write(b, start, end - start)
                }
            }

            override fun flush() {
                flushBuffer()
                out.flush()
            }

            private fun flushBuffer() {
                if (buffer.size() == 0) return
                val cmdStr = buffer.toString(Charsets.UTF_8.name())
                buffer.reset()

                val transformed = transformRootCommand(cmdStr, originPath, installedApkPath)
                val bytes = (transformed + "\n").toByteArray(Charsets.UTF_8)
                out.write(bytes)
            }
        }
    }

    fun transformRootCommand(cmd: String, originPath: String, installedApkPath: String): String {
        val trimmed = cmd.trim()
        if (!trimmed.contains("RootServerMain") || trimmed.contains("mount -o bind") || trimmed.contains("mount --bind")) {
            return cmd
        }

        Log.i(Consts.TAG, "RootServiceFixHook: intercepting RootServerMain command: $trimmed")

        var innerCmd = trimmed
        if (innerCmd.startsWith("(") && innerCmd.endsWith(")&")) {
            innerCmd = innerCmd.substring(1, innerCmd.length - 2).trim()
        } else if (innerCmd.startsWith("(") && innerCmd.endsWith(")")) {
            innerCmd = innerCmd.substring(1, innerCmd.length - 1).trim()
        }

        val redirectSuffix = ">/dev/null 2>&1"
        if (innerCmd.endsWith(redirectSuffix)) {
            innerCmd = innerCmd.substring(0, innerCmd.length - redirectSuffix.length).trim()
        }

        val escapedInnerCmd = innerCmd.replace("\"", "\\\"")
        val mountCmd = "(mount -o rprivate none / 2>/dev/null || mount --make-rprivate / 2>/dev/null || true) && (mount -o bind '$originPath' '$installedApkPath' || mount --bind '$originPath' '$installedApkPath')"
        val wrapped = "(if command -v unshare >/dev/null 2>&1; then unshare -m sh -c \"$mountCmd && $escapedInnerCmd\"; else toybox unshare -m sh -c \"$mountCmd && $escapedInnerCmd\"; fi >/dev/null 2>&1)&"

        Log.i(Consts.TAG, "RootServiceFixHook: transformed command to: $wrapped")
        return wrapped
    }

    private fun hookGetPackageCodePath(module: HookContext, originPath: String) {
        val contextWrapperClass = android.content.ContextWrapper::class.java

        attempt("hook ContextWrapper.getPackageCodePath", silent = true) {
            val method = contextWrapperClass.getDeclaredMethod("getPackageCodePath")
            module.hookTracked(
                method,
                idPrefix = "rootfix-getPackageCodePath",
                deoptimize = true
            ).intercept { chain ->
                val original = chain.proceed() as? String
                if (original != null && original != originPath) {
                    Log.d(Consts.TAG, "RootServiceFixHook: redirected getPackageCodePath: $original → $originPath")
                }
                originPath
            }
        }

        attempt("hook ContextWrapper.getPackageResourcePath", silent = true) {
            val method = contextWrapperClass.getDeclaredMethod("getPackageResourcePath")
            module.hookTracked(
                method,
                idPrefix = "rootfix-getPackageResourcePath",
                deoptimize = true
            ).intercept { chain ->
                val original = chain.proceed() as? String
                if (original != null && original != originPath) {
                    Log.d(Consts.TAG, "RootServiceFixHook: redirected getPackageResourcePath: $original → $originPath")
                }
                originPath
            }
        }
    }

    private fun patchSourceDir(appInfo: ApplicationInfo, originPath: String) {
        attempt("patch ApplicationInfo.sourceDir for RootService", silent = true) {
            val currentSource = appInfo.sourceDir
            if (currentSource == originPath) {
                Log.d(Consts.TAG, "RootServiceFixHook: sourceDir already points to origin APK")
                return@attempt
            }

            ApplicationInfo::class.java.getDeclaredField("sourceDir").apply {
                isAccessible = true
                set(appInfo, originPath)
            }
            ApplicationInfo::class.java.getDeclaredField("publicSourceDir").apply {
                isAccessible = true
                set(appInfo, originPath)
            }

            Log.i(Consts.TAG, "RootServiceFixHook: patched sourceDir $currentSource → $originPath")
        }
    }
}
