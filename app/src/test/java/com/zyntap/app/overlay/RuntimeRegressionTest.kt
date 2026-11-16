package com.zyntap.app.overlay

import org.junit.Assert.*
import org.junit.Test

class RuntimeRegressionTest {
    private fun state() = TargetAppState { pkg, _ -> pkg == "com.android.systemui" || pkg == "com.android.permissioncontroller" }
    @Test fun initialLauncherDoesNotCloseNewGame() {
        val s = state()
        s.resume("launcher", "Home", "home", 1)
        s.resume("game", "A", "a", 2)
        assertNull(s.leaveCandidate("game"))
    }
    @Test fun pauseAloneDoesNotCloseOverlay() {
        val s = state()
        s.resume("game", "A", "a", 2)
        s.pause("a", 3)
        assertNull(s.leaveCandidate("game"))
    }
    @Test fun pauseAndStopWithoutReplacementDoesNotCloseLoading() {
        val s = state()
        s.resume("game", "A", "a", 2)
        s.pause("a", 3)
        s.stop("a", 4)
        assertNull(s.leaveCandidate("game"))
    }
    @Test fun homeClosesEvenWhenRomOmitsStop() {
        val s = state()
        s.resume("game", "A", "a", 2)
        s.pause("a", 3)
        s.resume("launcher", "Home", "home", 4)
        assertEquals("launcher/Home@4", s.leaveCandidate("game"))
    }
    @Test fun replacementClosesDespiteStaleResumedActivity() {
        val s = state()
        s.resume("game", "A", "a", 2)
        s.resume("other", "B", "b", 4)
        assertEquals("other/B@4", s.leaveCandidate("game"))
    }
    @Test fun permissionDialogKeepsOverlay() {
        val s = state()
        s.resume("game", "A", "a", 2)
        s.pause("a", 3)
        s.resume("com.android.permissioncontroller", "Grant", "permission", 4)
        assertNull(s.leaveCandidate("game"))
    }
    @Test fun systemUiKeepsOverlay() {
        val s = state()
        s.resume("game", "A", "a", 2)
        s.resume("com.android.systemui", "Dialog", "sys", 3)
        assertNull(s.leaveCandidate("game"))
    }
    @Test fun activityHandoffInsideGameKeepsOverlay() {
        val s = state()
        s.resume("game", "A", "a", 2)
        s.pause("a", 3)
        s.resume("game", "B", "b", 4)
        s.stop("a", 5)
        assertNull(s.leaveCandidate("game"))
    }
    @Test fun returnToGameInvalidatesExitEvidence() {
        val s = state()
        s.resume("game", "A", "a", 2)
        s.pause("a", 3)
        s.resume("launcher", "Home", "home", 4)
        assertNotNull(s.leaveCandidate("game"))
        s.resume("game", "A", "a", 5)
        assertNull(s.leaveCandidate("game"))
    }
    @Test fun staleStopCannotEraseNewResume() {
        val s = state()
        s.resume("game", "A", "a", 5)
        s.stop("a", 4)
        assertTrue(s.isResumed("game"))
    }
    @Test fun unknownTargetDoesNotAutoClose() {
        val s = state()
        s.resume("launcher", "Home", "home", 4)
        assertNull(s.leaveCandidate("game"))
    }
    @Test fun duplicateResumeIsIdempotent() {
        val s = state()
        repeat(10000) { s.resume("game", "A", "a", 5) }
        assertTrue(s.isResumed("game"))
        assertNull(s.leaveCandidate("game"))
    }
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
    @Test fun restoredSessionClosesWithoutRecentGameResume() {
        val s = state()
        s.resume("launcher", "Home", "home", 20)
        assertEquals("launcher/Home@20", s.leaveCandidate("game", 10))
    }
    @Test fun prelaunchLauncherDoesNotCloseTargetBeforeItResumes() {
        val s = state()
        s.resume("launcher", "Home", "home", 5)
        assertNull(s.leaveCandidate("game", 10))
    }
}
