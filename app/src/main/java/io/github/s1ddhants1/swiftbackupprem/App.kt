package io.github.s1ddhants1.swiftbackupprem

import android.app.Application
import androidx.annotation.Keep
import io.github.s1ddhants1.swiftbackupprem.util.LSPatchHelper

class App : Application() {
    companion object {
        @Keep
        @JvmStatic
        fun isModuleActive(): Boolean = false
    }

    override fun onCreate() {
        super.onCreate()
        FrameworkHelper.init(this)
        LSPatchHelper.requestServicePush(this)
    }
}
