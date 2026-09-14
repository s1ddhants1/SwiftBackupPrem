# Preserve line numbers and source file attributes for debugging stack traces
-keepattributes SourceFile,LineNumberTable,*Annotation*,Signature,InnerClasses,EnclosingMethod,Exceptions

# LibXposed Modern API rules
-dontwarn io.github.libxposed.**
-adaptresourcefilecontents META-INF/xposed/java_init.list
-adaptresourcefilecontents META-INF/xposed/native_init.list
-keep public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
    public void on*(...);
}

# Keep XposedProvider for LSPosed Framework Service IPC
-keep class io.github.libxposed.service.XposedProvider { *; }

# AndroidX Preference (compileOnly provided by target app)
-dontwarn androidx.preference.**
-keep class io.github.s1ddhants1.swiftbackupprem.ui.settings.** { *; }

# Keep DexKit bridge classes used by native C++ (libdexkit.so) JNI reflection
-keep class org.luckypray.dexkit.** { *; }
-keepclassmembers class org.luckypray.dexkit.** { *; }

# Keep all native JNI method declarations
-keepclasseswithmembernames class * {
    native <methods>;
}

# Optimization iterations
-optimizationpasses 5

# Broaden access modifiers to increase results during optimization
-allowaccessmodification
