package io.github.s1ddhants1.swiftbackupprem.util

object FirebaseConfigValidator {

    private val PROJECT_ID_REGEX = Regex("^[a-z0-9][a-z0-9-]{2,28}[a-z0-9]$")
    private val DATABASE_URL_REGEX = Regex(
        "^https://[a-zA-Z0-9-]+(\\.[a-zA-Z0-9-]+)*\\.(firebaseio\\.com|firebasedatabase\\.app)/?$",
        RegexOption.IGNORE_CASE
    )
    private val GOOGLE_APP_ID_REGEX = Regex("^1:\\d{6,16}:android:[0-9a-fA-F]{10,64}$")
    private val GOOGLE_API_KEY_REGEX = Regex("^AIza[0-9A-Za-z_-]{35}$")
    private val SENDER_ID_REGEX = Regex("^\\d{6,16}$")
    private val CLIENT_ID_REGEX = Regex(
        "^\\d+-[a-zA-Z0-9_-]+\\.apps\\.googleusercontent\\.com$",
        RegexOption.IGNORE_CASE
    )
    private val STORAGE_BUCKET_REGEX = Regex(
        "^[a-z0-9][a-z0-9_.-]{1,61}[a-z0-9]$",
        RegexOption.IGNORE_CASE
    )

    fun isValidProjectId(value: String): Boolean =
        PROJECT_ID_REGEX.matches(value.trim())

    fun isValidDatabaseUrl(value: String): Boolean =
        DATABASE_URL_REGEX.matches(value.trim())

    fun isValidAppId(value: String): Boolean =
        GOOGLE_APP_ID_REGEX.matches(value.trim())

    fun isValidApiKey(value: String): Boolean =
        GOOGLE_API_KEY_REGEX.matches(value.trim())

    fun isValidSenderId(value: String): Boolean =
        SENDER_ID_REGEX.matches(value.trim())

    fun isValidClientId(value: String): Boolean =
        CLIENT_ID_REGEX.matches(value.trim())

    fun isValidStorageBucket(value: String): Boolean =
        value.isBlank() || STORAGE_BUCKET_REGEX.matches(value.trim())

    fun isValidConfig(
        projectId: String,
        databaseUrl: String,
        appId: String,
        apiKey: String,
        senderId: String,
        clientId: String,
        storageBucket: String = ""
    ): Boolean =
        isValidProjectId(projectId) &&
        isValidDatabaseUrl(databaseUrl) &&
        isValidAppId(appId) &&
        isValidApiKey(apiKey) &&
        isValidSenderId(senderId) &&
        isValidClientId(clientId) &&
        isValidStorageBucket(storageBucket)
}
