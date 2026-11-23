package com.princeniithompson.rapcha.service.floating

import android.content.Context
import android.os.Build
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import com.princeniithompson.rapcha.service.A11yRepairKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FloatingBubbleOptimizationTest {

    @Test
    fun `overlay vertical position math docks above keyboard when IME visible and restores saved Y otherwise`() {
        // IME hidden -> uses clamped savedY
        val hiddenY = OverlayVerticalPositionMath.resolveY(
            savedY = 920,
            imeTopPx = 0,
            savedOffsetAboveImePx = 36,
            windowHeightPx = 150,
            topY = 80,
            fallbackMaxY = 1800,
            minGapAboveImePx = 12
        )
        assertEquals(920, hiddenY)

        // IME visible at 1400px -> docks 36px above IME (1400 - 36 - 150 = 1214)
        val dockedY = OverlayVerticalPositionMath.resolveY(
            savedY = 300,
            imeTopPx = 1400,
            savedOffsetAboveImePx = 36,
            windowHeightPx = 150,
            topY = 80,
            fallbackMaxY = 1800,
            minGapAboveImePx = 12
        )
        assertEquals(1214, dockedY)

        // Small offset clamps cleanly to minGapAboveImePx so bubble never overlaps keyboard keys
        val clampedGapY = OverlayVerticalPositionMath.resolveY(
            savedY = 300,
            imeTopPx = 1400,
            savedOffsetAboveImePx = 0,
            windowHeightPx = 150,
            topY = 80,
            fallbackMaxY = 1800,
            minGapAboveImePx = 12
        )
        assertEquals(1400 - 12 - 150, clampedGapY)

        // Tiny screen clamps above topY
        val topClampedY = OverlayVerticalPositionMath.resolveY(
            savedY = 10,
            imeTopPx = 180,
            savedOffsetAboveImePx = 60,
            windowHeightPx = 150,
            topY = 80,
            fallbackMaxY = 1800,
            minGapAboveImePx = 12
        )
        assertEquals(80, topClampedY)
    }

    @Test
    fun `device performance tier flags Android 11 and 3-4GB hardware as low power`() {
        // Infinix Hot 11 (3 GB RAM, Android 11 API 30)
        assertTrue(DevicePerformanceTier.evaluate(isLowRamDevice = false, totalMemBytes = 3_000_000_000L, sdkInt = Build.VERSION_CODES.R))
        // 4 GB Samsung A15 on Android 14
        assertTrue(DevicePerformanceTier.evaluate(isLowRamDevice = false, totalMemBytes = 4_000_000_000L, sdkInt = 34))
        // Android Go / lowRamDevice flag
        assertTrue(DevicePerformanceTier.evaluate(isLowRamDevice = true, totalMemBytes = 8_000_000_000L, sdkInt = 34))
        // Unreported memory (0L) on SDK 33+ keeps full shader fidelity (e.g. screenshot test env)
        assertFalse(DevicePerformanceTier.evaluate(isLowRamDevice = false, totalMemBytes = 0L, sdkInt = 33))
        // Pixel 7 (8 GB RAM, Android 14) runs full shaders
        assertFalse(DevicePerformanceTier.evaluate(isLowRamDevice = false, totalMemBytes = 8_000_000_000L, sdkInt = 34))
    }

    @Test
    fun `native idle bubble view handles state transitions tap and repair clicks cleanly`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var ringClicks = 0
        var repairClicks = 0
        var drags = 0

        val bubbleView = IdleBubbleNativeView(
            context = context,
            onRingClick = { ringClicks++ },
            onRepairClick = { repairClicks++ },
            onLongPressBubble = {},
            onDragStart = { _, _ -> },
            onDrag = { _, _ -> drags++ },
            onDragEnd = { _, _ -> }
        )

        bubbleView.layout(0, 0, 200, 200)
        bubbleView.updateState(
            recording = false,
            shrunk = true,
            snappedToRight = false,
            networkProblem = true,
            clipboardFallback = false,
            repairKind = A11yRepairKind.NONE
        )
        assertTrue(bubbleView.isShrunk)
        assertFalse(bubbleView.isSnappedToRight)
        assertTrue(bubbleView.isNetworkProblem)

        // Tap without drag triggers ring click
        val down = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 100f, 100f, 0)
        val up = MotionEvent.obtain(0L, 50L, MotionEvent.ACTION_UP, 100f, 100f, 0)
        bubbleView.onTouchEvent(down)
        bubbleView.onTouchEvent(up)
        down.recycle()
        up.recycle()
        assertEquals(1, ringClicks)
        assertEquals(0, repairClicks)

        // When repair state is active, tap invokes repair callback instead of ring click
        bubbleView.updateState(repairKind = A11yRepairKind.PERMISSION_REVOKED)
        val down2 = MotionEvent.obtain(100L, 100L, MotionEvent.ACTION_DOWN, 100f, 100f, 0)
        val up2 = MotionEvent.obtain(100L, 150L, MotionEvent.ACTION_UP, 100f, 100f, 0)
        bubbleView.onTouchEvent(down2)
        bubbleView.onTouchEvent(up2)
        down2.recycle()
        up2.recycle()
        assertEquals(1, ringClicks)
        assertEquals(1, repairClicks)

        bubbleView.release()
    }
}
