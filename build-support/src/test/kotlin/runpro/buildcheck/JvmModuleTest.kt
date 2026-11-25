package runpro.buildcheck

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JvmModuleTest {
    @Test
    fun `tests run on the Java 21 toolchain`() {
        assertEquals(21, Runtime.version().feature())
    }

    @Test
    fun `Android framework is absent from the runtime classpath`() {
        val loader = javaClass.classLoader
        assertNull(loader.getResource("android/app/Activity.class"))
        assertNull(loader.getResource("android/os/Build.class"))
    }
}
