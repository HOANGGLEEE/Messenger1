package app.hushmessenger.patches.controls

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

internal const val SYNTHETIC_MENTION_AI = "LX/7Ok;->A73(LX/Sxa;$MENTION_LIST_BUILDER)V"
internal const val SYNTHETIC_IMAGINE = "LX/7On;->A73(LX/Sxa;$MENTION_LIST_BUILDER)V"
internal const val SYNTHETIC_COMMUNITY_AI = "LX/7Om;->A73(LX/Sxa;$MENTION_LIST_BUILDER)V"
internal const val SYNTHETIC_AI_COMMANDS = "LX/7Ob;->A06(LX/7Ob;$THREAD_KEY_TYPE)Ljava/util/List;"

private const val SENTINEL = "LX/1dj;->A03:Ljava/lang/Object;"
private const val COUNTER = "invoke-virtual {v7}, Ljava/util/concurrent/atomic/AtomicInteger;->getAndIncrement()I"
private const val ADD_ROW = "$MENTION_LIST_BUILDER->add(Ljava/lang/Object;)$MENTION_LIST_BUILDER"

/**
 * The Meta AI section as Messenger runs it: the marker loads first, a cold cache stores it, and the section's own read and
 * skip come right before its trace counter and plugin name.
 */
private fun mentionAi(
    read: String = "iget-object v0, v2, LX/7Ok;->A00:Ljava/lang/Object;",
    skip: String = "if-eq v0, v3, :done",
    marker: String = "sget-object v3, $SENTINEL",
    store: String = "iput-object v3, v2, LX/7Ok;->A00:Ljava/lang/Object;",
    jump: String = "",
) = fixtureMethod(SYNTHETIC_MENTION_AI, """
    invoke-static {}, LX/17Q;->A17()Ljava/util/concurrent/atomic/AtomicInteger;
    move-result-object v7
    move-object v2, p0
    const-string v4, "$MENTION_ROW_SPEC"
    $marker
    $jump
    iget-object v0, v2, LX/7Ok;->A00:Ljava/lang/Object;
    if-nez v0, :read
    $store
    :read
    $read
    :skip
    $skip
    $COUNTER
    const-string v0, "messaging.aibot.core.mentionai.MentionAiRowSupplier"
    const-string v1, "@Meta AI"
    invoke-virtual {p2, v1}, $ADD_ROW
    :done
    return-void
""".trimIndent(), registers = 12)

/** A dispatcher whose @everyone section runs before the imagine one, with the marker in another register. */
private fun imagine() = fixtureMethod(SYNTHETIC_IMAGINE, """
    invoke-static {}, LX/17Q;->A17()Ljava/util/concurrent/atomic/AtomicInteger;
    move-result-object v7
    move-object v0, p0
    const-string v5, "$MENTION_ROW_SPEC"
    sget-object v2, $SENTINEL
    iget-object v4, v0, LX/7On;->A02:Ljava/lang/Object;
    if-eq v4, v2, :imagine
    $COUNTER
    const-string v5, "messaging.mentions.globalrows.everyone.MentionEveryoneRowSupplier"
    const-string v6, "@everyone"
    invoke-virtual {p2, v6}, $ADD_ROW
    :imagine
    iget-object v4, v0, LX/7On;->A01:Ljava/lang/Object;
    if-nez v4, :read
    iput-object v2, v0, LX/7On;->A01:Ljava/lang/Object;
    :read
    iget-object v4, v0, LX/7On;->A01:Ljava/lang/Object;
    if-eq v4, v2, :done
    $COUNTER
    const-string v5, "messaging.aibot.core.imagine.ImagineRowSupplier"
    const-string v6, "imagine"
    invoke-virtual {p2, v6}, $ADD_ROW
    :done
    return-void
""".trimIndent(), registers = 12)

/**
 * The community chat dispatcher as Messenger runs it: its section reads the cache, loads the marker right there, moves two
 * unrelated registers and only then compares.
 */
