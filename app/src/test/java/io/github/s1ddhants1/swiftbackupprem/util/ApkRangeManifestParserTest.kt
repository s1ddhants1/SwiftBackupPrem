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
            "manifest",       // 0
            "application",    // 1
            "versionCode",    // 2
            "versionName",    // 3
            "package",        // 4
            "label",          // 5
            "com.test.app",   // 6
            "2.3.4",          // 7
            "My Test App"     // 8
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
        header.putShort(0x0003.toShort()) // RES_XML_TYPE
        header.putShort(8.toShort())
        header.putInt(0) // placeholder
        out.write(header.array())

        // String pool chunk
        val spBuf = ByteBuffer.allocate(spHeaderSize).order(ByteOrder.LITTLE_ENDIAN)
        spBuf.putShort(0x0001.toShort()) // RES_STRING_POOL_TYPE
        spBuf.putShort(28.toShort())
        spBuf.putInt(spChunkSize)
        spBuf.putInt(stringPool.size)
        spBuf.putInt(0) // styleCount
        spBuf.putInt(0) // flags (UTF-16)
        spBuf.putInt(spHeaderSize) // strStart relative to string pool chunk start (offset 8)
        spBuf.putInt(0) // stylesStart
        for (off in strOffsets) {
            spBuf.putInt(off)
        }
        out.write(spBuf.array())
        out.write(spPayload)

        // START_TAG chunk for <manifest>
        // Tag chunk has 16-byte header + 20-byte body + (3 * 20) attributes = 96 bytes
        val tagChunkSize = 16 + 20 + 3 * 20
        val tagBuf = ByteBuffer.allocate(tagChunkSize).order(ByteOrder.LITTLE_ENDIAN)
        tagBuf.putShort(0x0102.toShort()) // START_TAG
        tagBuf.putShort(16.toShort())     // headerSize
        tagBuf.putInt(tagChunkSize)
        tagBuf.putInt(1)                  // lineNumber
        tagBuf.putInt(-1)                 // comment
        // Body (20 bytes)
        tagBuf.putInt(-1)                 // ns (offset 16..19)
        tagBuf.putInt(0)                  // name (offset 20..23, index 0 = "manifest")
        tagBuf.putShort(20.toShort())     // attrStart (offset 24..25)
        tagBuf.putShort(20.toShort())     // attrSize (offset 26..27)
        tagBuf.putShort(3.toShort())      // attrCount (offset 28..29)
        tagBuf.putShort(0.toShort())      // idIndex (offset 30..31)
        tagBuf.putShort(0.toShort())      // classIndex (offset 32..33)
        tagBuf.putShort(0.toShort())      // styleIndex (offset 34..35)
        // position is now 16 + 20 = 36 (exactly attrStart)

        // Attr 0: package="com.test.app"
        tagBuf.putInt(-1)                 // ns
        tagBuf.putInt(4)                  // name (index 4 = "package")
        tagBuf.putInt(6)                  // rawValue (index 6 = "com.test.app")
        tagBuf.putShort(8.toShort())      // size
        tagBuf.put(0.toByte())            // res0
        tagBuf.put(3.toByte())            // type = TYPE_STRING (3)
        tagBuf.putInt(6)                  // data = string index 6

        // Attr 1: versionCode=42
        tagBuf.putInt(-1)
        tagBuf.putInt(2)                  // name (index 2 = "versionCode")
        tagBuf.putInt(-1)
        tagBuf.putShort(8.toShort())
        tagBuf.put(0.toByte())
        tagBuf.put(16.toByte())           // type = TYPE_INT_DEC (16)
        tagBuf.putInt(42)                 // data = 42

        // Attr 2: versionName="2.3.4"
        tagBuf.putInt(-1)
        tagBuf.putInt(3)                  // name (index 3 = "versionName")
        tagBuf.putInt(7)                  // rawValue (index 7 = "2.3.4")
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
