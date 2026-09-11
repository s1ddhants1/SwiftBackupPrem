package io.github.s1ddhants1.swiftbackupprem.hook.experimental.cloudproviders

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.annotation.Keep
import io.github.s1ddhants1.swiftbackupprem.Consts
import io.github.s1ddhants1.swiftbackupprem.hook.experimental.cloudproviders.CloudHttpHelper.findFirstString
import io.github.s1ddhants1.swiftbackupprem.util.attempt
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.xml.parsers.DocumentBuilderFactory

@Keep
object S3Scanner : CloudScanner {
    private const val TAG = Consts.TAG
    override val providerName: String = "S3"

    override fun isConfigured(context: Context, prefs: SharedPreferences): Boolean {
        val bucket = resolveBucket(prefs)
        val accessKey = resolveAccessKey(prefs)
        val secretKey = resolveSecretKey(prefs)
        return !bucket.isNullOrBlank() && !accessKey.isNullOrBlank() && !secretKey.isNullOrBlank()
    }

    private fun resolveBucket(prefs: SharedPreferences): String? =
        prefs.findFirstString("s3_bucket", "s3_bucket_name", "s3_bucket_id")

    private fun resolveAccessKey(prefs: SharedPreferences): String? =
        prefs.findFirstString("s3_access_key", "s3_access_key_id", "s3_key", "s3_api_key")

    private fun resolveSecretKey(prefs: SharedPreferences): String? =
        prefs.findFirstString("s3_secret_key", "s3_secret_access_key", "s3_secret")

    private fun resolveEndpoint(prefs: SharedPreferences): String {
        val clean = prefs.findFirstString("s3_endpoint", "s3_custom_endpoint", "s3_url", "s3_host")
        return if (!clean.isNullOrBlank()) {
            if (clean.startsWith("http://") || clean.startsWith("https://")) clean else "https://$clean"
        } else {
            "https://s3.amazonaws.com"
        }
    }

    private fun resolveRegion(prefs: SharedPreferences, endpoint: String): String {
        val reg = prefs.findFirstString("s3_region", "s3_aws_region")
        if (!reg.isNullOrBlank()) return reg
        if (endpoint.contains(".r2.cloudflarestorage.com")) return "auto"
        if (endpoint.contains("s3.") && endpoint.contains(".amazonaws.com")) {
            val middle = endpoint.substringAfter("s3.").substringBefore(".amazonaws.com")
            if (middle.isNotBlank() && middle != "s3") return middle
        }
        return "us-east-1"
    }

    private fun resolvePrefix(prefs: SharedPreferences): String =
        prefs.findFirstString("s3_path", "s3_prefix", "s3_folder")?.trimStart('/') ?: ""

    override fun listFiles(context: Context, prefs: SharedPreferences): List<CloudFileItem> {
        val bucket = resolveBucket(prefs) ?: return emptyList()
        val accessKey = resolveAccessKey(prefs) ?: return emptyList()
        val secretKey = resolveSecretKey(prefs) ?: return emptyList()
        val endpoint = resolveEndpoint(prefs)
        val region = resolveRegion(prefs, endpoint)
        val prefix = resolvePrefix(prefs)

        val items = mutableListOf<CloudFileItem>()
        var continuationToken: String? = null

        do {
            val queryParams = mutableMapOf<String, String>()
            queryParams["list-type"] = "2"
            if (prefix.isNotBlank()) queryParams["prefix"] = prefix
            if (continuationToken != null) queryParams["continuation-token"] = continuationToken

            val (urlStr, authHeaders) = buildSignedRequest(
                method = "GET",
                endpoint = endpoint,
                bucket = bucket,
                path = "",
                queryParams = queryParams,
                accessKey = accessKey,
                secretKey = secretKey,
                region = region
            )

            val xmlResp = executeHttp("GET", urlStr, authHeaders) ?: break
            val parsed = parseListBucketResult(xmlResp, items)
            continuationToken = parsed
        } while (continuationToken != null)

        Log.d(TAG, "[S3Scanner] Discovered ${items.size} backup items in S3 bucket '$bucket'")
        return items
    }

    private fun parseListBucketResult(xmlText: String, items: MutableList<CloudFileItem>): String? {
        return attempt("parse S3 XML", silent = true) {
            val dbFactory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            val doc = dbFactory.newDocumentBuilder().parse(ByteArrayInputStream(xmlText.toByteArray(StandardCharsets.UTF_8)))
            doc.documentElement.normalize()

            val contentsNodes = doc.getElementsByTagName("Contents")
            for (i in 0 until contentsNodes.length) {
                val contentElem = contentsNodes.item(i) as? Element ?: continue
                val key = contentElem.getElementsByTagName("Key").item(0)?.textContent ?: continue
                val size = contentElem.getElementsByTagName("Size").item(0)?.textContent?.toLongOrNull() ?: 0L
                val fileName = key.substringAfterLast("/")

                if (fileName.isNotBlank() && !key.endsWith("/")) {
                    items.add(
                        CloudFileItem(
                            id = key,
                            name = fileName,
                            size = size,
                            provider = providerName
                        )
                    )
                }
            }

            val nextToken = doc.getElementsByTagName("NextContinuationToken").item(0)?.textContent
            nextToken?.takeIf { it.isNotBlank() }
        }
    }