private fun communityAi(
    marker: String = "sget-object v4, $SENTINEL",
    move: String = "move-object/from16 v19, p1",
) = fixtureMethod(SYNTHETIC_COMMUNITY_AI, """
    invoke-static {}, LX/17Q;->A17()Ljava/util/concurrent/atomic/AtomicInteger;
    move-result-object v7
    move-object/from16 v3, p0
    move-object/from16 v6, p2
    const-string v5, "$MENTION_ROW_SPEC"
    iget-object v0, v3, LX/7Om;->A01:Ljava/lang/Object;
    if-nez v0, :read
    sget-object v0, $SENTINEL
    iput-object v0, v3, LX/7Om;->A01:Ljava/lang/Object;
    :read
    iget-object v0, v3, LX/7Om;->A01:Ljava/lang/Object;
    $marker
    $move
    move-object/from16 v24, p2
    if-eq v0, v4, :done
    $COUNTER
    const-string v0, "messaging.communitymessaging.ai.mentionrowsupplier.CommunityAiRowSupplier"
    const-string v1, "@Community AI"
    invoke-virtual {v6, v1}, $ADD_ROW
    :done
    return-void
""".trimIndent(), registers = 28)

/** The chat's AI commands, turned into suggestion rows. Null means the chat has none. */
private fun commandList(id: String = SYNTHETIC_AI_COMMANDS) = fixtureMethod(id, """
    const/4 v0, 0x0
    if-eqz p1, :none
    check-cast v0, $AI_PAGE_COMMAND
    new-instance v1, Ljava/util/ArrayList;
    invoke-direct {v1}, Ljava/util/ArrayList;-><init>()V
    invoke-virtual {v1, v0}, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z
    return-object v1
    :none
    return-object v0
""".trimIndent(), registers = 5, flags = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value)

/** Both dispatchers and the command list, for the catalog-wide discovery fixture. */
internal fun aiMentionsFixture() = listOf(
    fixtureClass("LX/7Ok;", listOf(mentionAi())),
    fixtureClass("LX/7On;", listOf(imagine())),
    fixtureClass("LX/7Om;", listOf(communityAi())),
    fixtureClass("LX/7Ob;", listOf(commandList())),
)

private fun Method.code() = implementation!!.instructions.toList()

/**
 * The stock instructions are the same objects after an insertion, except a goto whose span the insertion pushed
 * past 127 code units, which dexlib2 widens to goto/16.
 */
internal fun assertStockKept(before: List<Instruction>, after: List<Instruction>, label: String) {
    assertEquals(before.size, after.size, label)
    before.zip(after).forEachIndexed { i, (old, new) ->
        val widened = old.opcode == Opcode.GOTO && new.opcode == Opcode.GOTO_16
        assertTrue(old === new || widened, "$label: stock instruction $i went from ${old.opcode.name} to ${new.opcode.name}")
    }
}

/** The helper pair sits right before the section's skip, and nothing stock moves or changes. */
private fun assertGate(before: List<Instruction>, after: List<Instruction>, skip: Int, label: String) {
    val state = (before[skip] as TwoRegisterInstruction).registerA
    val disabled = (before[skip] as TwoRegisterInstruction).registerB
    assertEquals(Opcode.IF_EQ, before[skip].opcode, label)
    assertEquals(before.size + 2, after.size, label)
    assertStockKept(before, after.filterIndexed { i, _ -> i != skip && i != skip + 1 }, label)
    val helper = after[skip] as FiveRegisterInstruction
    assertEquals(Opcode.INVOKE_STATIC, helper.opcode, label)
    assertEquals(AI_MENTION_HELPER, (helper as ReferenceInstruction).reference.toString(), label)
    assertEquals(listOf(2, state, disabled), listOf(helper.registerCount, helper.registerC, helper.registerD), label)
    assertEquals(Opcode.MOVE_RESULT_OBJECT, after[skip + 1].opcode, label)
    assertEquals(state, (after[skip + 1] as OneRegisterInstruction).registerA, label)
    assertSame(before[skip], after[skip + 2], label)
}

class AiMentionsTest {
    @AfterTest fun reset() { activeProfile = SYNTHETIC_PROFILE }

    @Test fun eachMetaAiSectionReadsItsStateThroughTheExtension() {
        val classes = aiMentionsFixture()
        val found = mirroredControls(classes)
        assertEquals(setOf(SYNTHETIC_MENTION_AI, SYNTHETIC_IMAGINE, SYNTHETIC_COMMUNITY_AI), found.getValue(AI_MENTIONS).map { it.hookId() }.toSet())
        validateControls(found, setOf(AI_MENTIONS, AI_COMMANDS))
        for (method in listOf(mentionAi(), imagine(), communityAi())) {
            val before = method.code()
            val skips = method.aiMentionGates()
            assertEquals(1, skips.size, method.hookId())
            injectControl(AI_MENTIONS, mapOf(AI_MENTIONS to listOf(method)))
            // Every stock instruction stays, so the @everyone section before imagine keeps its own read and skip.
            assertGate(before, method.code(), skips.single(), method.hookId())
        }
    }

