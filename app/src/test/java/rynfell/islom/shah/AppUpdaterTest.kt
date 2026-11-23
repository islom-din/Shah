package rynfell.islom.shah

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdaterTest {
    @Test
    fun comparesVersionsNumerically() {
        assertTrue(isNewerVersion("v1.10", "1.9"))
        assertTrue(isNewerVersion("1.0.1", "1.0"))
        assertFalse(isNewerVersion("v1.0", "1.0"))
        assertFalse(isNewerVersion("0.9", "1.0"))
    }
}