    override fun downloadFileText(context: Context, prefs: SharedPreferences, fileItem: CloudFileItem): String? {
        val bucket = resolveBucket(prefs) ?: return null
        val accessKey = resolveAccessKey(prefs) ?: return null
        val secretKey = resolveSecretKey(prefs) ?: return null
        val endpoint = resolveEndpoint(prefs)
        val region = resolveRegion(prefs, endpoint)

        val (urlStr, authHeaders) = buildSignedRequest(
            method = "GET",
            endpoint = endpoint,
            bucket = bucket,
            path = fileItem.id,
            queryParams = emptyMap(),
            accessKey = accessKey,
            secretKey = secretKey,
            region = region
        )

        return executeHttp("GET", urlStr, authHeaders)
    }

    private fun buildSignedRequest(
        method: String,
        endpoint: String,
        bucket: String,
        path: String,
        queryParams: Map<String, String>,
        accessKey: String,
        secretKey: String,
        region: String
    ): Pair<String, Map<String, String>> {
        val url = URL(endpoint)
        val host = if (url.host == "s3.amazonaws.com") "$bucket.s3.amazonaws.com" else url.host
        val basePath = if (url.host == "s3.amazonaws.com") "" else "/$bucket"
        val cleanKeyPath = if (path.isNotBlank()) "/${path.trimStart('/')}" else ""
        val canonicalUri = if ("$basePath$cleanKeyPath".isBlank()) "/" else "$basePath$cleanKeyPath"

        val sortedQuery = queryParams.toSortedMap().map { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }.joinToString("&")

        val querySuffix = if (sortedQuery.isNotBlank()) "?$sortedQuery" else ""
        val fullUrlStr = "${url.protocol}://$host$canonicalUri$querySuffix"

        val now = Date()
        val amzDate = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(now)
        val dateStamp = SimpleDateFormat("yyyyMMdd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(now)

        val payloadHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        val canonicalHeaders = "host:$host\nx-amz-content-sha256:$payloadHash\nx-amz-date:$amzDate\n"
        val signedHeaders = "host;x-amz-content-sha256;x-amz-date"

        val canonicalRequest = "$method\n$canonicalUri\n$sortedQuery\n$canonicalHeaders\n$signedHeaders\n$payloadHash"
        val canonicalRequestHash = sha256Hex(canonicalRequest)

        val algorithm = "AWS4-HMAC-SHA256"
        val credentialScope = "$dateStamp/$region/s3/aws4_request"
        val stringToSign = "$algorithm\n$amzDate\n$credentialScope\n$canonicalRequestHash"

        val signingKey = getSignatureKey(secretKey, dateStamp, region, "s3")
        val signature = hmacSha256Hex(signingKey, stringToSign)

        val authHeader = "$algorithm Credential=$accessKey/$credentialScope, SignedHeaders=$signedHeaders, Signature=$signature"

        val headers = mapOf(
            "Host" to host,
            "x-amz-date" to amzDate,
            "x-amz-content-sha256" to payloadHash,
            "Authorization" to authHeader
        )

        return Pair(fullUrlStr, headers)
    }

    private fun executeHttp(method: String, urlStr: String, headers: Map<String, String>): String? =
        if (method == "GET") CloudHttpHelper.executeGet(urlStr, headers)
        else CloudHttpHelper.executePost(urlStr, headers, method = method)

    private fun sha256Hex(data: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val hash = md.digest(data.toByteArray(StandardCharsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }

    private fun hmacSha256(key: ByteArray, data: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data.toByteArray(StandardCharsets.UTF_8))
    }

    private fun hmacSha256Hex(key: ByteArray, data: String): String {
        return hmacSha256(key, data).joinToString("") { "%02x".format(it) }
    }

    private fun getSignatureKey(key: String, dateStamp: String, regionName: String, serviceName: String): ByteArray {
        val kSecret = ("AWS4$key").toByteArray(StandardCharsets.UTF_8)
        val kDate = hmacSha256(kSecret, dateStamp)
        val kRegion = hmacSha256(kDate, regionName)
        val kService = hmacSha256(kRegion, serviceName)
        return hmacSha256(kService, "aws4_request")
    }
}
