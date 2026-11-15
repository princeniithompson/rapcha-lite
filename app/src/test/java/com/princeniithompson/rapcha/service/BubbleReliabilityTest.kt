package com.princeniithompson.rapcha.service

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.princeniithompson.rapcha.data.AppLogRepository
import com.princeniithompson.rapcha.data.DiagnosticType
import com.princeniithompson.rapcha.diagnostics.BubbleHealthLog
import com.princeniithompson.rapcha.diagnostics.BubbleHealthRateLimiter
import com.princeniithompson.rapcha.ui.protection.DeviceProtectionManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BubbleReliabilityTest {

    @Test
    fun `keyboard settle state machine tolerates slow IME and requires two negatives plus settle delay`() {
        val sm = KeyboardSettleStateMachine()

        // T = 1000ms: focused editable shows bubble (slow IME > 300ms hasn't appeared yet)
        assertTrue(
            sm.evaluate(
                imeWindowPresent = false,
                focusedEditableActive = true,
                lastTypingOrFocusElapsedMs = 1000L,
                sessionProtected = false,
                nowMs = 1000L
            )
        )
        // T = 1400ms (>300ms later, still within 1500ms fallback): remains visible
        assertTrue(
            sm.evaluate(
                imeWindowPresent = false,
                focusedEditableActive = true,
                lastTypingOrFocusElapsedMs = 1000L,
                sessionProtected = false,
                nowMs = 1400L
            )
        )

        // T = 2600ms: fallback expired (1600ms > 1500ms) -> 1st negative evaluation MUST NOT hide immediately
        assertTrue(
            sm.evaluate(
                imeWindowPresent = false,
                focusedEditableActive = true,
                lastTypingOrFocusElapsedMs = 1000L,
                sessionProtected = false,
                nowMs = 2600L
            )
        )

        // T = 2900ms (2nd negative, but only 300ms elapsed < 450ms settle): MUST NOT hide yet
        assertTrue(
            sm.evaluate(
                imeWindowPresent = false,
                focusedEditableActive = false,
                lastTypingOrFocusElapsedMs = 1000L,
                sessionProtected = false,
                nowMs = 2900L
            )
        )

        // T = 3060ms (3rd negative AND >= 450ms elapsed since T=2600ms): now hides
        assertFalse(
            sm.evaluate(
                imeWindowPresent = false,
                focusedEditableActive = false,
                lastTypingOrFocusElapsedMs = 1000L,
                sessionProtected = false,
                nowMs = 3060L
            )
        )
    }

    @Test
    fun `keyboard settle state machine never hides while session protected and expires fallback at 1500ms`() {
        val sm = KeyboardSettleStateMachine()

        assertTrue(
            sm.evaluate(
                imeWindowPresent = false,
                focusedEditableActive = true,
                lastTypingOrFocusElapsedMs = 5000L,
                sessionProtected = false,
                nowMs = 5000L
            )
        )
        // Session protected holds bubble visible indefinitely even with no IME or focus
        assertTrue(
            sm.evaluate(
                imeWindowPresent = false,
                focusedEditableActive = false,
                lastTypingOrFocusElapsedMs = 5000L,
                sessionProtected = true,
                nowMs = 25000L
            )
        )

        // Pure condition check for 1500ms fallback expiry
        assertTrue(
            sm.isShowConditionMet(
                imeWindowPresent = false,
                focusedEditableActive = true,
                lastTypingOrFocusElapsedMs = 5000L,
                nowMs = 6500L
            )
        )
        assertFalse(
            sm.isShowConditionMet(
                imeWindowPresent = false,
                focusedEditableActive = true,
                lastTypingOrFocusElapsedMs = 5000L,
                nowMs = 6501L
            )
        )
    }

    @Test
    fun `visibility watchdog pauses on screen off requires two grace ticks and resets when healthy`() {
        var state = VisibilityWatchdogDecision.WatchdogState()

        // Screen off always returns OK and resets violations
        val (offState, offAction) = VisibilityWatchdogDecision.onTick(
            state = VisibilityWatchdogDecision.WatchdogState(1),
            screenOn = false,
            bubbleEnabled = true,
            componentsInitialized = true,
            hasOverlayPermission = true,
            shouldBeVisible = true,
            sessionActive = false,
            isAttached = false,
            windowVisibilityVisible = false,
            keyboardVisible = true
        )
        assertEquals(VisibilityWatchdogDecision.TickAction.OK, offAction)
        assertEquals(0, offState.consecutiveViolations)

        // 1st detached-while-needed tick -> VIOLATION_WAIT (1)
        val (s1, a1) = VisibilityWatchdogDecision.onTick(
            state = state,
            screenOn = true,
            bubbleEnabled = true,
            componentsInitialized = true,
            hasOverlayPermission = true,
            shouldBeVisible = true,
            sessionActive = false,
            isAttached = false,
            windowVisibilityVisible = false,
            keyboardVisible = false
        )
        assertEquals(VisibilityWatchdogDecision.TickAction.VIOLATION_WAIT, a1)
        assertEquals(1, s1.consecutiveViolations)

        // Healthy tick resets consecutive violations
        val (sHealthy, aHealthy) = VisibilityWatchdogDecision.onTick(
            state = s1,
            screenOn = true,
            bubbleEnabled = true,
            componentsInitialized = true,
            hasOverlayPermission = true,
            shouldBeVisible = true,
            sessionActive = false,
            isAttached = true,
            windowVisibilityVisible = true,
            keyboardVisible = true
        )
        assertEquals(VisibilityWatchdogDecision.TickAction.OK, aHealthy)
        assertEquals(0, sHealthy.consecutiveViolations)

        // Invisible while keyboard is up -> 1st wait, 2nd recover
        val (w1, act1) = VisibilityWatchdogDecision.onTick(
            state = sHealthy,
            screenOn = true,
            bubbleEnabled = true,
            componentsInitialized = true,
            hasOverlayPermission = true,
            shouldBeVisible = true,
            sessionActive = false,
            isAttached = true,
            windowVisibilityVisible = false,
            keyboardVisible = true
        )
        assertEquals(VisibilityWatchdogDecision.TickAction.VIOLATION_WAIT, act1)

        val (w2, act2) = VisibilityWatchdogDecision.onTick(
            state = w1,
            screenOn = true,
            bubbleEnabled = true,
            componentsInitialized = true,
            hasOverlayPermission = true,
            shouldBeVisible = true,
            sessionActive = false,
            isAttached = true,
            windowVisibilityVisible = false,
            keyboardVisible = true
        )
        assertEquals(VisibilityWatchdogDecision.TickAction.RECOVER, act2)
        assertEquals(0, w2.consecutiveViolations)
    }

    @Test
    fun `fgs startup decision handles all api levels and fallback ladder`() {
        assertEquals(
            FgsStartupDecision.FgsPlan.LEGACY_NO_TYPE,
            FgsStartupDecision.evaluatePlan(28, true, true, true)
        )
        assertEquals(
            FgsStartupDecision.FgsPlan.TYPE_ZERO,
            FgsStartupDecision.evaluatePlan(31, false, true, true)
        )
        assertEquals(
            FgsStartupDecision.FgsPlan.MICROPHONE_ONLY,
            FgsStartupDecision.evaluatePlan(31, true, true, true)
        )
        assertEquals(
            FgsStartupDecision.FgsPlan.SPECIAL_USE_AND_MICROPHONE,
            FgsStartupDecision.evaluatePlan(34, true, true, true)
        )
        assertEquals(
            FgsStartupDecision.FgsPlan.SPECIAL_USE_ONLY,
            FgsStartupDecision.evaluatePlan(34, true, false, true)
        )
        assertEquals(
            FgsStartupDecision.FgsPlan.SPECIAL_USE_ONLY,
            FgsStartupDecision.evaluatePlan(35, true, true, false)
        )

        // Fallback ladder
        assertEquals(
            FgsStartupDecision.FgsPlan.SPECIAL_USE_ONLY,
            FgsStartupDecision.fallbackPlan(34, FgsStartupDecision.FgsPlan.SPECIAL_USE_AND_MICROPHONE)
        )
        assertEquals(
            FgsStartupDecision.FgsPlan.TYPE_ZERO,
            FgsStartupDecision.fallbackPlan(31, FgsStartupDecision.FgsPlan.MICROPHONE_ONLY)
        )
        assertNull(
            FgsStartupDecision.fallbackPlan(34, FgsStartupDecision.FgsPlan.SPECIAL_USE_ONLY)
        )
    }

    @Test
    fun `respawn throttle and recovery job policy enforce bounds`() {
        var now = 10_000L
        val throttle = RespawnThrottle(minIntervalMs = 5_000L) { now }
        assertTrue(throttle.tryAcquire())
        now += 4_999L
        assertFalse(throttle.tryAcquire())
        now += 1L
        assertTrue(throttle.tryAcquire())

        assertEquals(30_000L, RecoveryJobPolicy.clampLatencyMs(1_000L))
        assertEquals(60_000L, RecoveryJobPolicy.clampLatencyMs(60_000L))
        assertTrue(RecoveryJobPolicy.shouldReschedule(true, true, 0))
        assertTrue(RecoveryJobPolicy.shouldReschedule(true, true, 2))
        assertTrue(RecoveryJobPolicy.shouldReschedule(true, true, 3))
        assertTrue(RecoveryJobPolicy.shouldReschedule(true, true, 100))
        assertFalse(RecoveryJobPolicy.shouldReschedule(false, true, 0))
        assertFalse(RecoveryJobPolicy.shouldReschedule(true, false, 0))
    }

    @Test
    fun `task removal editable node and keyboard visibility policies protect low-end samsung stability`() {
        // Task removal keeps session & overlay alive whenever bubble is enabled
        assertFalse(TaskRemovalPolicy.shouldReleaseSessionOnTaskRemoved(bubbleEnabled = true))
        assertTrue(TaskRemovalPolicy.shouldReleaseSessionOnTaskRemoved(bubbleEnabled = false))
        assertTrue(TaskRemovalPolicy.shouldRebuildOverlayOnTaskRemoved(bubbleEnabled = true, hasOverlayPermission = true))
        assertFalse(TaskRemovalPolicy.shouldRebuildOverlayOnTaskRemoved(bubbleEnabled = true, hasOverlayPermission = false))

        // Generic container viewIds ("content", "search", "input", "message") must NOT match as editable
        assertFalse(
            EditableNodePolicy.isEditableCandidate(
                isEditable = false,
                className = "android.widget.FrameLayout",
                viewIdResourceName = "android:id/content",
                supportsActionSetText = false
            )
        )
        assertFalse(
            EditableNodePolicy.isEditableCandidate(
                isEditable = false,
                className = "android.widget.LinearLayout",
                viewIdResourceName = "com.sec.android.app.launcher:id/search_container",
                supportsActionSetText = false
            )
        )
        assertTrue(
            EditableNodePolicy.isEditableCandidate(
                isEditable = false,
                className = "android.widget.EditText",
                viewIdResourceName = null,
                supportsActionSetText = false
            )
        )
        assertTrue(
            EditableNodePolicy.isEditableCandidate(
                isEditable = true,
                className = "android.view.View",
                viewIdResourceName = null,
                supportsActionSetText = false
            )
        )

        // Device lock/screen off suppresses keyboard/bubble; IME close & launcher return clear stale fallback focus
        assertTrue(KeyboardVisibilityGuard.shouldSuppressForDeviceState(screenInteractive = true, deviceLocked = true))
        assertTrue(KeyboardVisibilityGuard.shouldSuppressForDeviceState(screenInteractive = false, deviceLocked = false))
        assertFalse(KeyboardVisibilityGuard.shouldSuppressForDeviceState(screenInteractive = true, deviceLocked = false))

        assertTrue(
            KeyboardVisibilityGuard.shouldClearFallbackFocusOnTransition(
                wasImeVisible = true,
                isImeVisible = false,
                focusEventJustConfirmed = false,
                activeWindowIsLauncherOrIgnored = false,
                focusNodePackage = "com.whatsapp",
                activePackage = "com.whatsapp"
            )
        )
        assertTrue(
            KeyboardVisibilityGuard.shouldClearFallbackFocusOnTransition(
                wasImeVisible = false,
                isImeVisible = false,
                focusEventJustConfirmed = false,
                activeWindowIsLauncherOrIgnored = true,
                focusNodePackage = "com.whatsapp",
                activePackage = "com.sec.android.app.launcher"
            )
        )
        assertFalse(
            KeyboardVisibilityGuard.shouldClearFallbackFocusOnTransition(
                wasImeVisible = false,
                isImeVisible = true,
                focusEventJustConfirmed = false,
                activeWindowIsLauncherOrIgnored = false,
                focusNodePackage = "com.whatsapp",
                activePackage = "com.whatsapp"
            )
        )
    }

    @Test
    fun `bubble health rate limiter caps at 60 events per minute and recovers`() {
        var now = 1_000L
        val limiter = BubbleHealthRateLimiter(capPerWindow = 60, windowMs = 60_000L) { now }
        repeat(60) {
            assertTrue("Event $it should be allowed", limiter.tryAcquire())
        }
        assertFalse("61st event in window must be dropped", limiter.tryAcquire())

        now += 60_000L
        assertTrue("Limiter must recover after 60s window passes", limiter.tryAcquire())
    }

    @Test
    fun `a11y watchdog policy handles post update grace rebinding permission revoked and healthy`() {
        // Bubble disabled -> NONE
        assertEquals(
            A11yRepairKind.NONE,
            A11yWatchdogPolicy.evaluateA11yHealth(
                bubbleEnabled = false,
                settingsEnabled = false,
                serviceConnected = false,
                inPostUpdateGrace = false,
                unhealthySinceElapsedMs = 1_000L,
                nowElapsedMs = 30_000L
            )
        )
        // Settings disabled -> PERMISSION_REVOKED
        assertEquals(
            A11yRepairKind.PERMISSION_REVOKED,
            A11yWatchdogPolicy.evaluateA11yHealth(
                bubbleEnabled = true,
                settingsEnabled = false,
                serviceConnected = false,
                inPostUpdateGrace = false,
                unhealthySinceElapsedMs = 1_000L,
                nowElapsedMs = 30_000L
            )
        )
        // Post-update grace suppresses REBINDING
        assertEquals(
            A11yRepairKind.NONE,
            A11yWatchdogPolicy.evaluateA11yHealth(
                bubbleEnabled = true,
                settingsEnabled = true,
                serviceConnected = false,
                inPostUpdateGrace = true,
                unhealthySinceElapsedMs = 1_000L,
                nowElapsedMs = 30_000L
            )
        )
        // Under 15s disconnected -> NONE
        assertEquals(
            A11yRepairKind.NONE,
            A11yWatchdogPolicy.evaluateA11yHealth(
                bubbleEnabled = true,
                settingsEnabled = true,
                serviceConnected = false,
                inPostUpdateGrace = false,
                unhealthySinceElapsedMs = 10_000L,
                nowElapsedMs = 24_999L
            )
        )
        // >= 15s disconnected with Settings ON -> REBINDING
        assertEquals(
            A11yRepairKind.REBINDING,
            A11yWatchdogPolicy.evaluateA11yHealth(
                bubbleEnabled = true,
                settingsEnabled = true,
                serviceConnected = false,
                inPostUpdateGrace = false,
                unhealthySinceElapsedMs = 10_000L,
                nowElapsedMs = 25_000L
            )
        )
        // Bound -> NONE
        assertEquals(
            A11yRepairKind.NONE,
            A11yWatchdogPolicy.evaluateA11yHealth(
                bubbleEnabled = true,
                settingsEnabled = true,
                serviceConnected = true,
                inPostUpdateGrace = false,
                unhealthySinceElapsedMs = 10_000L,
                nowElapsedMs = 50_000L
            )
        )
    }

    @Test
    fun `robolectric checks samsung oem guide and boot receiver package update reconcile`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        AppLogRepository.init(context)

        val samsungGuide = DeviceProtectionManager.resolveOemGuide("Samsung")
        assertEquals("Samsung", samsungGuide.manufacturerLabel)
        assertTrue(samsungGuide.targets.any { it.first == "com.samsung.android.lool" })

        // Ensure bubble enabled is false so BOOT_COMPLETED records skipped_disabled cleanly
        context.getSharedPreferences("voxstream_bubble_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("bubble_enabled", false)
            .apply()

        val receiver = BubbleBootReceiver()
        receiver.onReceive(context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertTrue(BubbleHealthLog.isInPostUpdateGraceWindow(context))

        receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
    }
}
