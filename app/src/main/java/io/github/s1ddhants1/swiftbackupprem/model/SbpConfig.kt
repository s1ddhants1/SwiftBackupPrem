package io.github.s1ddhants1.swiftbackupprem.model

import kotlinx.serialization.Serializable

@Serializable
data class SbpConfig(
    val enablePremium: Boolean = true,
    val disableTelemetry: Boolean = true,
    val enableGoogleDriveScope: Boolean = false,
    val enableCloudDiscovery: Boolean = false,
    val enableSnapshotInjection: Boolean = false,
    val enableBackupRebuilder: Boolean = false,
    val syncMetadataToFirebase: Boolean = false,
    val unlockLocalCloudFeatures: Boolean = false,
    val customFirebaseApp: Boolean = false,
    val googleAppId: String = "",
    val googleApiKey: String = "",
    val firebaseDatabaseUrl: String = "",
    val gcmDefaultSenderId: String = "",
    val googleStorageBucket: String = "",
    val projectId: String = "",
    val clientId: String = ""
) {
    val isCompleteFirebaseConfig: Boolean
        get() = googleAppId.isNotBlank() &&
                googleApiKey.isNotBlank() &&
                firebaseDatabaseUrl.isNotBlank() &&
                gcmDefaultSenderId.isNotBlank() &&
                projectId.isNotBlank() &&
                clientId.isNotBlank()
}
