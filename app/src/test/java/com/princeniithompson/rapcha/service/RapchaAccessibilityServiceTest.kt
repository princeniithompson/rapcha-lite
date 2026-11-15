package com.princeniithompson.rapcha.service

import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RapchaAccessibilityServiceTest {

    private val service = RapchaAccessibilityService()

    @Test
    fun `extractGenuineText keeps text when the hint flag is set but hintText does not match`() {
        val node = AccessibilityNodeInfo.obtain()
        node.text = "Ask Google…"
        node.isShowingHintText = true

        val result = service.extractGenuineText(node)
        assertEquals("Ask Google…", result)
    }

    @Test
    fun `extractGenuineText keeps text that matches hintText when the hint flag is off`() {
        val node = AccessibilityNodeInfo.obtain()
        node.text = "Message"
        node.hintText = "Message"
        node.isShowingHintText = false

        val result = service.extractGenuineText(node)
        assertEquals("Message", result)
    }

    @Test
    fun `extractGenuineText returns empty only for an active hint`() {
        val node = AccessibilityNodeInfo.obtain()
        node.text = "Ask Google…"
        node.hintText = "Ask Google…"
        node.isShowingHintText = true

        val result = service.extractGenuineText(node)
        assertEquals("", result)
    }

    @Test
    fun `extractGenuineText keeps a short placeholder that is not an active hint`() {
        val node = AccessibilityNodeInfo.obtain()
        node.text = "Message"

        val result = service.extractGenuineText(node)
        assertEquals("Message", result)
    }

    @Test
    fun `extractGenuineText keeps short mention text when the hint flag is set`() {
        val node = AccessibilityNodeInfo.obtain()
        node.text = "@princeniithompson"
        node.hintText = "Ask Gemini"
        node.isShowingHintText = true

        val result = service.extractGenuineText(node)
        assertEquals("@princeniithompson", result)
    }

    @Test
    fun `extractGenuineText preserves genuine user typed text`() {
        val node = AccessibilityNodeInfo.obtain()
        node.text = "Hello world"
        node.hintText = "Search"
        node.isShowingHintText = false

        val result = service.extractGenuineText(node)
        assertEquals("Hello world", result)
    }

    @Test
    fun `accessibility service description says it reads the screen and types into the focused field`() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val description = context.getString(com.princeniithompson.rapcha.R.string.accessibility_service_desc)
        assertTrue(description.contains("reads on-screen content"))
        assertTrue(description.contains("detect the current app"))
        assertTrue(description.contains("types dictated text into the focused field"))
    }

    @Test
    fun `extractGenuineText returns empty for blank node text`() {
        val node = AccessibilityNodeInfo.obtain()
        node.text = "   "

        val result = service.extractGenuineText(node)
        assertEquals("", result)
    }

    @Test
    fun `commitTextViaInputMethod returns false when InputConnection is unavailable`() {
        val result = service.commitTextViaInputMethod("Test text")
        // When input connection is unavailable or not started, must return false to allow fallbacks
        org.junit.Assert.assertFalse(result)
    }

    @Test
    fun `injectText returns false for empty text`() {
        val result = kotlinx.coroutines.runBlocking { service.injectText("") }
        org.junit.Assert.assertFalse(result)
    }

    @Test
    fun `v25 banking apps are not blocked - IgnoredPackages keeps the v24 rules`() {
        org.junit.Assert.assertFalse(IgnoredPackages.isIgnoredPackage(null, "com.chase.sig.android"))
        org.junit.Assert.assertFalse(IgnoredPackages.isIgnoredPackage(null, "com.whatsapp"))
        org.junit.Assert.assertTrue(IgnoredPackages.isIgnoredPackage(null, null))
        org.junit.Assert.assertTrue(IgnoredPackages.isIgnoredPackage(null, " "))
        org.junit.Assert.assertTrue(IgnoredPackages.isIgnoredPackage(null, "android"))
        org.junit.Assert.assertTrue(IgnoredPackages.isIgnoredPackage(null, "com.android.systemui"))
        org.junit.Assert.assertTrue(IgnoredPackages.isIgnoredPackage(null, "com.google.android.inputmethod.latin"))
        org.junit.Assert.assertTrue(IgnoredPackages.isIgnoredPackage(null, "com.samsung.android.honeyboard"))
        org.junit.Assert.assertTrue(IgnoredPackages.isIgnoredPackage(null, "com.touchtype.swiftkey"))
        org.junit.Assert.assertTrue(IgnoredPackages.isIgnoredPackage(null, "com.transsion.keyboard"))
        org.junit.Assert.assertTrue(IgnoredPackages.isIgnoredPackage(null, "com.example.ime"))
        org.junit.Assert.assertTrue(IgnoredPackages.isIgnoredPackage(null, "com.princeniithompson.rapcha"))
    }

    @Test
    fun `RapchaInputMethodService commitText returns false when service is uninitialized`() {
        val result = RapchaInputMethodService.commitText("Hello")
        org.junit.Assert.assertFalse("Must return false when IME instance is null", result)
    }
}
