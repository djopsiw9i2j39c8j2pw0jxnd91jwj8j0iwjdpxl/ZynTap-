package com.zyntap.app.overlay

import org.junit.Assert.*
import org.junit.Test

class RuntimeRegressionTest {
    @Test fun gestureCannotReopenDuringClose() {
        val g = MenuGestureGate()
        assertTrue(g.down(100, false))
        g.blockUntil(600)
        assertFalse(g.tracking)
        assertFalse(g.down(200, false))
        assertFalse(g.down(599, false))
        assertTrue(g.down(600, false))
    }
    @Test fun busyAnimationRejectsNewDown() {
        val g = MenuGestureGate()
        assertFalse(g.down(1000, true))
        assertFalse(g.tracking)
    }
    @Test fun finishingGestureRejectsStrayUp() {
        val g = MenuGestureGate()
        g.down(100, false)
        g.finish()
        assertFalse(g.tracking)
    }
    @Test fun suppressionNeverShortensPreviousDeadline() {
        val g = MenuGestureGate()
        g.blockUntil(1000)
        g.blockUntil(200)
        assertFalse(g.down(999, false))
        assertTrue(g.down(1000, false))
    }
    @Test fun sixtyFpsFromPresentationIntervals() {
        val t = (0L..60L).map { 10_000_000_000L + it * 16_666_667L }
        assertEquals(60, FrameRateMath.calculate(t, t.last()))
    }
    @Test fun shortHighRefreshWindowNotMistakenForFrameCount() {
        val t = (0L..99L).map { 10_000_000_000L + it * 8_333_333L }
        assertEquals(120, FrameRateMath.calculate(t, t.last()))
    }
    @Test fun duplicateAndInvalidPresentationTimesIgnored() {
        val t = listOf(0L, Long.MAX_VALUE, 1_000_000_000L, 1_000_000_000L, 1_020_000_000L)
        assertEquals(50, FrameRateMath.calculate(t, 1_020_000_000L))
    }
    @Test fun unavailablePresentationSamplesRemainUnavailable() {
        assertEquals(-1, FrameRateMath.calculate(emptyList(), 5))
        assertEquals(-1, FrameRateMath.calculate(listOf(5), 5))
    }

    @Test fun identityFramePreservesExactPixels() {
        assertEquals(321 to 1678, PointCoordinates.map(321, 1678, 1080, 2400, 0, 1080, 2400, 0))
    }
    @Test fun portraitToLandscape90MapsBothAxes() {
        assertEquals(400 to 75, PointCoordinates.map(25, 400, 101, 501, 0, 501, 101, 1))
    }
    @Test fun portraitToLandscape270MapsBothAxes() {
        assertEquals(100 to 25, PointCoordinates.map(25, 400, 101, 501, 0, 501, 101, 3))
    }
    @Test fun reversePortrait180MapsBothAxes() {
        assertEquals(75 to 100, PointCoordinates.map(25, 400, 101, 501, 0, 101, 501, 2))
    }
    @Test fun rotationRoundTripDoesNotAccumulateDrift() {
        for (rotation in 0..3) {
            val width = if (rotation % 2 == 0) 1080 else 2400
            val height = if (rotation % 2 == 0) 2400 else 1080
            val p = PointCoordinates.map(321, 1678, 1080, 2400, 0, width, height, rotation)
            assertEquals(321 to 1678, PointCoordinates.map(p.first, p.second, width, height, rotation, 1080, 2400, 0))
        }
    }
    @Test fun resizedDisplayScalesWithinFrame() {
        assertEquals(50 to 100, PointCoordinates.map(100, 200, 201, 401, 0, 101, 201, 0))
    }
    @Test fun cornersStayInBoundsForEveryRotation() {
        for (rotation in 0..3) for (x in listOf(0, 1079)) for (y in listOf(0, 2399)) {
            val w = if (rotation % 2 == 0) 1080 else 2400
            val h = if (rotation % 2 == 0) 2400 else 1080
            val p = PointCoordinates.map(x, y, 1080, 2400, 0, w, h, rotation)
            assertTrue(p.first in 0 until w && p.second in 0 until h)
        }
    }
    @Test fun singlePixelFrameCannotDivideByZero() {
        assertEquals(0 to 0, PointCoordinates.map(0, 0, 1, 1, 0, 1, 1, 1))
    }
    @Test fun scriptMappingPreservesWaitsAndSimultaneousTaps() {
        val original = PointScriptParser.parse("Click 25, 400\n300ms\nMulticlick 0, 0\nMulticlick 100, 500", 101, 501)
        val result = PointCoordinates.transform(original, 101, 501, 0, 501, 101, 1)
        assertTrue(result.valid)
        assertEquals(PointStep.Tap(400, 75), result.steps[0])
        assertEquals(PointStep.Wait(300), result.steps[1])
        assertEquals(PointStep.Multi(listOf(0 to 100, 500 to 0)), result.steps[2])
    }
    @Test fun invalidScriptDoesNotBecomeValidAfterMapping() {
        val script = PointScriptParser.parse("Click 1000, 10", 101, 501)
        assertFalse(PointCoordinates.transform(script, 101, 501, 0, 501, 101, 1).valid)
    }
    @Test fun editorReframePreservesCommentsAndInvalidLines() {
        val text = "# điểm đầu\nClick 25, 400\n300ms\n  Multiclick 0, 0  \ninvalid\nClick 1000, 10"
        assertEquals("# điểm đầu\nClick 400, 75\n300ms\n  Multiclick 0, 100  \ninvalid\nClick 1000, 10",
            PointCoordinates.reframeText(text, 101, 501, 0, 501, 101, 1))
    }
    @Test fun legacyButtonWithoutMetadataUsesCurrentFrame() {
        val b = MainButton(1, "legacy", pointScript = "Click 100, 50")
        assertEquals(PointStep.Tap(100, 50), b.scriptFor(501, 101, 1).steps.single())
    }
    @Test fun buttonScriptAndEditorShareTheSameCoordinates() {
        val b = MainButton(1, "point", pointScript = "Click 25, 400", pointWidth = 101, pointHeight = 501, pointRotation = 0)
        assertEquals(b.scriptFor(501, 101, 1), PointScriptParser.parse(b.textFor(501, 101, 1), 501, 101))
    }
    @Test fun buttonKeepsAuthoredScriptUnchangedAcrossRotations() {
        val b = MainButton(1, "point", pointScript = "Click 25, 400", pointWidth = 101, pointHeight = 501, pointRotation = 0)
        repeat(100) { b.scriptFor(501, 101, 1); b.textFor(101, 501, 2) }
        assertEquals("Click 25, 400", b.pointScript)
        assertEquals(101, b.pointWidth)
        assertEquals(501, b.pointHeight)
    }
}
