package app.hushmessenger.patches.controls

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

internal const val SYNTHETIC_TAB_CONFIG = "LX/1nk;->A02(Landroid/content/Context;$FB_USER_SESSION)LX/1oC;"
private const val TABS = "LX/1ng;"
private const val NEW_CONFIG = "new-instance v1, LX/1oC;"
private const val CONFIG_INIT = "invoke-direct {v1, v4, v0}, LX/1oC;-><init>(Ljava/util/List;Ljava/util/List;)V"
private const val ADD = "invoke-virtual {v4, v0}, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z"

/**
 * The tab-list builder as 584 writes it: each tab is a constant of one enum, Search goes in only on some paths, and both
 * paths meet before the configuration is built from the finished list.
 */
private fun builder(
    id: String = SYNTHETIC_TAB_CONFIG,
    ai: String = "sget-object v0, $TABS->AI:$TABS",
    muse: String = "sget-object v0, $TABS->MUSE:$TABS",
    meet: String = ":config\nsget-object v0, LX/0Rk;->A00:LX/0Rk;",
    start: String = NEW_CONFIG,
    build: String = CONFIG_INIT,
) = fixtureMethod(id, """
    const-string v0, "$TAB_CONFIG_TRACE"
    new-instance v4, Ljava/util/ArrayList;
    invoke-direct {v4}, Ljava/util/ArrayList;-><init>()V
    sget-object v0, $TABS->PEOPLE:$TABS
    $ADD
    sget-object v0, $TABS->JEWEL:$TABS
    $ADD
    $ai
    $ADD
    $muse
    $ADD
    if-eqz p2, :config
    sget-object v0, $TABS->SEARCH:$TABS
    $ADD
    $meet
    $start
    $build
    return-object v1
""".trimIndent(), registers = 14)

internal fun tabConfigFixture() = listOf(fixtureClass("LX/1nk;", listOf(builder())))

private fun Method.code() = implementation!!.instructions.toList()

/** Two instructions go in right before the configuration starts: the list through the helper, back into its register. */
private fun assertTabHook(before: List<Instruction>, after: List<Instruction>, site: Int, list: Int, label: String) {
    assertEquals(before.size + 2, after.size, label)
    assertEquals(before, after.filterIndexed { i, _ -> i != site && i != site + 1 }, label)
    val helper = after[site] as FiveRegisterInstruction
    assertEquals(Opcode.INVOKE_STATIC, helper.opcode, label)
    assertEquals(VISIBLE_TABS_HELPER, (helper as ReferenceInstruction).reference.toString(), label)
    assertEquals(listOf(1, list), listOf(helper.registerCount, helper.registerC), label)
    assertEquals(Opcode.MOVE_RESULT_OBJECT, after[site + 1].opcode, label)
    assertEquals(list, (after[site + 1] as OneRegisterInstruction).registerA, label)
    assertEquals(Opcode.NEW_INSTANCE, after[site + 2].opcode, label)
    assertEquals(list, (after[site + 3] as FiveRegisterInstruction).registerD, label)
}

class TabConfigTest {
    @AfterTest fun reset() { activeProfile = SYNTHETIC_PROFILE }

    private fun found(vararg methods: MutableMethod) =
        mirroredControls(methods.groupBy { it.definingClass }.map { (type, group) -> fixtureClass(type, group) })

    @Test fun theFinishedTabListGoesThroughTheExtensionRightBeforeTheBarTakesIt() {
        val method = builder()
        val found = found(method)
        assertEquals(listOf(SYNTHETIC_TAB_CONFIG), found.getValue(TAB_CONFIG).map { it.hookId() })
        validateControls(found, setOf(TAB_CONFIG))
        val before = method.code()
        val site = method.tabConfigSite()!!
        assertEquals(Opcode.NEW_INSTANCE, before[site].opcode)
        injectControl(TAB_PEOPLE, mapOf(TAB_CONFIG to listOf(method)))
        assertTabHook(before, method.code(), site, 4, "builder")
        // Both paths still meet on the stock instruction ahead of the call, so Search or not, the list goes through it.
        val meet = method.code().indexOfFirst { it.opcode == Opcode.IF_EQZ }
        assertEquals(site - 1, method.code().branchTarget(meet))
    }

    @Test fun peopleNotificationsAndMetaAiShareTheOneCall() {
        assertTrue(listOf(TAB_PEOPLE, TAB_NOTIFICATIONS, "meta_ai").all { TAB_CONFIG in controlHooks.getValue(it) })
        val method = builder()
        val before = method.code()
        val site = method.tabConfigSite()!!
        injectControl(TAB_PEOPLE, mapOf(TAB_CONFIG to listOf(method)))
        val once = method.code()
        // The second and third control check the already hooked builder, then leave it as it is.
        injectControl(TAB_NOTIFICATIONS, mapOf(TAB_CONFIG to listOf(method)))
        injectControl("meta_ai", mapOf(TAB_CONFIG to listOf(method)))
        assertEquals(once, method.code())
        assertTabHook(before, method.code(), site, 4, "shared")
    }

