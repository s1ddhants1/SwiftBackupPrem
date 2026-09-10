package io.github.s1ddhants1.swiftbackupprem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DexKitVersionMapTest {

    @Test
    fun versionMapContainsExpectedVersions() {
        assertTrue(versionMap.containsKey(561))
        assertTrue(versionMap.containsKey(569))
        assertTrue(versionMap.containsKey(590))
        assertTrue(versionMap.containsKey(620))
    }

    @Test
    fun versionMapEntriesHaveNonBlankClasses() {
        for ((version, classes) in versionMap) {
            assertNotNull("clientId is null for $version", classes.clientId)
            assertTrue("clientId is blank for $version", classes.clientId.isNotBlank())

            assertNotNull("homeViewModel is null for $version", classes.homeViewModel)
            assertTrue("homeViewModel is blank for $version", classes.homeViewModel.isNotBlank())

            assertNotNull("authUser is null for $version", classes.authUser)
            assertTrue("authUser is blank for $version", classes.authUser.isNotBlank())

            assertNotNull("anonUser is null for $version", classes.anonUser)
            assertTrue("anonUser is blank for $version", classes.anonUser.isNotBlank())
        }
    }

    @Test
    fun versionMapReturnsExactMappingsForKnownVersion() {
        val v620 = versionMap[620]
        assertNotNull(v620)
        assertEquals("defpackage.gn5", v620!!.clientId)
        assertEquals("defpackage.c64", v620.homeViewModel)
        assertEquals("defpackage.d45", v620.authUser)
        assertEquals("defpackage.b45", v620.anonUser)
        assertEquals("defpackage.gg3", v620.firebaseWatcher)
    }
}
