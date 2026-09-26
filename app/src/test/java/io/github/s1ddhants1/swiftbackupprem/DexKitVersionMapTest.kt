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
        assertTrue(versionMap.containsKey(623))
        assertTrue(versionMap.containsKey(626))
        assertTrue(versionMap.containsKey(628))
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
        assertEquals("defpackage.pa7", v620.settingsFragment)
        assertEquals("org.swiftapps.swiftbackup.settings.a", v620.settingsDetailFragment)
        assertEquals("defpackage.fm0", v620.baseSettingsFragment)

        val v623 = versionMap[623]
        assertNotNull(v623)
        assertEquals("defpackage.gp5", v623!!.clientId)
        assertEquals("defpackage.p74", v623.homeViewModel)
        assertEquals("defpackage.n55", v623.authUser)
        assertEquals("defpackage.l55", v623.anonUser)
        assertEquals("defpackage.oh3", v623.firebaseWatcher)
        assertEquals("defpackage.jg3", v623.fireSynchronizer)
        assertEquals("defpackage.ag3", v623.fireSynchronizerSuccess)
        assertEquals("defpackage.bd7", v623.settingsFragment)
        assertEquals("org.swiftapps.swiftbackup.settings.a", v623.settingsDetailFragment)
        assertEquals("defpackage.qm0", v623.baseSettingsFragment)

        val v626 = versionMap[626]
        assertNotNull(v626)
        assertEquals("defpackage.dp5", v626!!.clientId)
        assertEquals("defpackage.f74", v626.homeViewModel)
        assertEquals("defpackage.w55", v626.authUser)
        assertEquals("defpackage.u55", v626.anonUser)
        assertEquals("defpackage.rh3", v626.firebaseWatcher)
        assertEquals("defpackage.lg3", v626.fireSynchronizer)
        assertEquals("defpackage.cg3", v626.fireSynchronizerSuccess)
        assertEquals("defpackage.re7", v626.settingsFragment)
        assertEquals("org.swiftapps.swiftbackup.settings.a", v626.settingsDetailFragment)
        assertEquals("defpackage.qm0", v626.baseSettingsFragment)

        val v628 = versionMap[628]
        assertNotNull(v628)
        assertEquals("defpackage.jq5", v628!!.clientId)
        assertEquals("defpackage.r84", v628.homeViewModel)
        assertEquals("defpackage.x65", v628.authUser)
        assertEquals("defpackage.v65", v628.anonUser)
        assertEquals("defpackage.oi3", v628.firebaseWatcher)
        assertEquals("defpackage.ih3", v628.fireSynchronizer)
        assertEquals("defpackage.yg3", v628.fireSynchronizerSuccess)
        assertEquals("defpackage.uf7", v628.settingsFragment)
        assertEquals("org.swiftapps.swiftbackup.settings.a", v628.settingsDetailFragment)
        assertEquals("defpackage.um0", v628.baseSettingsFragment)
    }
}
