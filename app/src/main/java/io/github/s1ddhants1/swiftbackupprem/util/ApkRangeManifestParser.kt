package io.github.s1ddhants1.swiftbackupprem.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.annotation.Keep
import io.github.s1ddhants1.swiftbackupprem.Consts
import io.github.s1ddhants1.swiftbackupprem.hook.experimental.cloudproviders.CloudFileItem
import io.github.s1ddhants1.swiftbackupprem.hook.experimental.cloudproviders.CloudScanner
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.zip.Inflater

@Keep
object ApkRangeManifestParser {

    private const val TAG = Consts.TAG

    data class ApkManifestInfo(
        val packageName: String? = null,
        val versionCode: Long = 1L,
        val versionName: String = "1.0",
        val appLabel: String? = null
    )

    fun parseFromScanner(
        context: Context,
        prefs: SharedPreferences,
        scanner: CloudScanner,
        apkItem: CloudFileItem
    ): ApkManifestInfo? = attempt("parse remote APK manifest") {
        val fileSize = apkItem.size
        Log.d(TAG, "[ApkRangeManifestParser] Inspecting remote APK: ${apkItem.name}, size=$fileSize")
        if (fileSize < 200) return@attempt null

        val tailSize = minOf(65536L, fileSize)
        val tailStart = fileSize - tailSize
        val tailBytes = scanner.downloadByteRange(context, prefs, apkItem, tailStart, fileSize - 1)
        if (tailBytes == null) {
            Log.w(TAG, "[ApkRangeManifestParser] Failed to download tail bytes from ${apkItem.name}")
            return@attempt null
        }

        var eocdOffsetInTail = -1
        for (i in tailBytes.size - 22 downTo 0) {
            if (tailBytes[i] == 0x50.toByte() &&
                tailBytes[i + 1] == 0x4b.toByte() &&
                tailBytes[i + 2] == 0x05.toByte() &&
                tailBytes[i + 3] == 0x06.toByte()
            ) {
                eocdOffsetInTail = i
                break
            }
        }
        if (eocdOffsetInTail < 0) {
            Log.w(TAG, "[ApkRangeManifestParser] EOCD signature not found in tail of ${apkItem.name}")
            return@attempt null
        }

        val eocdBuf = ByteBuffer.wrap(tailBytes).order(ByteOrder.LITTLE_ENDIAN)
        eocdBuf.position(eocdOffsetInTail + 12)
        val cdSize = eocdBuf.int.toLong() and 0xFFFFFFFFL
        val cdOffset = eocdBuf.int.toLong() and 0xFFFFFFFFL

        Log.d(TAG, "[ApkRangeManifestParser] Found EOCD in ${apkItem.name}: cdSize=$cdSize, cdOffset=$cdOffset")
        if (cdSize <= 0 || cdOffset < 0 || cdOffset + cdSize > fileSize) return@attempt null

        val cdBytes = if (cdOffset >= tailStart && (cdOffset + cdSize) <= fileSize) {
            val startInTail = (cdOffset - tailStart).toInt()
            tailBytes.copyOfRange(startInTail, startInTail + cdSize.toInt())
        } else {
            scanner.downloadByteRange(context, prefs, apkItem, cdOffset, cdOffset + cdSize - 1)
                ?: run {
                    Log.w(TAG, "[ApkRangeManifestParser] Failed to fetch CD bytes for ${apkItem.name}")
                    return@attempt null
                }
        }

        var pos = 0
        var manifestEntry: ManifestZipEntry? = null
        val cdBuf = ByteBuffer.wrap(cdBytes).order(ByteOrder.LITTLE_ENDIAN)

        while (pos + 46 <= cdBytes.size) {
            cdBuf.position(pos)
            val magic = cdBuf.int
            if (magic != 0x02014b50) break

            cdBuf.position(pos + 10)
            val method = cdBuf.short.toInt() and 0xFFFF
            cdBuf.position(pos + 20)
            val compSize = cdBuf.int.toLong() and 0xFFFFFFFFL
            val uncompSize = cdBuf.int.toLong() and 0xFFFFFFFFL
            val fnLen = cdBuf.short.toInt() and 0xFFFF
            val extraLen = cdBuf.short.toInt() and 0xFFFF
            val commentLen = cdBuf.short.toInt() and 0xFFFF
            cdBuf.position(pos + 42)
            val lfhOffset = cdBuf.int.toLong() and 0xFFFFFFFFL

            if (pos + 46 + fnLen <= cdBytes.size) {
                val fn = String(cdBytes, pos + 46, fnLen, StandardCharsets.UTF_8)
                if (fn == "AndroidManifest.xml") {
                    manifestEntry = ManifestZipEntry(method, compSize, uncompSize, lfhOffset)
                    break
                }
            }
            pos += 46 + fnLen + extraLen + commentLen
        }

        val entry = manifestEntry ?: run {
            Log.w(TAG, "[ApkRangeManifestParser] AndroidManifest.xml not found in CD of ${apkItem.name}")
            return@attempt null
        }
        Log.d(TAG, "[ApkRangeManifestParser] Found AndroidManifest.xml in ${apkItem.name}: lfhOffset=${entry.lfhOffset}, compSize=${entry.compressedSize}, method=${entry.method}")

        val lfhFetchLen = minOf(fileSize - entry.lfhOffset, 30L + 2048L + entry.compressedSize)
        val lfhBytes = scanner.downloadByteRange(context, prefs, apkItem, entry.lfhOffset, entry.lfhOffset + lfhFetchLen - 1)
            ?: run {
                Log.w(TAG, "[ApkRangeManifestParser] Failed to fetch LFH bytes for ${apkItem.name}")
                return@attempt null
            }

        if (lfhBytes.size < 30) return@attempt null
        val lfhBuf = ByteBuffer.wrap(lfhBytes).order(ByteOrder.LITTLE_ENDIAN)
        if (lfhBuf.int != 0x04034b50) return@attempt null

        lfhBuf.position(26)
        val lfhFnLen = lfhBuf.short.toInt() and 0xFFFF
        val lfhExtraLen = lfhBuf.short.toInt() and 0xFFFF
        val dataStart = 30 + lfhFnLen + lfhExtraLen

        val rawData = if (dataStart + entry.compressedSize <= lfhBytes.size) {
            lfhBytes.copyOfRange(dataStart, dataStart + entry.compressedSize.toInt())
        } else {
            val exactStart = entry.lfhOffset + dataStart
            scanner.downloadByteRange(context, prefs, apkItem, exactStart, exactStart + entry.compressedSize - 1)
                ?: return@attempt null
        }
        val manifestBytes = if (entry.method == 8) {
            val inflater = Inflater(true)
            inflater.setInput(rawData)
            val out = ByteArray(entry.uncompressedSize.toInt())
            inflater.inflate(out)
            inflater.end()
            out
        } else {
            rawData
        }

        parseAxml(manifestBytes)
    }

