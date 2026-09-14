#include <jni.h>
#include <string.h>
#include <stdbool.h>
#include <dlfcn.h>
#include "hook.h"

static HookFunType hook_func = NULL;

jint (*backup)(JavaVM *vm, void *reserved);

jint fakeLoad(JavaVM *vm, void *reserved) {
    (void) vm;
    (void) reserved;
    return JNI_VERSION_1_6;
}

bool ends_with(const char *a, const char *b) {
    size_t len = strlen(a);
    size_t len2 = strlen(b);
    if (len2 > len) return false;
    return strncmp(a + len - len2, b, len2) == 0;
}

static bool s_native_hooked = false;

void on_library_loaded(const char *name, void *handle) {
    if (name && hook_func && !s_native_hooked &&
        (ends_with(name, "libnative-lib.so") || strstr(name, "libnative-lib.so") != NULL)) {
        void *target = dlsym(handle, "JNI_OnLoad");
        if (target) {
            hook_func(target, (void *) fakeLoad, (void **) &backup);
            s_native_hooked = true;
        }
    }
}

JNIEXPORT __attribute__((visibility("default"))) __attribute__((used)) NativeOnModuleLoaded native_init(const NativeAPIEntries *entries) {
    if (!entries) return NULL;
    hook_func = entries->hook_func;
    return on_library_loaded;
}