    @Test fun theCommandListAnswersNoneWhileTheSwitchIsOn() {
        val method = commandList()
        assertEquals(listOf(SYNTHETIC_AI_COMMANDS), findAiCommands(aiMentionsFixture()).map { it.hookId() })
        val before = method.code()
        injectControl(AI_COMMANDS, mapOf(AI_COMMANDS to listOf(method)))
        val after = method.code()
        assertEquals(before, after.drop(5))
        assertEquals("$SETTINGS->hideMetaAi()Z", (after[0] as ReferenceInstruction).reference.toString())
        assertEquals(listOf(Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.CONST_4, Opcode.RETURN_OBJECT), after.subList(1, 5).map { it.opcode })
        // Two lists that look alike are ambiguous, so neither is hooked.
        val twin = fixtureClass("LX/7Oc;", listOf(commandList("LX/7Oc;->A06(LX/7Oc;$THREAD_KEY_TYPE)Ljava/util/List;")))
        assertTrue(findAiCommands(aiMentionsFixture() + twin).isEmpty())
    }

    @Test fun aShapeThatMovedStopsBeforeAnyEdit() {
        val shapes = mapOf(
            "state read from another class" to mentionAi(read = "iget-object v0, v2, LX/Zz1;->A00:Ljava/lang/Object;"),
            "skip on not-equal" to mentionAi(skip = "if-ne v0, v3, :done"),
            "skip compares another register" to mentionAi(skip = "if-eq v1, v3, :done"),
            "marker isn't the sentinel" to mentionAi(marker = "sget-object v3, LX/1dj;->A02:Ljava/lang/Object;"),
            "marker never reaches the cache" to mentionAi(store = "iput-object v1, v2, LX/7Ok;->A00:Ljava/lang/Object;"),
            "a jump lands on the skip" to mentionAi(jump = "if-eqz v1, :skip"),
            "community marker in another register" to communityAi(marker = "sget-object v5, $SENTINEL"),
            "community move overwrites the state" to communityAi(move = "move-object/from16 v0, p1"),
            "community move overwrites the marker" to communityAi(move = "move-object/from16 v4, p1"),
        )
        for ((case, bad) in shapes) {
            val good = imagine()
            val before = good.code()
            val failure = assertFailsWith<PatchException>(case) {
                injectControl(AI_MENTIONS, mapOf(AI_MENTIONS to listOf(good, bad)))
            }
            assertContains(failure.message.orEmpty(), "@ suggestion Meta AI rows moved", message = case)
            assertEquals(before, good.code(), case)
        }
    }

    @Test fun everySupportedBuildHooksItsMetaAiSectionsAndCommandList() {
        val root = System.getenv("HUSH_NATIVE_FIXTURES")
        assumeTrue(root != null, "Set HUSH_NATIVE_FIXTURES to the exact stock fixture directory")
        val apks = Files.list(Path.of(root!!)).use { it.filter { p -> p.toString().endsWith(".apk") }.sorted().toList() }
        assertEquals(controlProfiles.size, apks.size)
        for (apk in apks) {
            val code = apk.fileName.toString().substringBeforeLast(".apk").substringAfterLast('-')
            activeProfile = controlProfileFor(code)
            val dex = DexFileFactory.loadDexContainer(apk.toFile(), Opcodes.forApi(35))
            val classes = dex.dexEntryNames.flatMap { dex.getEntry(it)!!.dexFile.classes }
            val dispatchers = findAiMentions(classes)
            val commands = findAiCommands(classes)
            assertEquals(activeProfile.hooks.getValue(AI_MENTIONS), dispatchers.map { it.hookId() }.toSet(), code)
            assertEquals(activeProfile.hooks.getValue(AI_COMMANDS), commands.map { it.hookId() }.toSet(), code)
            validateControls(mapOf(AI_MENTIONS to dispatchers, AI_COMMANDS to commands), setOf(AI_MENTIONS, AI_COMMANDS))
            for (dispatcher in dispatchers) {
                val method = MutableMethod(dispatcher)
                val before = method.code()
                val skips = method.aiMentionGates()
                assertEquals(1, skips.size, code)
                method.injectAiMentions()
                assertGate(before, method.code(), skips.single(), code)
            }
            MutableMethod(commands.single()).injectAiCommands()
        }
    }
}