    private data class ManifestZipEntry(
        val method: Int,
        val compressedSize: Long,
        val uncompressedSize: Long,
        val lfhOffset: Long
    )

    fun parseAxml(bytes: ByteArray): ApkManifestInfo? = attempt("parse AXML binary") {
        if (bytes.size < 36) return@attempt null
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        val rootChunkType = buf.short.toInt() and 0xFFFF
        buf.short
        buf.int
        if (rootChunkType != 0x0003) return@attempt null

        val spChunkType = buf.short.toInt() and 0xFFFF
        buf.short
        val spSize = buf.int
        if (spChunkType != 0x0001) return@attempt null

        val strCount = buf.int
        buf.int
        val flags = buf.int
        val strStart = buf.int
        buf.int

        val isUtf8 = (flags and (1 shl 8)) != 0
        val strOffsets = IntArray(strCount)
        for (i in 0 until strCount) {
            strOffsets[i] = buf.int
        }

        val poolStart = 8 + strStart
        val strings = ArrayList<String>(strCount)

        for (i in 0 until strCount) {
            val off = poolStart + strOffsets[i]
            if (off >= bytes.size) {
                strings.add("")
                continue
            }
            if (isUtf8) {
                var o = off
                var u8len = bytes[o].toInt() and 0xFF
                if ((u8len and 0x80) != 0) {
                    u8len = ((u8len and 0x7F) shl 8) or (bytes[o + 1].toInt() and 0xFF)
                    o += 2
                } else {
                    o += 1
                }
                val end = minOf(bytes.size, o + u8len)
                strings.add(String(bytes, o, end - o, StandardCharsets.UTF_8))
            } else {
                val u16len = (bytes[off].toInt() and 0xFF) or ((bytes[off + 1].toInt() and 0xFF) shl 8)
                val strByteLen = u16len * 2
                val start = off + 2
                val end = minOf(bytes.size, start + strByteLen)
                strings.add(String(bytes, start, end - start, StandardCharsets.UTF_16LE))
            }
        }

        var pos = 8 + spSize
        var pkgName: String? = null
        var versionCode: Long = 1L
        var versionName = "1.0"
        var appLabel: String? = null

        while (pos + 8 <= bytes.size) {
            buf.position(pos)
            val chunkType = buf.short.toInt() and 0xFFFF
            val headerSize = buf.short.toInt() and 0xFFFF
            val chunkSize = buf.int
            if (chunkSize <= 0) break

            if (chunkType == 0x0102) {
                buf.position(pos + 16)
                buf.int
                val nameIdx = buf.int
                val attrStart = buf.short.toInt() and 0xFFFF
                val attrSize = buf.short.toInt() and 0xFFFF
                val attrCount = buf.short.toInt() and 0xFFFF

                val tagName = if (nameIdx in strings.indices) strings[nameIdx] else ""
                var attrPos = pos + headerSize + attrStart

                for (a in 0 until attrCount) {
                    if (attrPos + 20 <= bytes.size) {
                        buf.position(attrPos)
                        buf.int
                        val aName = buf.int
                        val aValStr = buf.int
                        buf.short
                        buf.get()
                        val aValType = buf.get().toInt() and 0xFF
                        val aData = buf.int

                        val attrName = if (aName in strings.indices) strings[aName] else ""
                        val attrVal = if (aValStr != -1 && aValStr in strings.indices) strings[aValStr] else null

                        if (tagName == "manifest") {
                            when (attrName) {
                                "versionCode" -> versionCode = aData.toLong() and 0xFFFFFFFFL
                                "versionName" -> {
                                    versionName = attrVal
                                        ?: (if (aValType == 3 && aData in strings.indices) strings[aData] else aData.toString())
                                }
                                "package" -> {
                                    pkgName = attrVal
                                        ?: (if (aValType == 3 && aData in strings.indices) strings[aData] else null)
                                }
                            }
                        } else if (tagName == "application" && attrName == "label") {
                            appLabel = attrVal ?: (if (aValType == 3 && aData in strings.indices) strings[aData] else null)
                        }
                    }
                    attrPos += attrSize
                }
            }
            pos += chunkSize
        }

        try {
            Log.d(TAG, "[ApkRangeManifestParser] Extracted from APK: pkg=$pkgName, versionCode=$versionCode, versionName=$versionName, label=$appLabel")
        } catch (_: Throwable) {}
        ApkManifestInfo(
            packageName = pkgName,
            versionCode = if (versionCode > 0) versionCode else 1L,
            versionName = versionName.ifBlank { "1.0" },
            appLabel = appLabel
        )
    }
}
