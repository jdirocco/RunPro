package com.runpro.wear

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.plugins.DefaultSdkProvider
import org.robolectric.plugins.LegacyDependencyResolver

class RobolectricOfflineConfigTest {
    @Test
    fun `robolectric resolves pre-fetched android-all jars offline from gradle dir`() {
        assertEquals("true", System.getProperty("robolectric.offline"))
        val dirProperty = System.getProperty("robolectric.dependency.dir")
        assertNotNull("robolectric.dependency.dir must be set", dirProperty)
        val dependencyDir = Path.of(requireNotNull(dirProperty)).toAbsolutePath().normalize()
        assertTrue("Expected directory at $dependencyDir", Files.isDirectory(dependencyDir))

        val sdksByApi = DefaultSdkProvider(LegacyDependencyResolver(System.getProperties()))
            .sdks
            .associateBy { it.apiLevel }

        for (api in listOf(30, 34)) {
            val sdk = requireNotNull(sdksByApi[api]) { "Missing Robolectric SDK for API $api" }
            val jarPath = sdk.jarPath.toAbsolutePath().normalize()
            assertTrue(
                "Missing pre-resolved jar for API $api at $jarPath",
                Files.isRegularFile(jarPath)
            )
            assertEquals(dependencyDir, jarPath.parent)
        }
    }
}
