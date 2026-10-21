package io.github.s1ddhants1.swiftbackupprem.hook

import android.content.Context
import android.util.Log
import androidx.annotation.Keep
import io.github.s1ddhants1.swiftbackupprem.Consts
import io.github.s1ddhants1.swiftbackupprem.util.LSPatchHelper
import io.github.s1ddhants1.swiftbackupprem.util.PreferencesManager
import io.github.s1ddhants1.swiftbackupprem.util.attempt
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy

@Keep
object ShizukuServiceFixHook : HookHandler {

    data class SbaManagerMethods(
        val getterMethod: Method?,
        val readModeMethod: Method?,
        val opModeMethod: Method?
    )

    override fun apply(
        module: HookContext,
        context: Context,
        classLoader: ClassLoader,
        targets: ResolvedTargets,
        prefs: PreferencesManager
    ) {
        val originPath = LSPatchHelper.resolveOriginApkPath(classLoader)
        if (originPath == null) {
            Log.w(Consts.TAG, "ShizukuServiceFixHook: could not resolve origin APK path, skipping")
            return
        }

        val isIntegrated = LSPatchHelper.isIntegratedMode(context)
        Log.i(Consts.TAG, "ShizukuServiceFixHook: origin APK: $originPath, isIntegrated: $isIntegrated")

        val sourceDir = context.applicationInfo.sourceDir ?: originPath

        hookSbaMethods(module, classLoader, sourceDir, isIntegrated)
    }

    fun hookSbaMethods(
        module: HookContext,
        classLoader: ClassLoader,
        sourceDir: String,
        isIntegrated: Boolean
    ) {
        hookSbaBindMethod(module, classLoader, sourceDir, isIntegrated)
        hookSbaManagerMethods(module, classLoader, sourceDir, isIntegrated)
    }

    private fun hookSbaBindMethod(
        module: HookContext,
        classLoader: ClassLoader,
        sourceDir: String,
        isIntegrated: Boolean
    ) {
        val bindMethod = findSbaBindMethod(classLoader, sourceDir) ?: return
        val bindClass = bindMethod.declaringClass
        val monitorField = attempt("find u17 monitor field", silent = true) {
            bindClass.declaredFields.firstOrNull { Modifier.isStatic(it.modifiers) && it.type == Any::class.java }?.apply {
                isAccessible = true
            }
        }

        attempt("hook SBA Shizuku bind method for crash protection", silent = true) {
            module.hookTracked(
                bindMethod,
                idPrefix = "shizukufix-sba-bind",
                deoptimize = true
            ).intercept { chain ->
                if (isIntegrated) {
                    Log.i(Consts.TAG, "ShizukuServiceFixHook: skipped SBA Shizuku bind in Integrated Mode")
                    null
                } else {
                    attempt("SBA Shizuku bind with monitor lock", silent = true) {
                        val monitor = monitorField?.get(null)
                        if (monitor != null) {
                            synchronized(monitor) {
                                chain.proceed()
                            }
                        } else {
                            chain.proceed()
                        }
                    }
                    null
                }
            }
        }
    }

    private fun hookSbaManagerMethods(
        module: HookContext,
        classLoader: ClassLoader,
        sourceDir: String,
        isIntegrated: Boolean
    ) {
        val methods = findSbaManagerMethods(classLoader, sourceDir) ?: return

        methods.getterMethod?.let { getterMethod ->
            attempt("hook Shizuku SBA service getter for crash protection", silent = true) {
                module.hookTracked(
                    getterMethod,
                    idPrefix = "shizukufix-sba-get",
                    deoptimize = true
                ).intercept { chain ->
                    if (isIntegrated) {
                        Log.d(Consts.TAG, "ShizukuServiceFixHook: returning dummy SBA service proxy (Integrated Mode)")
                        createDummySbaProxy(getterMethod.returnType, classLoader)
                    } else {
                        val result = attempt("get Shizuku SBA service (crash-protected)", silent = true) {
                            chain.proceed()
                        }
                        result ?: createDummySbaProxy(getterMethod.returnType, classLoader)
                    }
                }
            }
        }

        methods.readModeMethod?.let { readModeMethod ->
            if (isIntegrated) {
                attempt("hook SBA read execution mode for Integrated Mode redirection", silent = true) {
                    module.hookTracked(
                        readModeMethod,
                        idPrefix = "shizukufix-sba-read-mode",
                        deoptimize = true
                    ).intercept { chain ->
                        val original = chain.proceed() as? Enum<*>
                        if (original != null && original.name == "Shizuku") {
                            Log.i(Consts.TAG, "ShizukuServiceFixHook: redirected SBA read mode Shizuku -> Local")
                            redirectEnumToLocal(original)
                        } else {
                            original
                        }
                    }
                }
            }
        }

        methods.opModeMethod?.let { opModeMethod ->
            if (isIntegrated) {
                attempt("hook SBA op execution mode for Integrated Mode redirection", silent = true) {
                    module.hookTracked(
                        opModeMethod,
                        idPrefix = "shizukufix-sba-op-mode",
                        deoptimize = true
                    ).intercept { chain ->
                        val original = chain.proceed() as? Enum<*>
                        if (original != null && original.name == "Shizuku") {
                            Log.i(Consts.TAG, "ShizukuServiceFixHook: redirected SBA op mode Shizuku -> Local")
                            redirectEnumToLocal(original)
                        } else {
                            original
                        }
                    }
                }
            }
        }
    }

