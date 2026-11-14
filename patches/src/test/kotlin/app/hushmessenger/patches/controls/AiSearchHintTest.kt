package app.hushmessenger.patches.controls

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal const val SYNTHETIC_SEARCH_BAR = "LX/28Z;->A00(LX/1SO;LX/1vn;Lcom/facebook/mig/scheme/interfaces/MigColorScheme;Z)LX/1GG;"

private const val CHECK = "invoke-interface {v4, v9}, LX/Ik1;->BqJ(Lcom/facebook/auth/usersession/FbUserSession;)Z"

/** The search bar builder as Messenger writes it: the Meta AI check, then the plain hint unless the check passed. */
private fun searchBar(check: String = CHECK, branch: String = "if-eqz v8, :pick", jump: String = "", id: String = SYNTHETIC_SEARCH_BAR) =
    fixtureMethod(id, """
        $jump
        $check
        move-result v8
        :after_check
        invoke-virtual {v11}, LX/1SO;->A03()Landroid/content/res/Resources;
        move-result-object v4
        const v1, 0x7f1300a8
        $branch
        const v1, 0x7f1301b2
        :pick
        invoke-virtual {v4, v1}, Landroid/content/res/Resources;->getString(I)Ljava/lang/String;
        move-result-object v2
        const-string v1, "$SEARCH_BAR_TAG"
        const/4 v0, 0x0
        return-object v0
    """.trimIndent(), registers = 20)

/** The builder for the catalog-wide discovery fixture. */
internal fun searchBarFixture() = listOf(fixtureClass("LX/28Z;", listOf(searchBar())))

internal const val SYNTHETIC_SEARCH_SCREEN = "LX/HqZ;->A00(Lcom/facebook/auth/usersession/FbUserSession;LX/HqZ;)V"

/**
 * The search screen header as 584 writes it: the bar's Meta AI check, two unrelated lines and a style test, then the
 * Meta AI hint unless the check failed, read through a static (Resources, id) helper.
 */
private fun searchScreen(
    check: String = CHECK,
    between: String = "",
    branch: String = "if-nez v8, :read",
    metaAi: String = "0x7f1301b2",
    jump: String = "",
    hint: String = "v6",
    id: String = SYNTHETIC_SEARCH_SCREEN,
) = fixtureMethod(id, """
        $jump
        $check
        move-result v8
        :after_check
        invoke-static {v2}, LX/32s;->A0k(Ljava/lang/Object;)V
        sget-object v0, LX/Gyv;->A04:LX/Gyv;
        $between
        if-eq v2, v0, :plain
        const $hint, $metaAi
        $branch
        :plain
        const $hint, 0x7f1300a8
        :read
        invoke-static {v7, $hint}, LX/32s;->A0J(Landroid/content/res/Resources;I)Ljava/lang/String;
        move-result-object v3
        return-void
    """.trimIndent(), registers = 20, flags = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value)

/** The search screen for the catalog-wide discovery fixture, found only next to [searchBarFixture]. */
internal fun searchScreenFixture() = listOf(fixtureClass("LX/HqZ;", listOf(searchScreen())))

private fun Method.code() = implementation!!.instructions.toList()

/** The helper pair sits right after the check's result, and nothing stock moves or changes. */
private fun assertHintHook(before: List<Instruction>, after: List<Instruction>, result: Int, label: String) {
    val held = (before[result] as OneRegisterInstruction).registerA
    assertEquals(before.size + 2, after.size, label)
    assertStockKept(before, after.filterIndexed { i, _ -> i != result + 1 && i != result + 2 }, label)
    val helper = after[result + 1] as RegisterRangeInstruction
    assertEquals(Opcode.INVOKE_STATIC_RANGE, helper.opcode, label)
    assertEquals(AI_SEARCH_HINT_HELPER, (helper as ReferenceInstruction).reference.toString(), label)
    assertEquals(listOf(1, held), listOf(helper.registerCount, helper.startRegister), label)
    assertEquals(Opcode.MOVE_RESULT, after[result + 2].opcode, label)
    assertEquals(held, (after[result + 2] as OneRegisterInstruction).registerA, label)
}