    @Test fun onlyTheTracedBuilderCounts() {
        // Without its trace, or with other parameters, the same code isn't the builder.
        assertTrue(found(fixtureMethod(SYNTHETIC_TAB_CONFIG, "const/4 v0, 0x0\nreturn-object v0")).getValue(TAB_CONFIG).isEmpty())
        assertTrue(found(builder(id = "LX/1nk;->A02(Landroid/content/Context;Ljava/lang/Object;)LX/1oC;")).getValue(TAB_CONFIG).isEmpty())
        // Two builders make the set differ from the build's, so the controls stop.
        val twice = found(builder(), builder(id = "LX/1nl;->A02(Landroid/content/Context;$FB_USER_SESSION)LX/1oC;"))
        assertEquals(2, twice.getValue(TAB_CONFIG).size)
        assertFailsWith<PatchException> { validateControls(twice, setOf(TAB_CONFIG)) }
    }

    @Test fun aShapeThatMovedStopsBeforeAnyEdit() {
        val cases = mapOf(
            "no Meta AI tab" to builder(ai = "sget-object v0, $TABS->SEARCH:$TABS"),
            // Hide Meta AI maps MUSE by name, so a builder that no longer reads it would leave that tab's index off.
            "no MUSE tab" to builder(muse = "sget-object v0, $TABS->SEARCH:$TABS"),
            "MUSE from another enum" to builder(muse = "sget-object v0, LX/1nh;->MUSE:LX/1nh;"),
            "tabs from two enums" to builder(ai = "sget-object v0, LX/1nh;->AI:LX/1nh;"),
            "a second configuration" to builder(build = "$CONFIG_INIT\n$NEW_CONFIG\n$CONFIG_INIT"),
            "configuration takes one list" to builder(build = "invoke-direct {v1, v4}, LX/1oC;-><init>(Ljava/util/List;)V"),
            "configuration built by a range call" to
                builder(build = "invoke-direct/range {v1 .. v3}, LX/1oC;-><init>(Ljava/util/List;Ljava/util/List;)V"),
            "something between new-instance and the call" to builder(start = "$NEW_CONFIG\nconst/4 v2, 0x0"),
            "new-instance of another type" to builder(start = "new-instance v1, LX/1oD;"),
            "new-instance into another register" to builder(start = "new-instance v2, LX/1oC;"),
            "a jump lands on the configuration" to builder(meet = "sget-object v0, LX/0Rk;->A00:LX/0Rk;\n:config"),
        )
        for ((case, bad) in cases) {
            val before = bad.code()
            // CompatReport has to turn the same shape down: the mirror fails this call if it still reports the hook.
            assertEquals(1, found(bad).getValue(TAB_CONFIG).size, case)
            val failure = assertFailsWith<PatchException>(case) { injectControl(TAB_PEOPLE, mapOf(TAB_CONFIG to listOf(bad))) }
            assertContains(failure.message.orEmpty(), "tab list no longer matches", message = case)
            assertEquals(before, bad.code(), case)
        }
    }

    @Test fun everySupportedBuildHooksItsTabListBuilder() {
        val root = System.getenv("HUSH_NATIVE_FIXTURES")
        assumeTrue(root != null, "Set HUSH_NATIVE_FIXTURES to the exact stock fixture directory")
        val apks = Files.list(Path.of(root!!)).use { it.filter { p -> p.toString().endsWith(".apk") }.sorted().toList() }
        assertEquals(controlProfiles.size, apks.size)
        for (apk in apks) {
            val code = apk.fileName.toString().substringBeforeLast(".apk").substringAfterLast('-')
            activeProfile = controlProfileFor(code)
            val dex = DexFileFactory.loadDexContainer(apk.toFile(), Opcodes.forApi(35))
            val classes = dex.dexEntryNames.flatMap { dex.getEntry(it)!!.dexFile.classes }
            val builders = findTabConfig(classes)
            assertEquals(activeProfile.hooks.getValue(TAB_CONFIG), builders.map { it.hookId() }.toSet(), code)
            validateControls(mapOf(TAB_CONFIG to builders), setOf(TAB_CONFIG))
            val method = MutableMethod(builders.single())
            val site = method.tabConfigSite()!!
            val list = (method.code()[site + 1] as FiveRegisterInstruction).registerD
            val before = method.code()
            injectControl(TAB_PEOPLE, mapOf(TAB_CONFIG to listOf(method)))
            injectControl(TAB_NOTIFICATIONS, mapOf(TAB_CONFIG to listOf(method)))
            assertTabHook(before, method.code(), site, list, code)
        }
    }
}
