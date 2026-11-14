package app.hushmessenger.patches.controls

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/** Native notification preview routes verified against stock 587 code 347415280. */
internal const val IMAGE_RENDERER_280 = "LX/9yV;"
internal const val IMAGE_UPDATER_280 = "LX/A0L;"
private const val PREVIEW_280 = "LX/Bpq;"
private const val LOADER_280 = "LX/A0M;"
private const val FILE = "Ljava/io/File;"
private const val RECEIVED_IMAGE = "$SETTINGS->recordReceivedImage(Ljava/lang/String;Ljava/lang/String;$FILE)V"

internal data class MessageLogImageSites(val eligible: Int, val load: Int, val ready: Int)

internal fun Method.messageLogImageSites280(): MessageLogImageSites {
    val code = implementation?.instructions?.toList().orEmpty()
    fun ref(index: Int) = (code.getOrNull(index) as? ReferenceInstruction)?.reference as? MethodReference
    fun isCall(index: Int, owner: String, name: String) = ref(index)?.let { it.definingClass == owner && it.name == name } == true
    val marker = code.indexOfFirst { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == "MediaPreviewUpdater" }
    val load = code.indices.filter { isCall(it, LOADER_280, "A02") }.singleOrNull()
    val mime = code.indices.filter { it > marker && (load == null || it < load) && isCall(it, "Ljava/util/Set;", "contains") }.singleOrNull()
    val ready = code.indices.filter { it > (load ?: code.size) && isCall(it, FILE, "length") }.singleOrNull()
    if (definingClass != IMAGE_RENDERER_280 || name != "A06" || marker < 0 || load == null || mime == null || ready == null ||
        implementation?.registerCount != 57 || parameterTypes.map { it.toString() } != listOf(
            "Landroid/graphics/Bitmap;", "LX/0MD;", FB_USER_SESSION, "LX/D6K;", "Ljava/lang/String;", "I") ||
        code.getOrNull(mime + 1)?.opcode != Opcode.MOVE_RESULT || code.getOrNull(mime + 2)?.opcode != Opcode.IF_EQZ ||
        (code[mime + 1] as? OneRegisterInstruction)?.registerA != 3 ||
        code.getOrNull(load - 3)?.opcode != Opcode.INVOKE_INTERFACE_RANGE ||
        AccessFlags.STATIC.isSet(accessFlags) || code.getOrNull(ready + 9)?.opcode != Opcode.GOTO_16) {
        throw PatchException("Message log: Messenger 347415280 media preview renderer has changed")
    }
    return MessageLogImageSites(mime + 3, load - 3, ready + 9)
}

internal fun Method.validateMessageLogImageUpdater280() {
    if (definingClass != IMAGE_UPDATER_280 || name != "A00" || returnType != "V" || !AccessFlags.STATIC.isSet(accessFlags) || implementation?.registerCount != 18 ||
        parameterTypes.map { it.toString() } != listOf(FB_USER_SESSION, MESSAGE, PREVIEW_280, IMAGE_UPDATER_280,
            "Lcom/facebook/messaging/notify/type/MessagingNotification;", "Ljava/lang/String;", "I")) {
        throw PatchException("Message log: Messenger 347415280 media preview callback has changed")
    }
}

internal fun MutableMethod.injectMessageLogImages280(sites: MessageLogImageSites) {
    // MIME eligibility and earlier stock checks run before this branch.
    addInstructionsWithLabels(sites.ready, """
        invoke-static {}, $SETTINGS->logReceivedMessages()Z
        move-result v3
        if-eqz v3, :hush_image_ready
        iget-object v3, v10, $MESSAGE->A1m:Ljava/lang/String;
        iget-object v5, v10, $MESSAGE->A0e:$THREAD_KEY_TYPE
        if-eqz v5, :hush_image_ready
        invoke-virtual {v5}, $THREAD_KEY_TYPE->toString()Ljava/lang/String;
        move-result-object v5
        iget-object v13, v4, $PREVIEW_280->A01:$FILE
        invoke-static {v3, v5, v13}, $RECEIVED_IMAGE
    """.trimIndent(), ExternalLabel("hush_image_ready", getInstruction(sites.ready)))
    addInstructionsWithLabels(sites.eligible, """
        invoke-static {}, $SETTINGS->logReceivedMessages()Z
        move-result v3
        if-nez v3, :hush_load_image
    """.trimIndent(), ExternalLabel("hush_load_image", getInstruction(sites.load)))
}

internal fun MutableMethod.injectMessageLogImageUpdater280() {
    addInstructionsWithLabels(0, """
        invoke-static {}, $SETTINGS->logReceivedMessages()Z
        move-result v0
        if-eqz v0, :hush_stock_preview
        move-object/from16 v0, p1
        iget-object v1, v0, $MESSAGE->A1m:Ljava/lang/String;
        iget-object v2, v0, $MESSAGE->A0e:$THREAD_KEY_TYPE
        if-eqz v2, :hush_stock_preview
        invoke-virtual {v2}, $THREAD_KEY_TYPE->toString()Ljava/lang/String;
        move-result-object v2
        move-object/from16 v0, p2
        iget-object v0, v0, $PREVIEW_280->A01:$FILE
        invoke-static {v1, v2, v0}, $RECEIVED_IMAGE
    """.trimIndent(), ExternalLabel("hush_stock_preview", getInstruction(0)))
}
