package io.github.s1ddhants1.swiftbackupprem.hook

import androidx.annotation.Keep

@Keep
data class ResolvedTargets(
    val clientIdClass: Class<*>? = null,
    val vClass: Class<*>? = null,
    val cloudGmsClass: Class<*>? = null,
    val homeViewModelClass: Class<*>? = null,
    val authUserClass: Class<*>? = null,
    val anonUserClass: Class<*>? = null,
    val oauthHelperClass: Class<*>? = null,
    val authRequestBuilderClass: Class<*>? = null,
    val appBackupClass: Class<*>? = null,
    val appMetadataXmlClass: Class<*>? = null,
    val fireSynchronizerClass: Class<*>? = null,
    val firebaseWatcherClass: Class<*>? = null
) {
    val isFullyResolved: Boolean
        get() = listOf(clientIdClass, vClass, homeViewModelClass, authUserClass).all { it != null }
}

