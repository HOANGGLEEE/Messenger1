package app.hushmessenger.patches.controls

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val TYPEFACE = "Landroid/graphics/Typeface;"
private const val FILE = "Ljava/io/File;"
private const val CHAR_SEQUENCE = "Ljava/lang/CharSequence;"
internal const val EMOJI_FONT_CALL = "$SETTINGS->messengerEmojiFont($FILE)V"
internal const val EMOJI_TYPEFACE_CALL = "$SETTINGS->systemEmojiTypeface($TYPEFACE)$TYPEFACE"
internal const val CHAT_LIST_SNIPPET_CALL = "$SETTINGS->chatListSnippet($CHAR_SEQUENCE)$CHAR_SEQUENCE"
/** Builds the chat list's last-message snippet; a kept name in every tested build. */
internal const val SNIPPET_MAKER = "Lcom/facebook/messaging/threadlist/plugins/core/threadsnippet/lastmessage/LastMessageSnippetImpl;"
private const val SNIPPET_NAME = "LastMessageThreadSnippet(text="

private fun emojiFontChanged(): Nothing =
    throw PatchException("Messenger controls: Messenger's emoji font no longer loads the way the tested builds do")

/**
 * The constructor of the holder Messenger keeps its downloaded FacebookEmoji.ttf in. The emoji getter reads the holder's
 * Typeface and returns it at once; the final holder has that Typeface, the font's File and one constructor taking both.
 */
internal fun Method.emojiFontHolder(classDefBy: (String) -> ClassDef?): String {
    val code = implementation?.instructions?.toList() ?: emojiFontChanged()
    if (returnType != TYPEFACE || parameterTypes.isNotEmpty() || AccessFlags.STATIC.isSet(accessFlags)) emojiFontChanged()
    val holders = code.indices.mapNotNull { i ->
        val field = (code[i] as? ReferenceInstruction)?.reference as? FieldReference
        val read = code[i] as? TwoRegisterInstruction
        val next = code.getOrNull(i + 1)
        if (code[i].opcode == Opcode.IGET_OBJECT && field?.type == TYPEFACE && next?.opcode == Opcode.RETURN_OBJECT &&
            (next as OneRegisterInstruction).registerA == read?.registerA) field.definingClass else null
    }.distinct()
    val holder = holders.singleOrNull()?.takeIf { it != definingClass }?.let(classDefBy) ?: emojiFontChanged()
    val fields = holder.fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) }.map { it.type }.sorted()
    val init = holder.methods.filter { it.name == "<init>" }.singleOrNull() ?: emojiFontChanged()
    if (!AccessFlags.FINAL.isSet(holder.accessFlags) || fields != listOf(TYPEFACE, FILE).sorted() ||
        init.hookId() != "${holder.type}-><init>($TYPEFACE$FILE)V") emojiFontChanged()
    init.validateEmojiFontHolder()
    return init.hookId()
}

/** The holder's constructor starts with the Object constructor, so the font file is passed on right after it. */
internal fun Method.validateEmojiFontHolder() {
    val first = implementation?.instructions?.firstOrNull()
    val call = (first as? ReferenceInstruction)?.reference as? MethodReference
    // The file arrives in p2, which a plain invoke can only pass from v15 or below.
    if (first?.opcode != Opcode.INVOKE_DIRECT || call?.definingClass != "Ljava/lang/Object;" || call.name != "<init>" ||
        1 in jumpTargets() || parameterRegister(2) > 15) emojiFontChanged()
}

/** Messenger still loads its own font; the extension learns where it lives so its glyphs can sit behind the phone's. */
internal fun MutableMethod.injectEmojiFontHolder() {
    validateEmojiFontHolder()
    addInstructions(1, "invoke-static {p2}, $EMOJI_FONT_CALL")
}

/**
 * The constructor of the chat list's last-message snippet, or null when this Messenger builds it some other way (#34
 * follow-up). A Like there is U+F0000, which only Messenger's downloaded emoji font can draw. LastMessageSnippetImpl
 * builds the snippet: a final class with one CharSequence, set by its only constructor, that names itself in its
 * toString. Use system emoji still applies without it, so a changed snippet only leaves that box as it was.
 */
internal fun likeSnippetInit(classDefBy: (String) -> ClassDef?): String? {
    val maker = classDefBy(SNIPPET_MAKER) ?: return null
    val built = maker.methods.flatMap { method ->
        method.implementation?.instructions?.toList().orEmpty().mapNotNull { instruction ->
            val call = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: return@mapNotNull null
            call.definingClass.takeIf { instruction.opcode == Opcode.INVOKE_DIRECT && call.name == "<init>" &&
                call.parameterTypes.map { it.toString() } == listOf(CHAR_SEQUENCE) }
        }
    }.distinct().mapNotNull(classDefBy).filter { snippet ->
        snippet.methods.any { method ->
            method.name == "toString" && method.implementation?.instructions?.any {
                ((it as? ReferenceInstruction)?.reference as? StringReference)?.string?.startsWith(SNIPPET_NAME) == true
            } == true
        }
    }
    val snippet = built.singleOrNull() ?: return null
    val fields = snippet.fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) }.map { it.type }
    val init = snippet.methods.filter { it.name == "<init>" }.singleOrNull() ?: return null
    if (!AccessFlags.FINAL.isSet(snippet.accessFlags) || fields != listOf(CHAR_SEQUENCE) ||
        init.hookId() != "${snippet.type}-><init>($CHAR_SEQUENCE)V" || !init.takesLikeSnippet()) return null
    return init.hookId()
}

/** The snippet's constructor starts with the Object constructor, and its text sits where a plain invoke can pass it. */
internal fun Method.takesLikeSnippet(): Boolean {
    val first = implementation?.instructions?.firstOrNull()
    val call = (first as? ReferenceInstruction)?.reference as? MethodReference
    return first?.opcode == Opcode.INVOKE_DIRECT && call?.definingClass == "Ljava/lang/Object;" && call.name == "<init>" &&
        1 !in jumpTargets() && parameterRegister(1) <= 15
}

/** The snippet's text goes through the extension before the constructor stores it. */
internal fun MutableMethod.injectLikeSnippet() {
    if (!takesLikeSnippet()) throw PatchException("Messenger controls: the chat list snippet changed after it was checked")
    addInstructions(1, "invoke-static {p1}, $CHAT_LIST_SNIPPET_CALL\nmove-result-object p1")
}

internal fun MutableMethod.validateEmojiTypeface(): List<Int> {
    if (returnType != TYPEFACE) throw PatchException("Expected Typeface return for emoji hook: ${hookId()}")
    val returns = implementation!!.instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_OBJECT }.map { it.index }
    if (returns.isEmpty()) emojiFontChanged()
    return returns
}

/**
 * Every return hands Messenger's typeface to the extension, which swaps in the phone's emoji (#34). A return can be a
 * branch target, and an insert before it would sit behind the branch, so the return itself becomes the call.
 */
internal fun MutableMethod.injectEmojiTypeface() {
    for (index in validateEmojiTypeface().reversed()) {
        val register = (getInstruction(index) as OneRegisterInstruction).registerA
        replaceInstruction(index, "invoke-static/range {v$register .. v$register}, $EMOJI_TYPEFACE_CALL")
        addInstructions(index + 1, "move-result-object v$register\nreturn-object v$register")
    }
}