class AiSearchHintTest {
    @AfterTest fun reset() { activeProfile = SYNTHETIC_PROFILE }

    @Test fun theMetaAiCheckGoesThroughTheExtensionBeforeTheHintIsPicked() {
        val method = searchBar()
        val found = mirroredControls(searchBarFixture())
        assertEquals(listOf(SYNTHETIC_SEARCH_BAR), found.getValue(AI_SEARCH_HINT).map { it.hookId() })
        validateControls(found, setOf(AI_SEARCH_HINT))
        val before = method.code()
        val result = method.searchHintSite()!!
        // An invoke-virtual check (583) is read the same way.
        assertEquals(result, searchBar(check = CHECK.replace("invoke-interface", "invoke-virtual")).searchHintSite())
        injectControl(AI_SEARCH_HINT, mapOf(AI_SEARCH_HINT to listOf(method)))
        assertHintHook(before, method.code(), result, "builder")
        // Two builders that look alike are ambiguous, so neither is hooked.
        val twin = fixtureClass("LX/28Y;", listOf(searchBar(id = SYNTHETIC_SEARCH_BAR.replace("LX/28Z;", "LX/28Y;"))))
        assertTrue(findAiSearchHint(searchBarFixture() + twin).isEmpty())
    }

    @Test fun aShapeThatMovedIsntFoundOrStopsBeforeAnyEdit() {
        // Discovery skips a builder whose hint isn't picked by a session check right before getString.
        val moved = mapOf(
            "check takes no session" to searchBar(check = "invoke-interface {v4}, LX/Ik1;->BqJ()Z"),
            "check is a static call" to searchBar(check = CHECK.replace("invoke-interface", "invoke-static")),
            "check answers a number" to searchBar(check = CHECK.replace(")Z", ")I")),
            "plain hint on not-equal" to searchBar(branch = "if-nez v8, :pick"),
            "branch tests another register" to searchBar(branch = "if-eqz v2, :pick"),
        )
        for ((case, method) in moved) assertNull(method.searchHintSite(), case)
        // A jump straight past the check's result would skip the extension.
        val jumped = searchBar(jump = "if-eqz v3, :after_check")
        val before = jumped.code()
        val failure = assertFailsWith<PatchException> { injectControl(AI_SEARCH_HINT, mapOf(AI_SEARCH_HINT to listOf(jumped))) }
        assertContains(failure.message.orEmpty(), "inbox search hint moved")
        assertEquals(before, jumped.code())
    }

    @Test fun theSearchScreenAsksTheSameExtensionAsTheBar() {
        val method = searchScreen()
        val found = mirroredControls(searchBarFixture() + searchScreenFixture())
        assertEquals(listOf(SYNTHETIC_SEARCH_SCREEN), found.getValue(AI_SEARCH_SCREEN_HINT).map { it.hookId() })
        validateControls(found, setOf(AI_SEARCH_HINT, AI_SEARCH_SCREEN_HINT))
        val before = method.code()
        val result = method.searchScreenHintSite()!!
        assertEquals(result, searchScreen(check = CHECK.replace("invoke-interface", "invoke-virtual")).searchScreenHintSite())
        injectControl(AI_SEARCH_SCREEN_HINT, mapOf(AI_SEARCH_SCREEN_HINT to listOf(method)))
        assertHintHook(before, method.code(), result, "screen")
        // The screen is only trusted with the bar's own check and ids, so it needs the bar, and two screens are ambiguous.
        assertTrue(findAiSearchScreenHint(searchScreenFixture()).isEmpty())
        val twin = fixtureClass("LX/HqY;", listOf(searchScreen(id = SYNTHETIC_SEARCH_SCREEN.replace("LX/HqZ;", "LX/HqY;"))))
        assertTrue(findAiSearchScreenHint(searchBarFixture() + searchScreenFixture() + twin).isEmpty())
    }

