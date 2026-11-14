package app.hushmessenger.patches.controls

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

/** The @ suggestion list's Meta AI, imagine and community AI rows. */
internal const val AI_MENTIONS = "ai_mentions"

/** The AI command list the same suggestions read for the open chat. */
internal const val AI_COMMANDS = "ai_commands"

internal const val MENTION_ROW_SPEC = "messaging.mentions.composer.MentionRowSupplierSpec"

/** Sections Hide Meta AI skips, by the plugin name each one traces under. */
internal val AI_MENTION_PLUGINS = setOf(
    "messaging.aibot.core.mentionai.MentionAiRowSupplier",
    "messaging.aibot.core.imagine.ImagineRowSupplier",
    "messaging.communitymessaging.ai.mentionrowsupplier.CommunityAiRowSupplier",
)

internal const val AI_PAGE_COMMAND = "Lcom/facebook/messaging/aibot/page/customcommand/AiPageCustomCommand;"
internal const val MENTION_LIST_BUILDER = "Lcom/google/common/collect/ImmutableList\$Builder;"
internal const val AI_MENTION_HELPER = "$SETTINGS->hideMetaAiRow(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"
private const val SECTION_COUNTER = "Ljava/util/concurrent/atomic/AtomicInteger;->getAndIncrement()I"

private fun mentionsChanged(detail: String): Nothing =
    throw PatchException("Messenger controls: the @ suggestion Meta AI rows moved ($detail)")

private fun commandsChanged(detail: String): Nothing =
    throw PatchException("Messenger controls: the AI command list moved ($detail)")

private fun Instruction.string() = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string

private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference?.toString()

/**
 * A row dispatcher, `(query, ImmutableList.Builder)V`. Messenger runs each mention plugin inline here under the
 * MentionRowSupplierSpec trace, and the ones that matter name a Meta AI section.
 */
internal fun Method.isAiMentionDispatcher(): Boolean {
    if (returnType != "V" || AccessFlags.STATIC.isSet(accessFlags) ||
        parameterTypes.map { it.toString() }.getOrNull(1) != MENTION_LIST_BUILDER || parameterTypes.size != 2) return false
    val strings = implementation?.instructions?.mapNotNull { it.string() }?.toSet() ?: return false
    return MENTION_ROW_SPEC in strings && strings.any { it in AI_MENTION_PLUGINS }
}

internal fun findAiMentions(classes: Iterable<ClassDef>): List<Method> =
    classes.flatMap { it.methods }.filter { it.isAiMentionDispatcher() }

/**
 * Index of each Meta AI section's skip. A section starts where its plugin trace begins:
 *
 *     iget-object vS, this, <cache>:Object      <- the state Messenger computed (or cached) for this plugin
 *     sget-object vD, <sentinel>                 only in the community section; the others load vD earlier
 *     move-object/from16 vX, vY                  zero or more, touching neither vS nor vD
 *     if-eq vS, vD, :next_section              <- the skip; vD holds the disabled marker
 *     invoke-virtual {vN}, AtomicInteger->getAndIncrement()I
 *     const-string vX, "messaging.<plugin>"
 *
 * vD has to be the marker, loaded from the profile's sentinel, and the cache has to be stored from a register the
 * sentinel was loaded into when the check fails. Nothing else may jump to the skip, or a path could miss the extension.
 */
