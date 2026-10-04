package nl.markmaaktmedia.tandem.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LivePlanTest {
    @Test
    fun aPortraitPhoneFitsTheBoxTheOtherWayRound() {
        // The Mac asks for "1920 by 1080"; a portrait phone gets 1080 by 1920 at most.
        val size = LivePlan.fit(1440, 3120, 1920, 1080)
        assertTrue(size.width <= 1080 && size.height <= 1920)
        assertTrue(size.height > size.width)
    }

    @Test
    fun aLandscapeCameraKeepsTheLongSideLong() {
        val size = LivePlan.fit(4000, 3000, 1280, 720)
        assertTrue(size.width <= 1280 && size.height <= 720)
        assertTrue(size.width > size.height)
    }

    @Test
    fun nothingIsMadeLargerThanItIs() {
        val size = LivePlan.fit(640, 480, 1920, 1080)
        assertEquals(640, size.width)
        assertEquals(480, size.height)
    }

    @Test
    fun sizesAreMultiplesOfEight() {
        val size = LivePlan.fit(1080, 2340, 0, 0)
        assertEquals(0, size.width % 8)
        assertEquals(0, size.height % 8)
        assertEquals(1080, size.width)
        assertEquals(2336, size.height)
    }

    @Test
    fun noPreferenceMeansTheScreenAsItIs() {
        val size = LivePlan.fit(1080, 2400, 0, 0)
        assertEquals(1080, size.width)
        assertEquals(2400, size.height)
    }

    @Test
    fun aBrokenSourceStillGivesASize() {
        val size = LivePlan.fit(0, 0, 1920, 1080)
        assertTrue(size.width >= 16 && size.height >= 16)
    }

    @Test
    fun theFrameRateIsKeptInRange() {
        assertEquals(30, LivePlan.fps(0))
        assertEquals(60, LivePlan.fps(240))
        assertEquals(10, LivePlan.fps(1))
        assertEquals(24, LivePlan.fps(24))
    }

    @Test
    fun theBitrateFollowsTheSizeAndTheMacsLimit() {
        val small = LivePlan.bitrate(640, 360, 30, 0)
        val large = LivePlan.bitrate(1920, 1080, 30, 0)
        assertEquals(1_500_000, small)
        assertTrue(large in 3_000_000..16_000_000)
        assertEquals(2_000_000, LivePlan.bitrate(1920, 1080, 30, 2_000_000))
        assertEquals(16_000_000, LivePlan.bitrate(3840, 2160, 60, 0))
    }

    @Test
    fun aPlanHasAllOfIt() {
        val plan = LivePlan.plan(1080, 2400, LivePlan.Limits(2560, 1440, 60, 0))
        assertEquals(1080, plan.width)
        assertEquals(2400, plan.height)
        assertEquals(60, plan.fps)
        assertTrue(plan.bitrate in 1_500_000..16_000_000)
    }

    @Test
    fun theSensorAngleBecomesASurfaceRotation() {
        assertEquals(0, LivePlan.surfaceRotation(0))
        assertEquals(0, LivePlan.surfaceRotation(350))
        assertEquals(3, LivePlan.surfaceRotation(90))
        assertEquals(2, LivePlan.surfaceRotation(180))
        assertEquals(1, LivePlan.surfaceRotation(270))
        assertNull(LivePlan.surfaceRotation(-1))
    }

    @Test
    fun annexBIsRecognisedAndParameterSetsAreFound() {
        val key = byteArrayOf(0, 0, 0, 1, 0x67, 1, 2, 0, 0, 0, 1, 0x68, 3, 0, 0, 1, 0x65, 9, 9)
        assertTrue(AnnexBScan.startsWithStartCode(key))
        assertTrue(AnnexBScan.hasParameterSets(key))
        assertTrue(AnnexBScan.hasKeyframe(key))
        assertEquals(listOf(7, 8, 5), AnnexBScan.nalTypes(key))

        val delta = byteArrayOf(0, 0, 0, 1, 0x41, 5, 5, 5)
        assertFalse(AnnexBScan.hasParameterSets(delta))
        assertFalse(AnnexBScan.hasKeyframe(delta))

        // Length-prefixed video is not what the core takes.
        assertFalse(AnnexBScan.startsWithStartCode(byteArrayOf(0, 0, 0, 5, 0x65, 1, 2, 3, 4)))
    }

    @Test
    fun bitratesAreWrittenForPeople() {
        assertEquals("5.8 Mbit/s", LivePlan.megabits(5_840_000))
    }
}
