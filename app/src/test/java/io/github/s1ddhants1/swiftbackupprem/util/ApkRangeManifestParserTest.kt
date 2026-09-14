package io.github.s1ddhants1.swiftbackupprem.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

class ApkRangeManifestParserTest {

    @Test
    fun testParseAxmlBinary() {
        val stringPool = listOf(
            "manifest",
            "application",
            "versionCode",
            "versionName",
            "package",
            "label",
            "com.test.app",
            "2.3.4",
            "My Test App"
        )

        val strData = ByteArrayOutputStream()
        val strOffsets = mutableListOf<Int>()
        for (s in stringPool) {
            strOffsets.add(strData.size())
            val chars = s.toByteArray(StandardCharsets.UTF_16LE)
            val lenBytes = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(s.length.toShort()).array()
            strData.write(lenBytes)
            strData.write(chars)
            strData.write(0)
            strData.write(0)
        }

        val spPayload = strData.toByteArray()
        val spHeaderSize = 28 + strOffsets.size * 4
        val spChunkSize = spHeaderSize + spPayload.size

        val out = ByteArrayOutputStream()
        val header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        header.putShort(0x0003.toShort())
        header.putShort(8.toShort())
        header.putInt(0)
        out.write(header.array())

        val spBuf = ByteBuffer.allocate(spHeaderSize).order(ByteOrder.LITTLE_ENDIAN)
        spBuf.putShort(0x0001.toShort())
        spBuf.putShort(28.toShort())
        spBuf.putInt(spChunkSize)
        spBuf.putInt(stringPool.size)
        spBuf.putInt(0)
        spBuf.putInt(0)
        spBuf.putInt(spHeaderSize)
        spBuf.putInt(0)
        for (off in strOffsets) {
            spBuf.putInt(off)
        }
        out.write(spBuf.array())
        out.write(spPayload)

        val tagChunkSize = 16 + 20 + 3 * 20
        val tagBuf = ByteBuffer.allocate(tagChunkSize).order(ByteOrder.LITTLE_ENDIAN)
        tagBuf.putShort(0x0102.toShort())
        tagBuf.putShort(16.toShort())
        tagBuf.putInt(tagChunkSize)
        tagBuf.putInt(1)
        tagBuf.putInt(-1)
        tagBuf.putInt(-1)
        tagBuf.putInt(0)
        tagBuf.putShort(20.toShort())
        tagBuf.putShort(20.toShort())
        tagBuf.putShort(3.toShort())
        tagBuf.putShort(0.toShort())
        tagBuf.putShort(0.toShort())
        tagBuf.putShort(0.toShort())

        tagBuf.putInt(-1)
        tagBuf.putInt(4)
        tagBuf.putInt(6)
        tagBuf.putShort(8.toShort())
        tagBuf.put(0.toByte())
        tagBuf.put(3.toByte())
        tagBuf.putInt(6)

        tagBuf.putInt(-1)
        tagBuf.putInt(2)
        tagBuf.putInt(-1)
        tagBuf.putShort(8.toShort())
        tagBuf.put(0.toByte())
        tagBuf.put(16.toByte())
        tagBuf.putInt(42)

        tagBuf.putInt(-1)
        tagBuf.putInt(3)
        tagBuf.putInt(7)
        tagBuf.putShort(8.toShort())
        tagBuf.put(0.toByte())
        tagBuf.put(3.toByte())
        tagBuf.putInt(7)

        out.write(tagBuf.array())

        val fullBytes = out.toByteArray()
        val totalSizeBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(fullBytes.size)
        System.arraycopy(totalSizeBuf.array(), 0, fullBytes, 4, 4)

        val result = ApkRangeManifestParser.parseAxml(fullBytes)
        assertNotNull(result)
        assertEquals("com.test.app", result?.packageName)
        assertEquals(42L, result?.versionCode)
        assertEquals("2.3.4", result?.versionName)
    }
}
