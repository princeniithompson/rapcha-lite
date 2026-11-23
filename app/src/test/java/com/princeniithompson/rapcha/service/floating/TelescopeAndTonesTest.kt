package com.princeniithompson.rapcha.service.floating

import android.content.Context
import android.graphics.Rect
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.princeniithompson.rapcha.core.ApiConfig
import com.princeniithompson.rapcha.data.FloatingTextActionsClient
import com.princeniithompson.rapcha.data.ReplyLength
import com.princeniithompson.rapcha.data.TextActionRequest
import com.princeniithompson.rapcha.data.TextActionResult
import com.princeniithompson.rapcha.data.ToneRepository
import com.princeniithompson.rapcha.data.TranslateLanguage
import com.princeniithompson.rapcha.service.FloatingBubbleService
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TelescopeAndTonesTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("rapcha_tones_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("voxstream_settings", Context.MODE_PRIVATE).edit().clear().commit()
        ToneRepository.resetForTests()
        FloatingTextActionsClient.httpExecutor = null
    }

    @After
    fun tearDown() {
        FloatingTextActionsClient.httpExecutor = null
        ToneRepository.resetForTests()
    }

    @Test
    fun toneRepository_defaultsAndCustomCrudAndReorder_persistCleanly() {
        ToneRepository.init(context)

        val defaultNames = ToneRepository.tones.value.map { it.name }
        assertEquals(
            listOf("Professional", "Romantic", "Friendly", "Funny", "Social", "Sarcastic", "Direct"),
            defaultNames
        )
        assertEquals("Short", ToneRepository.lastReplyLength.value)
        assertEquals("English", ToneRepository.lastTranslateLanguage.value)

        // Toggle default tone off
        ToneRepository.setToneEnabled("default_romantic", false)
        assertFalse(ToneRepository.getEnabledTones().any { it.id == "default_romantic" })

        // Add custom tone with >19 char name -> capped at 19 chars
        val added = ToneRepository.addCustomTone(
            name = "IT Engineer Super Long Name",
            instruction = "Rewrite like a senior IT engineer."
        )
        assertTrue(added)
        val custom = ToneRepository.tones.value.first { !it.isDefault }
        assertEquals(ToneRepository.MAX_NAME_LENGTH, custom.name.length)
        assertEquals("IT Engineer Super L", custom.name)

        // Case-insensitive duplicate tone check
        assertTrue(ToneRepository.isToneNameDuplicate("sarcastic"))
        assertFalse(ToneRepository.addCustomTone("sarcastic", "Duplicate should fail"))

        // Edit custom tone
        assertTrue(ToneRepository.editCustomTone(custom.id, "Vibe Coding", "Sound like a senior SRE."))
        assertEquals("Vibe Coding", ToneRepository.tones.value.first { it.id == custom.id }.name)

        // Reorder tones -> put custom tone first
        val newOrder = listOf(custom.id) + ToneRepository.tones.value.map { it.id }.filterNot { it == custom.id }
        ToneRepository.reorderTones(newOrder)
        assertEquals(custom.id, ToneRepository.tones.value.first().id)

        // Persist reply length (including No Reply) & translate language
        ToneRepository.setLastReplyLength("No Reply")
        ToneRepository.setLastTranslateLanguage("\uD83C\uDDEB\uD83C\uDDF7 French")

        // Reload from SharedPreferences
        ToneRepository.resetForTests()
        ToneRepository.init(context)
        assertFalse(ToneRepository.getEnabledTones().any { it.id == "default_romantic" })
        assertEquals(custom.id, ToneRepository.tones.value.first().id)
        assertEquals("Vibe Coding", ToneRepository.getEnabledTones().first().name)
        assertEquals("No Reply", ToneRepository.lastReplyLength.value)
        assertEquals("\uD83C\uDDEB\uD83C\uDDF7 French", ToneRepository.lastTranslateLanguage.value)

        // Delete custom tone
        assertTrue(ToneRepository.deleteCustomTone(custom.id))
        assertFalse(ToneRepository.tones.value.any { it.id == custom.id })
    }

    @Test
    fun modulesAndTargetLanguages_crudDuplicateCheckDisableAndReorder_persistCleanly() {
        ToneRepository.init(context)

        // Default modules order and configurability
        assertEquals(
            listOf("Grammar", "Translate", "Reply", "Tone"),
            ToneRepository.modules.value.map { it.name }
        )
        val byId = ToneRepository.modules.value.associateBy { it.id }
        assertFalse(byId[ToneRepository.MODULE_GRAMMAR]!!.isConfigurable)
        assertTrue(byId[ToneRepository.MODULE_TRANSLATE]!!.isConfigurable)
        assertFalse(byId[ToneRepository.MODULE_REPLY]!!.isConfigurable)
        assertTrue(byId[ToneRepository.MODULE_TONE]!!.isConfigurable)

        // Duplicate built-in module name rejected case-insensitively
        assertTrue(ToneRepository.isModuleNameDuplicate("reply"))
        assertFalse(ToneRepository.addCustomModule("reply", "Make a polite excuse"))

        // Create custom module (19 char cap, 500 char cap)
        assertTrue(ToneRepository.addCustomModule("Add Emojis", "Add relevant emojis inside the target text."))
        val customMod = ToneRepository.modules.value.first { !it.isBuiltIn }
        assertEquals("Add Emojis", customMod.name)
        assertFalse(customMod.isConfigurable)

        // Edit custom module & disable Grammar
        assertTrue(ToneRepository.editCustomModule(customMod.id, "Emoji Boost", "Add subtle emojis."))
        ToneRepository.setModuleEnabled(ToneRepository.MODULE_GRAMMAR, false)
        assertFalse(ToneRepository.getEnabledModules().any { it.id == ToneRepository.MODULE_GRAMMAR })

        // Reorder modules: custom module first, then Reply, Tone, Translate, Grammar
        val reordered = listOf(
            customMod.id,
            ToneRepository.MODULE_REPLY,
            ToneRepository.MODULE_TONE,
            ToneRepository.MODULE_TRANSLATE,
            ToneRepository.MODULE_GRAMMAR
        )
        ToneRepository.reorderModules(reordered)

        // Target languages default order + add custom dialect + reorder
        assertEquals(
            listOf(
                "\uD83C\uDDEC\uD83C\uDDE7 English",
                "\uD83C\uDDEA\uD83C\uDDF8 Spanish",
                "\uD83C\uDDEB\uD83C\uDDF7 French",
                "\uD83C\uDDE9\uD83C\uDDEA German",
                "\uD83C\uDDEE\uD83C\uDDF9 Italian"
            ),
            ToneRepository.languages.value.map { it.name }
        )
        assertTrue(ToneRepository.addCustomLanguage("\uD83C\uDDF3\uD83C\uDDEC Pidgin", "Natural Nigerian Pidgin"))
        val pidgin = ToneRepository.languages.value.first { !it.isDefault }
        ToneRepository.reorderLanguages(listOf(pidgin.id) + ToneRepository.languages.value.map { it.id }.filterNot { it == pidgin.id })

        // Reload and confirm full persistence
        ToneRepository.resetForTests()
        ToneRepository.init(context)
        assertEquals(reordered, ToneRepository.modules.value.map { it.id })
        assertFalse(ToneRepository.getEnabledModules().any { it.id == ToneRepository.MODULE_GRAMMAR })
        assertEquals("Emoji Boost", ToneRepository.modules.value.first().name)
        assertEquals(pidgin.id, ToneRepository.languages.value.first().id)

        // Cannot delete built-in module, can delete custom module & custom language
        assertFalse(ToneRepository.deleteCustomModule(ToneRepository.MODULE_TRANSLATE))
        assertTrue(ToneRepository.deleteCustomModule(customMod.id))
        assertTrue(ToneRepository.deleteCustomLanguage(pidgin.id))
    }

    @Test
    fun telescopeExecutionPolicy_defersTranslateReplyTone_andRunsGrammarAndCustomImmediately() {
        assertTrue(TelescopeExecutionPolicy.executesImmediatelyOnTap(ToneRepository.MODULE_GRAMMAR))
        assertTrue(TelescopeExecutionPolicy.executesImmediatelyOnTap("custom_mod_12345678"))
        assertFalse(TelescopeExecutionPolicy.executesImmediatelyOnTap(ToneRepository.MODULE_TRANSLATE))
        assertFalse(TelescopeExecutionPolicy.executesImmediatelyOnTap(ToneRepository.MODULE_REPLY))
        assertFalse(TelescopeExecutionPolicy.executesImmediatelyOnTap(ToneRepository.MODULE_TONE))
    }

    @Test
    fun builtInToneEmojis_customIconPersistence_windowFilter_andLiveReorderHelper() {
        ToneRepository.init(context)

        // Exact default emojis on all 7 built-in tones
        assertEquals(
            listOf(
                "\uD83D\uDCBC Professional",
                "\uD83C\uDF39 Romantic",
                "\uD83D\uDE42 Friendly",
                "\uD83D\uDE02 Funny",
                "\uD83D\uDE03 Social",
                "\uD83D\uDE44 Sarcastic",
                "\uD83C\uDFAF Direct"
            ),
            ToneRepository.tones.value.map { it.displayLabel }
        )

        // Custom tone with picked vector icon + accent color & custom module with picked emoji persist
        assertTrue(
            ToneRepository.addCustomTone(
                name = "Vibe Coder",
                instruction = "Write concise technical replies.",
                iconKey = "code",
                iconColorKey = "sky"
            )
        )
        assertTrue(
            ToneRepository.addCustomModule(
                name = "Sparkle",
                query = "Add tasteful sparkle.",
                iconEmoji = "\u2728"
            )
        )

        ToneRepository.resetForTests()
        ToneRepository.init(context)

        val savedTone = ToneRepository.tones.value.first { it.name == "Vibe Coder" }
        assertEquals("code", savedTone.iconKey)
        assertEquals("sky", savedTone.iconColorKey)
        assertEquals("", savedTone.iconEmoji)

        val savedMod = ToneRepository.modules.value.first { it.name == "Sparkle" }
        assertEquals("\u2728", savedMod.iconEmoji)
        assertEquals("", savedMod.iconKey)

        // Window type filter allows TYPE_APPLICATION (incl. Rapcha screens) and rejects overlays/IME/system
        assertTrue(ScreenTextSnapshot.isAllowedWindowType(android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION))
        assertFalse(ScreenTextSnapshot.isAllowedWindowType(android.view.accessibility.AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY))
        assertFalse(ScreenTextSnapshot.isAllowedWindowType(android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM))
        assertFalse(ScreenTextSnapshot.isAllowedWindowType(android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD))

        // Live swap reorder helper moves items cleanly
        val ids = listOf("a", "b", "c", "d")
        assertEquals(listOf("b", "c", "a", "d"), com.princeniithompson.rapcha.ui.screens.ChipCloudReorderHelper.moveItem(ids, 0, 2))
        assertEquals(ids, com.princeniithompson.rapcha.ui.screens.ChipCloudReorderHelper.moveItem(ids, 1, 1))
    }

    @Test
    fun floatingTextActionsClient_promptsAlwaysEndWithResultOnlySuffix_supportsNoReplyAndCustomModule() = runBlocking {
        // Exact 4 Reply chips in order: Short | Medium | Long | No Reply
        assertEquals(
            listOf("Short", "Medium", "Long", "No Reply"),
            ReplyLength.entries.map { it.displayName }
        )

        val requests = listOf(
            TextActionRequest.Grammar("helo wrld"),
            TextActionRequest.Translate("Good morning", TranslateLanguage.FRENCH),
            TextActionRequest.Translate("How are you?", "\uD83C\uDDF3\uD83C\uDDEC Pidgin", "Natural Nigerian Pidgin"),
            TextActionRequest.Reply("Are we still meeting at 4?", ReplyLength.MEDIUM),
            TextActionRequest.Reply("Can you come downstairs?", ReplyLength.NO_REPLY),
            TextActionRequest.Tone("fix the server now", "IT Engineer", "Sound like a senior IT engineer"),
            TextActionRequest.CustomModule("See you soon", "Add Emojis", "Add emojis inside the target text.")
        )
        requests.forEach { req ->
            val prompt = FloatingTextActionsClient.buildPrompt(req)
            assertTrue(
                "Prompt must end with strict suffix: $prompt",
                prompt.endsWith(FloatingTextActionsClient.RESULT_ONLY_SUFFIX)
            )
        }

        val noReplyPrompt = FloatingTextActionsClient.buildPrompt(
            TextActionRequest.Reply("Can you come downstairs?", ReplyLength.NO_REPLY)
        )
        assertTrue(noReplyPrompt.contains("believable excuse declining the request or invitation"))
        assertTrue(noReplyPrompt.contains("Message:\nCan you come downstairs?"))

        // Put a non-placeholder test key in prefs
        context.getSharedPreferences("voxstream_settings", Context.MODE_PRIVATE)
            .edit()
            .putString("custom_api_key", "AIzaSyTestKeyForUnitVerification12345")
            .commit()

        var capturedUrl = ""
        var capturedKey = ""
        FloatingTextActionsClient.httpExecutor = { url, apiKey, _ ->
            capturedUrl = url
            capturedKey = apiKey
            200 to """{"candidates":[{"content":{"parts":[{"text":"\"Bonjour !\""}]}}]}"""
        }

        val result = FloatingTextActionsClient.execute(
            context,
            TextActionRequest.Translate("Good morning!", TranslateLanguage.FRENCH)
        )

        assertTrue(result is TextActionResult.Success)
        assertEquals("Bonjour !", (result as TextActionResult.Success).text)
        assertEquals(ApiConfig.geminiGenerateContentUrl("gemini-3.5-flash-lite"), capturedUrl)
        assertFalse("API key must never be in URL query string", capturedUrl.contains("key="))
        assertEquals("AIzaSyTestKeyForUnitVerification12345", capturedKey)
    }

    @Test
    fun screenTextSnapshot_ignoresTimestamps_capsAt150_andTightestBubbleWins() {
        assertTrue(ScreenTextSnapshot.isIgnoredTimestampOrBadge("10:34 AM"))
        assertTrue(ScreenTextSnapshot.isIgnoredTimestampOrBadge("10:00"))
        assertTrue(ScreenTextSnapshot.isIgnoredTimestampOrBadge("Edited 10:45 PM"))
        assertTrue(ScreenTextSnapshot.isIgnoredTimestampOrBadge("Yesterday"))
        assertTrue(ScreenTextSnapshot.isIgnoredTimestampOrBadge("✓✓"))
        assertFalse(ScreenTextSnapshot.isIgnoredTimestampOrBadge("Hey, are you free at 10:34 AM?"))

        val containerNode = ScreenTextNode("Whole chat list wrapper", Rect(0, 0, 1080, 1800))
        val messageBubble = ScreenTextNode("See you at lunch!", Rect(120, 400, 620, 520))
        val timestampBadge = ScreenTextNode("10:34 AM", Rect(540, 480, 615, 515))

        val normalized = ScreenTextSnapshot.normalizeAndSort(
            listOf(containerNode, timestampBadge, messageBubble)
        )
        assertEquals(2, normalized.size)
        assertEquals(messageBubble, normalized.first())

        val hit = ScreenTextSnapshot.findNodeAt(normalized, rawX = 300, rawY = 460)
        assertNotNull(hit)
        assertEquals("See you at lunch!", hit?.text)
        assertNull(ScreenTextSnapshot.findNodeAt(normalized, rawX = 1200, rawY = 2000))

        // Cap enforcement at 150 visible nodes
        val manyNodes = (1..220).map { i ->
            ScreenTextNode("Message number $i", Rect(10, i * 10, 400, i * 10 + 40))
        }
        val capped = ScreenTextSnapshot.normalizeAndSort(manyNodes)
        assertEquals(ScreenTextSnapshot.MAX_VISIBLE_TEXT_NODES, capped.size)
    }

    @Test
    fun alwaysOnScreen_preservesDefaultBehavior_andAllowsTapWithoutKeyboardWhenEnabled() {
        // Default OFF -> identical to existing keyboard-only behavior
        assertEquals(
            FloatingBubbleService.RingTap.HIDE,
            FloatingBubbleService.decideRingTap(recording = false, keyboardVisible = false, fieldFocused = false)
        )
        assertFalse(
            FloatingBubbleService.startsRecordingOnTap(recording = false, keyboardVisible = false, fieldFocused = false)
        )
        assertTrue(
            FloatingBubbleService.keyboardRecheckShouldHide(imeVisible = false, sessionActive = false)
        )

        // Always on Screen ON -> bubble stays visible and tapping starts voice typing even when keyboard is closed
        assertEquals(
            FloatingBubbleService.RingTap.CONTINUE,
            FloatingBubbleService.decideRingTap(
                recording = false,
                keyboardVisible = false,
                fieldFocused = false,
                alwaysOnScreen = true
            )
        )
        assertTrue(
            FloatingBubbleService.startsRecordingOnTap(
                recording = false,
                keyboardVisible = false,
                fieldFocused = false,
                alwaysOnScreen = true
            )
        )
        assertFalse(
            FloatingBubbleService.keyboardRecheckShouldHide(
                imeVisible = false,
                sessionActive = false,
                alwaysOnScreen = true
            )
        )
    }

    @Test
    fun bubbleInteractionPolicy_twoStepWakeTapStayHiddenAndEarlyTuckAndKeepScreenOn() {
        // Hidden/half-tucked bubble: 1st tap wakes only (does not start voice typing)
        assertTrue(BubbleInteractionPolicy.shouldConsumeTapToWake(wasHiddenOnTouchDown = true))
        // Already awake bubble: tap starts voice typing immediately
        assertFalse(BubbleInteractionPolicy.shouldConsumeTapToWake(wasHiddenOnTouchDown = false))
        // Closing keyboard must never pop an already-hidden bubble back out
        assertFalse(
            BubbleInteractionPolicy.shouldWakeOnKeyboardHide(
                alwaysOnScreen = true,
                alreadyHidden = true
            )
        )
        // Always on Screen ON + awake bubble + keyboard closes early -> immediately tuck back to side bezel
        assertTrue(
            BubbleInteractionPolicy.shouldTuckImmediatelyOnKeyboardHide(
                alwaysOnScreen = true,
                alreadyHidden = false,
                isSessionOrTelescopeActive = false
            )
        )
        assertFalse(
            BubbleInteractionPolicy.shouldTuckImmediatelyOnKeyboardHide(
                alwaysOnScreen = true,
                alreadyHidden = false,
                isSessionOrTelescopeActive = true
            )
        )
        // Keep-screen-on flag is enabled ONLY while voice typing is active
        val recordingFlags = FloatingOverlayWindowManager.resolveOverlayFlags(keepScreenOn = true)
        val idleFlags = FloatingOverlayWindowManager.resolveOverlayFlags(keepScreenOn = false)
        assertTrue((recordingFlags and android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0)
        assertEquals(0, idleFlags and android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    @Test
    fun collapsedBubble_crossesKeyboardWithoutVerticalJump_whileExpandedStillDocksAboveIme() {
        val handler = FloatingDragSnapHandler(context)
        val displayHeight = context.resources.displayMetrics.heightPixels
        val density = context.resources.displayMetrics.density
        val windowSize = (BubbleChromeGeometry.IDLE_WINDOW_SIZE_DP * density).toInt()
        val imeTop = (displayHeight * 0.55f).toInt()
        val parkedOverKeyboardY = (displayHeight * 0.72f).toInt()

        handler.savePreferences(snappedRight = true, y = parkedOverKeyboardY)

        // Collapsed lifesaver bubble keeps its saved Y even when keyboard is open (no jump/flash)
        val collapsedWithIme = handler.resolveYForWindow(
            windowHeightPx = windowSize,
            isExpanded = false,
            imeTopPx = imeTop
        )
        val collapsedWithoutIme = handler.resolveYForWindow(
            windowHeightPx = windowSize,
            isExpanded = false,
            imeTopPx = 0
        )
        assertEquals(collapsedWithoutIme, collapsedWithIme)
        assertTrue(
            "Collapsed bubble should be allowed to rest below imeTop ($imeTop), got $collapsedWithIme",
            collapsedWithIme > imeTop
        )

        // Expanded recording bar still docks cleanly above imeTop
        val expandedHeight = (BubbleChromeGeometry.RECORDING_WINDOW_HEIGHT_DP * density).toInt()
        val expandedWithIme = handler.resolveYForWindow(
            windowHeightPx = expandedHeight,
            isExpanded = true,
            imeTopPx = imeTop
        )
        assertTrue(
            "Expanded recording popup should stay above imeTop ($imeTop), got $expandedWithIme",
            expandedWithIme + expandedHeight <= imeTop
        )
    }

    @Test
    fun magnifierGeometry_rigidOffsetWithoutFlipping_andLensCenterAnchor() {
        val density = 3f
        val windowSizePx = (BubbleChromeGeometry.IDLE_WINDOW_SIZE_DP * density).toInt()
        val screenW = 1080
        val screenH = 2400

        // Same constant diagonal offset on both left and right halves of screen (zero flipping)
        val leftSide = TelescopeMagnifierGeometry.computePlacement(
            rawFingerX = 250f,
            rawFingerY = 600f,
            windowSizePx = windowSizePx,
            density = density,
            screenWidth = screenW,
            screenHeight = screenH
        )
        val rightSide = TelescopeMagnifierGeometry.computePlacement(
            rawFingerX = 850f,
            rawFingerY = 1200f,
            windowSizePx = windowSizePx,
            density = density,
            screenWidth = screenW,
            screenHeight = screenH
        )

        // Lens center is consistently up-left of the finger by identical dx/dy on both sides
        assertEquals(250 - leftSide.lensCenterX, 850 - rightSide.lensCenterX)
        assertEquals(600 - leftSide.lensCenterY, 1200 - rightSide.lensCenterY)
        assertTrue("Lens must sit to the left of finger", leftSide.lensCenterX < 250)
        assertTrue("Lens must sit above finger", leftSide.lensCenterY < 600)

        // Near far left/top bezel, lens center remains clamped on-screen without flipping right
        val edgePlacement = TelescopeMagnifierGeometry.computePlacement(
            rawFingerX = 20f,
            rawFingerY = 20f,
            windowSizePx = windowSizePx,
            density = density,
            screenWidth = screenW,
            screenHeight = screenH
        )
        assertTrue(edgePlacement.lensCenterX in 0..screenW)
        assertTrue(edgePlacement.lensCenterY in 0..screenH)
    }

    @Test
    fun telescopeFocusTracker_350msDwellAndNoLockRelease_andEmptyFiltering() {
        val tracker = TelescopeFocusTracker()
        val density = 3f
        val msg = ScreenTextNode("Hello from WhatsApp", Rect(100, 300, 600, 420))
        val blank = ScreenTextNode("   ", Rect(100, 300, 600, 420))
        val sorted = ScreenTextSnapshot.normalizeAndSort(listOf(blank, msg))
        assertEquals(1, sorted.size)
        assertTrue(ScreenTextSnapshot.isDecorativeNonTextClass("android.widget.ImageView"))
        assertFalse(ScreenTextSnapshot.isDecorativeNonTextClass("android.widget.TextView"))

        // Initial move over text starts dwell, does NOT lock immediately
        assertEquals(
            TelescopeFocusTracker.MoveEffect.RESTART_DWELL,
            tracker.onLensMoved(200, 350, density, sorted)
        )
        assertNull(tracker.lockedNode)

        // Small micro-movement within 15dp keeps dwelling without resetting timer
        assertEquals(
            TelescopeFocusTracker.MoveEffect.KEEP_DWELLING,
            tracker.onLensMoved(210, 355, density, sorted)
        )
        assertNull(tracker.lockedNode)

        // Fast move (>15dp) resets dwell cleanly
        assertEquals(
            TelescopeFocusTracker.MoveEffect.RESTART_DWELL,
            tracker.onLensMoved(320, 355, density, sorted)
        )
        assertNull(tracker.lockedNode)

        // After 350ms dwell completes over valid text -> locks node
        val locked = tracker.onDwellElapsed(sorted)
        assertEquals(msg, locked)
        assertEquals(msg, tracker.lockedNode)

        // Moving inside locked node keeps lock intact
        assertEquals(
            TelescopeFocusTracker.MoveEffect.KEEP_LOCKED,
            tracker.onLensMoved(380, 360, density, sorted)
        )

        // Dragging away into empty space clears lock immediately
        assertEquals(
            TelescopeFocusTracker.MoveEffect.RESTART_DWELL,
            tracker.onLensMoved(900, 900, density, sorted)
        )
        assertNull(tracker.lockedNode)
        // Dwelling over empty space produces null lock (clean bubble move on release)
        assertNull(tracker.onDwellElapsed(sorted))
        assertNull(tracker.lockedNode)
    }

    @Test
    fun reorderHistoryStack_andBuiltInModuleIcons_workCleanly() {
        val history = com.princeniithompson.rapcha.ui.screens.ReorderHistoryStack()
        assertFalse(history.canUndo)
        assertFalse(history.canRedo)

        val initial = listOf("grammar", "translate", "reply", "tone")
        val afterFirst = listOf("translate", "grammar", "reply", "tone")
        val afterSecond = listOf("translate", "tone", "grammar", "reply")

        // No-op transition ignored
        history.recordTransition(initial, initial)
        assertFalse(history.canUndo)

        history.recordTransition(initial, afterFirst)
        history.recordTransition(afterFirst, afterSecond)
        assertTrue(history.canUndo)
        assertFalse(history.canRedo)

        val undone1 = history.undo(afterSecond)
        assertEquals(afterFirst, undone1)
        assertTrue(history.canRedo)

        val undone2 = history.undo(afterFirst)
        assertEquals(initial, undone2)
        assertFalse(history.canUndo)

        val redone1 = history.redo(initial)
        assertEquals(afterFirst, redone1)

        // Reconciles cleanly if a new custom item was added before undo
        val withNewItem = afterFirst + "custom_1"
        val undoneWithNew = history.undo(withNewItem)
        assertEquals(initial + "custom_1", undoneWithNew)

        // Direct 2-item pair swap from dragStartOrder:
        // Start: [1: tone, 2: reply, 3: grammar, 4: translate]
        val startOrder = listOf("tone", "reply", "grammar", "translate")
        // Hold "tone" (index 0) and settle on "grammar" (slot index 2):
        // "grammar" moves to where "tone" was (index 0), "tone" is at index 2, "reply" stays untouched!
        val step1 = com.princeniithompson.rapcha.ui.screens.ChipCloudReorderHelper.swapPairFromStartOrder(
            dragStartOrder = startOrder,
            draggedId = "tone",
            targetSlotIndex = 2
        )
        assertEquals(listOf("grammar", "reply", "tone", "translate"), step1)

        // Still holding "tone" without letting go, now settle on "reply" (slot index 1):
        // "grammar" returns back to index 2, and "reply" moves to where "tone" originally was (index 0)!
        val step2 = com.princeniithompson.rapcha.ui.screens.ChipCloudReorderHelper.swapPairFromStartOrder(
            dragStartOrder = startOrder,
            draggedId = "tone",
            targetSlotIndex = 1
        )
        assertEquals(listOf("reply", "tone", "grammar", "translate"), step2)

        // Built-in module icons resolve for every module
        listOf(
            ToneRepository.MODULE_GRAMMAR,
            ToneRepository.MODULE_TRANSLATE,
            ToneRepository.MODULE_REPLY,
            ToneRepository.MODULE_TONE,
            "custom_mod_999"
        ).forEach { modId ->
            val key = com.princeniithompson.rapcha.ui.screens.CuratedIconCatalog.builtInModuleIconKey(modId)
            assertNotNull(
                "Expected vector icon for module $modId (key=$key)",
                com.princeniithompson.rapcha.ui.screens.CuratedIconCatalog.resolveVector(key)
            )
        }
    }

    @get:org.junit.Rule
    val composeRule = createComposeRule()

    @Test
    fun modulesScreen_liveDragSwap_andUndoRedo_movesChipsCleanly() {
        ToneRepository.init(context)
        composeRule.setContent {
            com.princeniithompson.rapcha.ui.screens.ModulesScreen(onOpenMenu = {})
        }
        composeRule.waitForIdle()

        val grammarNode = composeRule.onNodeWithTag("module_chip_builtin_grammar", useUnmergedTree = true)
        val toneNode = composeRule.onNodeWithTag("module_chip_builtin_tone", useUnmergedTree = true)
        val undoNode = composeRule.onNodeWithTag("modules_undo_button", useUnmergedTree = true)
        val redoNode = composeRule.onNodeWithTag("modules_redo_button", useUnmergedTree = true)

        val initialGrammarBounds = grammarNode.fetchSemanticsNode().boundsInRoot
        val initialToneBounds = toneNode.fetchSemanticsNode().boundsInRoot
        assertTrue(initialGrammarBounds.left < initialToneBounds.left)

        // 1. Long-press on Tone and drag to Grammar, hold for 250ms BEFORE lifting finger
        val deltaToGrammar = initialGrammarBounds.center - initialToneBounds.center
        toneNode.performTouchInput {
            down(center)
            advanceEventTime(600L)
            moveBy(deltaToGrammar)
            advanceEventTime(250L)
        }
        composeRule.mainClock.advanceTimeBy(500L)
        composeRule.waitForIdle()

        // While finger is still held down, Grammar must have automatically glided to Tone's starting spot!
        val midDragGrammarBounds = grammarNode.fetchSemanticsNode().boundsInRoot
        val midDragToneBounds = toneNode.fetchSemanticsNode().boundsInRoot
        assertEquals(initialToneBounds.center.x, midDragGrammarBounds.center.x, 3f)
        assertEquals(initialGrammarBounds.center.x, midDragToneBounds.center.x, 3f)

        // 2. Lift finger -> both chips settle cleanly at their swapped slots
        toneNode.performTouchInput {
            up()
        }
        composeRule.mainClock.advanceTimeBy(600L)
        composeRule.waitForIdle()

        val afterDropGrammarBounds = grammarNode.fetchSemanticsNode().boundsInRoot
        val afterDropToneBounds = toneNode.fetchSemanticsNode().boundsInRoot
        assertTrue(afterDropToneBounds.left < afterDropGrammarBounds.left)
        assertEquals(initialGrammarBounds.left, afterDropToneBounds.left, 3f)

        // 3. Click Undo -> chips visually glide back to their original slots
        undoNode.performClick()
        composeRule.mainClock.advanceTimeBy(600L)
        composeRule.waitForIdle()

        val afterUndoGrammarBounds = grammarNode.fetchSemanticsNode().boundsInRoot
        val afterUndoToneBounds = toneNode.fetchSemanticsNode().boundsInRoot
        assertEquals(initialGrammarBounds.left, afterUndoGrammarBounds.left, 1f)
        assertEquals(initialToneBounds.left, afterUndoToneBounds.left, 1f)

        // 4. Click Redo -> chips visually glide back to their swapped slots
        redoNode.performClick()
        composeRule.mainClock.advanceTimeBy(600L)
        composeRule.waitForIdle()

        val afterRedoGrammarBounds = grammarNode.fetchSemanticsNode().boundsInRoot
        val afterRedoToneBounds = toneNode.fetchSemanticsNode().boundsInRoot
        assertEquals(afterDropGrammarBounds.left, afterRedoGrammarBounds.left, 1f)
        assertEquals(afterDropToneBounds.left, afterRedoToneBounds.left, 1f)

        // 5. Single tap on Tone chip -> opens Module Inspector card with Configure button
        toneNode.performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("module_inspector_card", useUnmergedTree = true).fetchSemanticsNode()
        val configureBtn = composeRule.onNodeWithTag("inspector_configure_button", useUnmergedTree = true)
        configureBtn.performClick()
        composeRule.waitForIdle()

        // 6. Now inside Tone Configure screen: single tap on a tone chip opens its inspector card,
        // and tapping it a second time toggles the inspector card closed
        val profChip = composeRule.onNodeWithTag("config_tone_chip_default_professional", useUnmergedTree = true)
        val romChip = composeRule.onNodeWithTag("config_tone_chip_default_romantic", useUnmergedTree = true)
        profChip.performClick()
        composeRule.mainClock.advanceTimeBy(400L)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("config_tone_inspector_card", useUnmergedTree = true).fetchSemanticsNode()

        // Tap Professional again -> toggles inspector card off
        profChip.performClick()
        composeRule.mainClock.advanceTimeBy(400L)
        composeRule.waitForIdle()

        // 7. Drag Romantic onto Professional inside Tone Configure screen and verify Undo/Redo
        val initProf = profChip.fetchSemanticsNode().boundsInRoot
        val initRom = romChip.fetchSemanticsNode().boundsInRoot
        assertTrue(
            "Expected Professional to precede Romantic in flow order",
            initProf.top < initRom.top || initProf.left < initRom.left
        )
        romChip.performTouchInput {
            down(center)
            advanceEventTime(600L)
            moveBy(initProf.center - initRom.center)
            advanceEventTime(250L)
        }
        composeRule.mainClock.advanceTimeBy(500L)
        composeRule.waitForIdle()
        romChip.performTouchInput { up() }
        composeRule.mainClock.advanceTimeBy(600L)
        composeRule.waitForIdle()

        val swappedProf = profChip.fetchSemanticsNode().boundsInRoot
        val swappedRom = romChip.fetchSemanticsNode().boundsInRoot
        assertTrue(
            "Expected Romantic to precede Professional after swap",
            swappedRom.top < swappedProf.top || swappedRom.left < swappedProf.left
        )
        assertEquals(initProf.top, swappedRom.top, 2f)

        composeRule.onNodeWithTag("config_tone_undo_button", useUnmergedTree = true).performClick()
        composeRule.mainClock.advanceTimeBy(600L)
        composeRule.waitForIdle()
        assertEquals(initProf.top, profChip.fetchSemanticsNode().boundsInRoot.top, 1f)
        assertEquals(initProf.left, profChip.fetchSemanticsNode().boundsInRoot.left, 1f)

        composeRule.onNodeWithTag("config_tone_redo_button", useUnmergedTree = true).performClick()
        composeRule.mainClock.advanceTimeBy(600L)
        composeRule.waitForIdle()
        assertEquals(swappedProf.top, profChip.fetchSemanticsNode().boundsInRoot.top, 1f)
        assertEquals(swappedProf.left, profChip.fetchSemanticsNode().boundsInRoot.left, 1f)
    }
}