    fun redirectEnumToLocal(enumVal: Enum<*>): Enum<*> {
        if (enumVal.name != "Shizuku") return enumVal
        return attempt("redirect enum to Local", silent = true) {
            val enumClass = enumVal.javaClass
            val localConstants = enumClass.enumConstants
            localConstants?.firstOrNull { it.name == "Local" }
        } ?: enumVal
    }

    fun createDummySbaProxy(returnType: Class<*>, classLoader: ClassLoader): Any? {
        if (!returnType.isInterface) return null
        return attempt("create dummy SBA service proxy", silent = true) {
            Proxy.newProxyInstance(
                classLoader,
                arrayOf(returnType)
            ) { _, method, args ->
                when (method.name) {
                    "toString" -> "DummySbaServiceProxy"
                    "hashCode" -> 1
                    "equals" -> args?.getOrNull(0) != null
                    "asBinder" -> object : android.os.Binder() {
                        override fun isBinderAlive() = false
                        override fun pingBinder() = false
                    }
                    else -> when (method.returnType) {
                        Boolean::class.javaPrimitiveType, Boolean::class.javaObjectType -> false
                        Int::class.javaPrimitiveType, Int::class.javaObjectType -> 0
                        Long::class.javaPrimitiveType, Long::class.javaObjectType -> -1L
                        String::class.java -> ""
                        android.os.IBinder::class.java -> object : android.os.Binder() {
                            override fun isBinderAlive() = false
                            override fun pingBinder() = false
                        }
                        else -> null
                    }
                }
            }
        }
    }

    fun findSbaBindMethod(classLoader: ClassLoader, sourceDir: String): Method? {
        return attempt("find SBA Shizuku bind method via DexKit", silent = true) {
            DexKitBridge.create(sourceDir).use { bridge ->
                val methodData = bridge.findMethod {
                    matcher {
                        usingStrings("swiftbackup_sba", "sba")
                    }
                }.firstOrNull()

                val method = methodData?.getMethodInstance(classLoader)
                if (method != null) {
                    Log.i(Consts.TAG, "ShizukuServiceFixHook: found SBA bind method: ${method.declaringClass.name}.${method.name}")
                    return@use method
                }

                val results = bridge.findClass {
                    matcher {
                        usingStrings("swiftbackup_sba", "sba")
                    }
                }
                val bindClass = results.firstOrNull()?.getInstance(classLoader) ?: return@use null
                bindClass.declaredMethods.firstOrNull { m ->
                    Modifier.isStatic(m.modifiers) &&
                        m.parameterTypes.isEmpty() &&
                        m.returnType == Void.TYPE
                }
            }
        }
    }

    fun findSbaManagerMethods(classLoader: ClassLoader, sourceDir: String): SbaManagerMethods? {
        return attempt("find SBA manager methods via DexKit", silent = true) {
            DexKitBridge.create(sourceDir).use { bridge ->
                val getterMethod = bridge.findMethod {
                    matcher {
                        usingStrings("Shizuku SBA service is unavailable for SBA ")
                    }
                }.firstOrNull()?.getMethodInstance(classLoader)

                val readModeMethod = bridge.findMethod {
                    matcher {
                        usingStrings("Privileged access is required for SBA file read: ")
                    }
                }.firstOrNull()?.getMethodInstance(classLoader)

                val opModeMethod = bridge.findMethod {
                    matcher {
                        usingStrings("Root access is required for SBA ")
                    }
                }.firstOrNull()?.getMethodInstance(classLoader)

                var finalGetter = getterMethod
                var finalReadMode = readModeMethod
                var finalOpMode = opModeMethod

                if (finalGetter == null || finalReadMode == null || finalOpMode == null) {
                    val managerClass = bridge.findClass {
                        matcher {
                            usingStrings("SbaShizukuServiceManager")
                        }
                    }.firstOrNull()?.getInstance(classLoader)

                    if (managerClass != null) {
                        if (finalGetter == null) {
                            finalGetter = managerClass.declaredMethods.firstOrNull { m ->
                                Modifier.isStatic(m.modifiers) &&
                                    m.parameterTypes.size == 1 &&
                                    m.parameterTypes[0] == String::class.java &&
                                    m.returnType != Void.TYPE &&
                                    m.returnType.isInterface
                            }
                        }
                        if (finalReadMode == null) {
                            finalReadMode = managerClass.declaredMethods.firstOrNull { m ->
                                Modifier.isStatic(m.modifiers) &&
                                    m.parameterTypes.size == 1 &&
                                    m.parameterTypes[0] != String::class.java &&
                                    m.returnType.isEnum
                            }
                        }
                        if (finalOpMode == null) {
                            finalOpMode = managerClass.declaredMethods.firstOrNull { m ->
                                Modifier.isStatic(m.modifiers) &&
                                    m.parameterTypes.size == 3 &&
                                    m.returnType.isEnum
                            }
                        }
                    }
                }

                Log.i(
                    Consts.TAG,
                    "ShizukuServiceFixHook: resolved SBA manager methods - getter=${finalGetter?.name}, readMode=${finalReadMode?.name}, opMode=${finalOpMode?.name}"
                )

                SbaManagerMethods(finalGetter, finalReadMode, finalOpMode)
            }
        }
    }
}
