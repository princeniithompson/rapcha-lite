package com.princeniithompson.rapcha.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SmartModeSettingTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun smart_mode_is_on_by_default() {
        assertTrue(SmartModeSetting.isEnabled(ctx))
    }

    @Test
    fun old_v24_smart_mode_false_does_not_turn_it_off() {
        ctx.getSharedPreferences("voxstream_settings", Context.MODE_PRIVATE)
            .edit().putBoolean("smart_mode", false).commit()
        assertTrue(SmartModeSetting.isEnabled(ctx))
    }

    @Test
    fun switching_off_persists() {
        SmartModeSetting.setEnabled(ctx, false)
        assertFalse(SmartModeSetting.isEnabled(ctx))
        SmartModeSetting.setEnabled(ctx, true)
        assertTrue(SmartModeSetting.isEnabled(ctx))
    }
}