    @Test fun aSearchScreenThatMovedIsntFoundOrStopsBeforeAnyEdit() {
        val moved = mapOf(
            "check takes no session" to searchScreen(check = "invoke-interface {v4}, LX/Ik1;->BqJ()Z"),
            "check is a static call" to searchScreen(check = CHECK.replace("invoke-interface", "invoke-static")),
            "Meta AI hint on false" to searchScreen(branch = "if-eqz v8, :read"),
            "branch tests another register" to searchScreen(branch = "if-nez v2, :read"),
            "answer overwritten before the pick" to searchScreen(between = "const/4 v8, 0x0"),
            "hint goes into the check's register" to searchScreen(hint = "v8"),
        )
        for ((case, method) in moved) assertNull(method.searchScreenHintSite(), case)
        // Same shape, but not the bar's check or not the bar's ids: some other screen's hint, left alone.
        val others = mapOf(
            "another check" to searchScreen(check = CHECK.replace("BqJ", "BqK")),
            "another Meta AI id" to searchScreen(metaAi = "0x7f1301b3"),
        )
        for ((case, method) in others) {
            assertTrue(method.searchScreenHintSite() != null, case)
            assertTrue(findAiSearchScreenHint(searchBarFixture() + fixtureClass("LX/HqZ;", listOf(method))).isEmpty(), case)
        }
        // A jump straight past the check's result would skip the extension.
        val jumped = searchScreen(jump = "if-eqz v3, :after_check")
        val before = jumped.code()
        val failure = assertFailsWith<PatchException> { injectControl(AI_SEARCH_SCREEN_HINT, mapOf(AI_SEARCH_SCREEN_HINT to listOf(jumped))) }
        assertContains(failure.message.orEmpty(), "search screen's hint moved")
        assertEquals(before, jumped.code())
    }

    @Test fun everySupportedBuildHooksItsOneSearchScreen() {
        val root = System.getenv("HUSH_NATIVE_FIXTURES")
        assumeTrue(root != null, "Set HUSH_NATIVE_FIXTURES to the exact stock fixture directory")
        val apks = Files.list(Path.of(root!!)).use { it.filter { p -> p.toString().endsWith(".apk") }.sorted().toList() }
        assertEquals(controlProfiles.size, apks.size)
        for (apk in apks) {
            val code = apk.fileName.toString().substringBeforeLast(".apk").substringAfterLast('-')
            activeProfile = controlProfileFor(code)
            val dex = DexFileFactory.loadDexContainer(apk.toFile(), Opcodes.forApi(35))
            val screens = findAiSearchScreenHint(dex.dexEntryNames.flatMap { dex.getEntry(it)!!.dexFile.classes })
            assertEquals(activeProfile.hooks.getValue(AI_SEARCH_SCREEN_HINT), screens.map { it.hookId() }.toSet(), code)
            validateControls(mapOf(AI_SEARCH_SCREEN_HINT to screens), setOf(AI_SEARCH_SCREEN_HINT))
            val method = MutableMethod(screens.single())
            val before = method.code()
            val result = method.searchScreenHintSite()!!
            method.injectAiSearchScreenHint()
            assertHintHook(before, method.code(), result, code)
        }
    }

    @Test fun everySupportedBuildHooksItsOneSearchBar() {
        val root = System.getenv("HUSH_NATIVE_FIXTURES")
        assumeTrue(root != null, "Set HUSH_NATIVE_FIXTURES to the exact stock fixture directory")
        val apks = Files.list(Path.of(root!!)).use { it.filter { p -> p.toString().endsWith(".apk") }.sorted().toList() }
        assertEquals(controlProfiles.size, apks.size)
        for (apk in apks) {
            val code = apk.fileName.toString().substringBeforeLast(".apk").substringAfterLast('-')
            activeProfile = controlProfileFor(code)
            val dex = DexFileFactory.loadDexContainer(apk.toFile(), Opcodes.forApi(35))
            val builders = findAiSearchHint(dex.dexEntryNames.flatMap { dex.getEntry(it)!!.dexFile.classes })
            assertEquals(activeProfile.hooks.getValue(AI_SEARCH_HINT), builders.map { it.hookId() }.toSet(), code)
            validateControls(mapOf(AI_SEARCH_HINT to builders), setOf(AI_SEARCH_HINT))
            val method = MutableMethod(builders.single())
            val before = method.code()
            val result = method.searchHintSite()!!
            method.injectAiSearchHint()
            assertHintHook(before, method.code(), result, code)
        }
    }
}
