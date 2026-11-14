package app.hushmessenger.patches.controls

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

internal const val TAB_CONFIG = "tab_config"

/** Messenger traces the bottom bar's tab-list builder under this name. */
internal const val TAB_CONFIG_TRACE = "MessengerTabConfigurationCreator.createTabConfiguration"
internal const val VISIBLE_TABS_HELPER = "$SETTINGS->visibleTabs(Ljava/util/List;)Ljava/util/List;"

/**
 * The constants of Messenger's tab enum that a Hush switch takes off the bottom bar: People, Notifications, Meta AI, and
 * MUSE, the AI agent tab some accounts get in Meta AI's place. The extension maps each one by its enum name.
 */
private val HIDABLE_TABS = setOf("PEOPLE", "JEWEL", "AI", "MUSE")
private val TWO_LISTS = listOf("Ljava/util/List;", "Ljava/util/List;")

private fun tabsChanged(detail: String): Nothing =
    throw PatchException("Messenger controls: the tab list no longer matches the tested build ($detail)")

/** The tab-list builder: `(Context, FbUserSession)` to the bar's tab configuration, on an instance. */
internal fun Method.isTabConfiguration(): Boolean {
    if (AccessFlags.STATIC.isSet(accessFlags) || !returnType.startsWith("L") ||
        parameterTypes.map { it.toString() } != listOf("Landroid/content/Context;", FB_USER_SESSION)) return false
    return implementation?.instructions?.any {
        ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == TAB_CONFIG_TRACE
    } == true
}

internal fun findTabConfig(classes: Iterable<ClassDef>): List<Method> = classes.flatMap { it.methods }.filter { it.isTabConfiguration() }

/**
 * Index of the `new-instance` that starts the configuration the builder returns, or null. The builder reads every
 * hidable tab as a constant of one enum and hands the finished tab list to the configuration as its first argument:
 *
 *     sget-object vX, Tabs;->PEOPLE:Tabs;            (and JEWEL, AI, MUSE)
 *     ...
 *     new-instance vC, Configuration                 <-
 *     invoke-direct {vC, vTabs, vOther}, Configuration;-><init>(List, List)V
 */
internal fun Method.tabConfigSite(): Int? {
    val code = implementation?.instructions?.toList() ?: return null
    val builds = code.indices.filter { i ->
        val callee = (code[i] as? ReferenceInstruction)?.reference as? MethodReference
        (code[i].opcode == Opcode.INVOKE_DIRECT || code[i].opcode == Opcode.INVOKE_DIRECT_RANGE) &&
            callee?.name == "<init>" && callee.definingClass == returnType
    }
    val at = builds.singleOrNull() ?: return null
    // A plain (non-range) call keeps the list within v15, which the helper's own plain invoke needs.
    val build = code[at] as? FiveRegisterInstruction ?: return null
    val callee = (build as ReferenceInstruction).reference as MethodReference
    if (callee.parameterTypes.map { it.toString() } != TWO_LISTS || build.registerCount != 3) return null
    val start = code.getOrNull(at - 1) ?: return null
    if (start.opcode != Opcode.NEW_INSTANCE || (start as OneRegisterInstruction).registerA != build.registerC ||
        ((start as ReferenceInstruction).reference as TypeReference).type != returnType) return null
    val tabs = code.filter { it.opcode == Opcode.SGET_OBJECT }
        .mapNotNull { (it as ReferenceInstruction).reference as? FieldReference }
        .filter { it.name in HIDABLE_TABS && it.type == it.definingClass }
    if (tabs.map { it.name }.toSet() != HIDABLE_TABS || tabs.map { it.type }.toSet().size != 1) return null
    return at - 1
}

/** The register holding the tab list the configuration at [site] is built from. */
private fun Method.tabListRegister(site: Int): Int =
    (implementation!!.instructions.elementAt(site + 1) as FiveRegisterInstruction).registerD

/** True once a control sharing this builder already put the list through the extension. */
private fun Method.tabsFiltered(site: Int, list: Int): Boolean {
    val code = implementation!!.instructions.toList()
    val result = code.getOrNull(site - 1)
    val call = code.getOrNull(site - 2)
    return result?.opcode == Opcode.MOVE_RESULT_OBJECT && (result as OneRegisterInstruction).registerA == list &&
        call?.opcode == Opcode.INVOKE_STATIC &&
        ((call as ReferenceInstruction).reference as? MethodReference)?.toString() == VISIBLE_TABS_HELPER &&
        (call as FiveRegisterInstruction).registerCount == 1 && call.registerC == list
}

internal fun MutableMethod.validateTabConfig(): Int {
    if (!isTabConfiguration()) tabsChanged("builder ${hookId()}")
    val site = tabConfigSite() ?: tabsChanged("configuration ${hookId()}")
    // A jump straight to the configuration would build it from the full list, past the extension.
    val targets = jumpTargets()
    if (site in targets || site + 1 in targets) tabsChanged("configuration is a jump target")
    return site
}

/**
 * The finished tab list goes through the extension right before the configuration takes it, so the bar's list and the
 * tabs it draws match. People, Notifications and Meta AI share this builder; the first of them to patch adds the call.
 */
internal fun MutableMethod.injectTabConfig() {
    val site = validateTabConfig()
    val list = tabListRegister(site)
    if (tabsFiltered(site, list)) return
    addInstructions(site, """
        invoke-static {v$list}, $VISIBLE_TABS_HELPER
        move-result-object v$list
    """.trimIndent())
}
