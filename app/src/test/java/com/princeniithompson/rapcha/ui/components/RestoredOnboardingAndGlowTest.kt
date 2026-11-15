package com.princeniithompson.rapcha.ui.components

import com.princeniithompson.rapcha.ui.screens.onboarding.SandboxPolish
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** v26 guards: one glow, no haptics, no VIBRATE, mic + Android 11 speech queries present. */
class RestoredOnboardingAndGlowTest {
    private val main = File("src/main")

    @Test
    fun exactly_one_glow_the_v24_default() {
        assertEquals(1, GlowAnimationCatalogue.styles.size)
        assertEquals("breathing_horizon", GlowAnimationCatalogue.DEFAULT_ID)
        assertEquals("breathing_horizon", GlowAnimationCatalogue.getById("anything").id)
    }

    @Test
    fun no_haptics_or_vibration_anywhere() {
        val offenders = main.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { f ->
                val t = f.readText()
                listOf("performHapticFeedback", "Vibrator", "VibrationEffect", "LocalHapticFeedback", ".vibrate(")
                    .any { t.contains(it) }
            }
            .map { it.name }
            .toList()
        assertTrue("haptics found in $offenders", offenders.isEmpty())
        val manifest = File(main, "AndroidManifest.xml").readText()
        assertFalse(manifest.contains("android.permission.VIBRATE"))
    }

    @Test
    fun manifest_keeps_mic_notifications_and_android_11_speech_query() {
        val manifest = File(main, "AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android.permission.RECORD_AUDIO"))
        assertTrue(manifest.contains("android.permission.POST_NOTIFICATIONS"))
        assertTrue(manifest.contains("android.speech.RecognitionService"))
    }

    @Test
    fun sandbox_polish_tidies_without_network() {
        assertEquals("Hello world.", SandboxPolish.tidy("  hello world "))
        assertEquals("Is it done?", SandboxPolish.tidy("is it done?"))
        assertEquals("", SandboxPolish.tidy("   "))
    }
}
