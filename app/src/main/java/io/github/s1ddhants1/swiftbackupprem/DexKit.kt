@file:JvmName("DexKit")

package io.github.s1ddhants1.swiftbackupprem

data class VersionClasses(
    val clientId: String,
    val homeViewModel: String,
    val authUser: String,
    val anonUser: String,
    val oauthHelper: String? = null,
    val authRequestBuilder: String? = null,
    val appBackup: String? = null,
    val appMetadataXml: String? = null,
    val firebaseWatcher: String? = null,
    val fireSynchronizer: String? = null,
    val fireSynchronizerSuccess: String? = null
)

val versionMap = mapOf(
    561 to VersionClasses("kf.s0", "org.swiftapps.swiftbackup.home.a", "org.swiftapps.swiftbackup.common.a3", "org.swiftapps.swiftbackup.anonymous.a"),
    569 to VersionClasses("rf.r0", "org.swiftapps.swiftbackup.home.a", "org.swiftapps.swiftbackup.common.a3", "org.swiftapps.swiftbackup.anonymous.a"),
    590 to VersionClasses("eh.u", "org.swiftapps.swiftbackup.home.a", "org.swiftapps.swiftbackup.common.a3", "org.swiftapps.swiftbackup.anonymous.a"),
    620 to VersionClasses("defpackage.gn5", "defpackage.c64", "defpackage.d45", "defpackage.b45", "defpackage.uj", "defpackage.c90", "defpackage.hk", "defpackage.cu", "defpackage.gg3", "defpackage.cf3", "defpackage.xe3"),
)
