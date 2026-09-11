package io.github.s1ddhants1.swiftbackupprem

import android.app.Application
import android.util.Log
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import io.github.s1ddhants1.swiftbackupprem.util.attempt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class App : Application() {
    companion object {
        private val _serviceState = MutableStateFlow<XposedService?>(null)
        val serviceState = _serviceState.asStateFlow()
    }

    override fun onCreate() {
        super.onCreate()
        attempt("register XposedServiceHelper listener") {
            XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
                override fun onServiceBind(service: XposedService) {
                    Log.i(Consts.TAG, "Connected to Xposed Framework: ${service.frameworkName} v${service.frameworkVersion} (API ${service.apiVersion})")
                    _serviceState.value = service
                }

                override fun onServiceDied(service: XposedService) {
                    Log.w(Consts.TAG, "Xposed Framework Service disconnected")
                    if (_serviceState.value == service) {
                        _serviceState.value = null
                    }
                }
            })
        }
    }
}