internal fun Method.aiMentionGates(): List<Int> {
    val code = implementation?.instructions?.toList() ?: mentionsChanged("no code")
    val sections = code.indices.filter { code[it].string() in AI_MENTION_PLUGINS && code.getOrNull(it - 1)?.reference() == SECTION_COUNTER }
    if (sections.isEmpty()) mentionsChanged("no Meta AI section")
    if (sections.map { code[it].string() }.toSet().size != sections.size) mentionsChanged("a section repeats")
    val jumps = jumpTargets()
    val sentinels = code.filter { it.opcode == Opcode.SGET_OBJECT && it.reference() == activeProfile.pluginSentinel }
        .map { (it as OneRegisterInstruction).registerA }.toSet()
    return sections.map { at ->
        val skip = code.getOrNull(at - 2) as? TwoRegisterInstruction
        if (skip == null || skip.opcode != Opcode.IF_EQ) mentionsChanged("skip check")
        val state = skip.registerA
        val disabled = skip.registerB
        var back = at - 3
        while (code.getOrNull(back)?.opcode in MOVE_OBJECTS && (code[back] as TwoRegisterInstruction).registerA !in setOf(state, disabled)) back--
        val localMarker = code.getOrNull(back)?.let {
            it.opcode == Opcode.SGET_OBJECT && it.reference() == activeProfile.pluginSentinel && (it as OneRegisterInstruction).registerA == disabled
        } == true
        if (localMarker) back--
        val read = code.getOrNull(back)
        val field = (read as? ReferenceInstruction)?.reference as? FieldReference
        if (read?.opcode != Opcode.IGET_OBJECT || field == null || field.definingClass != definingClass || field.type != "Ljava/lang/Object;") {
            mentionsChanged("state read")
        }
        if ((read as TwoRegisterInstruction).registerA != state) mentionsChanged("skip check")
        if (code[at - 1].opcode !in setOf(Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE)) mentionsChanged("section counter")
        val stored = code.any {
            it.opcode == Opcode.IPUT_OBJECT && it.reference() == field.toString() && (it as TwoRegisterInstruction).registerA in sentinels
        }
        if (!(localMarker || disabled in sentinels) || !stored) mentionsChanged("disabled marker")
        if ((at - 2) in jumps) mentionsChanged("a jump past the state read")
        // The helper is a plain invoke, which reaches v15 at most.
        if (state > 15 || disabled > 15) mentionsChanged("register out of range")
        at - 2
    }
}

private val MOVE_OBJECTS = setOf(Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16)

internal fun MutableMethod.validateAiMentions(): List<Int> {
    if (!isAiMentionDispatcher()) mentionsChanged("unexpected dispatcher ${hookId()}")
    return aiMentionGates()
}

/**
 * Passes each Meta AI section's state through the extension right before its skip compares it. With the switch on it
 * reads as the disabled marker, so the section's own check skips the row. People, @everyone and the other rows don't
 * change.
 */
internal fun MutableMethod.injectAiMentions() {
    val gates = validateAiMentions()
    val code = implementation!!.instructions.toList()
    for (skip in gates.reversed()) {
        val state = (code[skip] as TwoRegisterInstruction).registerA
        val disabled = (code[skip] as TwoRegisterInstruction).registerB
        addInstructions(skip, """
            invoke-static {v$state, v$disabled}, $AI_MENTION_HELPER
            move-result-object v$state
        """.trimIndent())
    }
}

/**
 * The open chat's AI commands, a static `(controller, ThreadKey)List` that turns each AiPageCustomCommand into an @ or /
 * suggestion. Null is its own answer when the chat has none, and both callers handle it.
 */
internal fun Method.isAiCommandList(): Boolean {
    if (!AccessFlags.STATIC.isSet(accessFlags) || returnType != "Ljava/util/List;" ||
        parameterTypes.map { it.toString() } != listOf(definingClass, THREAD_KEY_TYPE)) return false
    return implementation?.instructions?.any {
        it.opcode == Opcode.CHECK_CAST && ((it as ReferenceInstruction).reference as? TypeReference)?.type == AI_PAGE_COMMAND
    } == true
}

/** The one command list, or none when more than one method looks like it. */
internal fun findAiCommands(classes: Iterable<ClassDef>): List<Method> =
    classes.flatMap { it.methods }.filter { it.isAiCommandList() }.let { if (it.size == 1) it else emptyList() }

internal fun MutableMethod.validateAiCommands() {
    if (!isAiCommandList()) commandsChanged("unexpected method ${hookId()}")
    validateSwitch()
}

/** With the switch on the chat has no AI commands, the same answer Messenger gives a chat without any. */
internal fun MutableMethod.injectAiCommands() {
    validateAiCommands()
    injectSwitch("$SETTINGS->hideMetaAi()Z", "0x0")
}
