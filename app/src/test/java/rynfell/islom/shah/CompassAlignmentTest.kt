package rynfell.islom.shah

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompassAlignmentTest {
    @Test
    fun usesShortestTurnAcrossNorth() {
        assertEquals(2f, signedDegrees(1f - 359f), 0.001f)
        assertEquals(-2f, signedDegrees(359f - 1f), 0.001f)
        assertEquals(358f, normalizeDegrees(-2f), 0.001f)
    }

    @Test
    fun vibratesOnlyAfterHoldingAlignmentAndRearmsOutsideExitThreshold() {
        val alignment = CompassAlignment()
        assertFalse(alignment.update(2f, 0L))
        assertFalse(alignment.update(2f, 199_000_000L))
        assertTrue(alignment.update(2f, 200_000_000L))
        assertFalse(alignment.update(5f, 210_000_000L))
        assertTrue(alignment.aligned)
        assertFalse(alignment.update(-7.1f, 300_000_000L))
        assertFalse(alignment.aligned)
        assertFalse(alignment.update(-3f, 400_000_000L))
        assertTrue(alignment.update(-3f, 600_000_000L))
    }

    @Test
    fun anInvalidHeadingBreaksTheHoldWithoutRearmingFeedback() {
        val alignment = CompassAlignment()
        assertFalse(alignment.update(0f, 0L))
        assertFalse(alignment.update(null, 199_000_000L))
        assertFalse(alignment.update(0f, 200_000_000L))
        assertTrue(alignment.update(0f, 400_000_000L))
        assertFalse(alignment.update(null, 500_000_000L))
        assertFalse(alignment.update(0f, 600_000_000L))
        assertFalse(alignment.update(0f, 800_000_000L))
        assertTrue(alignment.aligned)
    }
}
