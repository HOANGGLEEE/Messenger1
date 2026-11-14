package app.hushmessenger.patches.controls

import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.SwitchPayload
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/** Plugin gates by control, found by their kill switch or plugin names. False is the normal disabled path. */
internal data class PluginGate(val anchors: Set<String>)

internal const val AD_CONTEXT_BANNER = "ad_context_banner"
internal const val TAB_PEOPLE = "tab_people"
internal const val TAB_NOTIFICATIONS = "tab_notifications"
internal const val AI_MUSE_TAB = "ai_muse_tab"

internal val pluginGates = mapOf(
    "people" to PluginGate(
        setOf(
            "com.facebook.messaging.friending.plugins.inboxunit.InboxPeopleYouMayKnowSectionKillSwitch",
        ),
    ),
    // The same suggestions repeated after the last chat; selected by the people control.
    "people_list_end" to PluginGate(
        setOf(
            "com.facebook.messaging.friending.plugins.inboxthreadlistend.InboxPYMKThreadListEndKillSwitch",
        ),
    ),
    "friend_requests" to PluginGate(
        setOf(
            "com.facebook.messaging.friending.plugins.friendrequestinboxunit.FriendingFriendrequestinboxunitKillSwitch",
        ),
    ),
    "growth" to PluginGate(
        setOf(
            "com.facebook.messaging.friending.plugins.growthpromotioninboxunit.FriendingGrowthpromotioninboxunitKillSwitch",
        ),
    ),
    "moments" to PluginGate(
        setOf(
            "com.facebook.messaging.navigation.plugins.momentsfolder.NavigationMomentsfolderKillSwitch",
        ),
    ),
    "ai_stickers" to PluginGate(
        setOf(
            "com.facebook.stickers.keyboardls.generatedtab.plugins.core.KeyboardlsGeneratedtabCoreKillSwitch",
            "com.facebook.messaging.suggestedkeyboard.plugins.core.composer.rows.genai.GenAiSearchSuggestedRow",
        ),
    ),
    "avatar_stickers" to PluginGate(
        setOf(
            "com.facebook.stickers.keyboardls.avatartab.plugins.core.KeyboardlsAvatartabCoreKillSwitch",
        ),
    ),
    "inbox_promotions" to PluginGate(
        setOf(
            "com.facebook.messaging.quickpromotion.plugins.threadlist.QuickpromotionThreadlistKillSwitch",
            "com.facebook.messaging.quickpromotion.plugins.threadlistmsys.QuickpromotionThreadlistmsysKillSwitch",
        ),
    ),
    "chat_promotions" to PluginGate(
        setOf(
            "com.facebook.messaging.quickpromotion.plugins.threadview.QuickpromotionThreadviewKillSwitch",
            "com.facebook.messaging.quickpromotion.plugins.threadviewmsys.QuickpromotionThreadviewmsysKillSwitch",
        ),
    ),
    // The ad a chat with a business started from, shown as a banner at the top of the chat. It isn't a Quickpromotion,
    // so the chat promotion gates never see it. The anchor is the banner itself, which only the thread banner gate builds.
    AD_CONTEXT_BANNER to PluginGate(
        setOf(
            "com.facebook.messaging.nativepagereply.plugins.adscontextprovider.threadviewbanner.CTMAdsThreadViewBannerImplementation",
        ),
    ),
    "suggested_replies" to PluginGate(
        setOf(
            "com.facebook.messaging.business.plugins.suggestedreply.SuggestedReplyKillSwitch",
        ),
    ),
    "business_suggestions" to PluginGate(
        setOf(
            "com.facebook.messaging.business.plugins.suggestasyoutype.SAYTKillSwitch",
        ),
    ),
    "event_prompts" to PluginGate(
        setOf(
            "com.facebook.messaging.events.plugins.qp.EventsQpKillSwitch",
        ),
    ),
    "reels_badge" to PluginGate(
        setOf(
            "com.facebook.messaging.reels.plugins.badge.ReelsBadgeKillSwitch",
        ),
    ),
    "ai_toolbar" to PluginGate(
        setOf(
            "com.facebook.messaging.inbox.tab.plugins.core.tabtoolbarbutton.aihomebutton.AiHomeButtonKillSwitch",
        ),
    ),
    // The Meta AI bottom tab. Its kill switch also gates the tab's own toolbar buttons, so the
    // anchor is the tab content, which only the bottom bar's gate builds.
    "ai_tab" to PluginGate(
        setOf(
            "com.facebook.messaging.aibot.plugins.tab.tabcontent.MetaAiTabContentImplementation",
        ),
    ),
    // The same tab on accounts where mobileconfig swaps it for MUSE, an AI agent tab with its own gate in the bottom bar.
    AI_MUSE_TAB to PluginGate(
        setOf(
            "com.facebook.messaging.aiagent.plugins.tab.tabcontent.MuseTabContentImplementation",
        ),
    ),
    // The People and Notifications bottom tabs, anchored the same way. Each tab content is named by its bottom bar gate
    // and, for Notifications, a static initializer that discovery never reads.
    TAB_PEOPLE to PluginGate(
        setOf(
            "com.facebook.messaging.peopletab.plugins.tab.tabcontent.PeopleTabContentImplementation",
        ),
    ),
    TAB_NOTIFICATIONS to PluginGate(
        setOf(
            "com.facebook.messaging.inbox.jewel.jeweltab.plugins.core.tabcontent.JewelTabContentImplementation",
        ),
    ),
    // Mark as read in the menu a long press on a chat opens. Both of the menu's builders gate the row by this plugin
    // name. Mark as unread and the community chats' own Mark as read are other plugins.
    "mark_read_action" to PluginGate(
        setOf(
            "com.facebook.messaging.threadlist.threaditemmenu.plugins.core.markreadmenuitem.MarkReadMenuItemImplementation",
        ),
    ),
)

/**
 * Redex's string tables: static (I)String methods that switch on the key straight to a constant, by method and key.
 * 583 kept some kill switch names in one, so a gate loads its anchor with `const/16 key` and a lookup, not a literal.
 */
internal fun redexStringTables(classes: Iterable<ClassDef>): Map<String, Map<Int, String>> = buildMap {
    for (method in classes.asSequence().flatMap { it.methods }) {
        if (!AccessFlags.STATIC.isSet(method.accessFlags) || method.returnType != "Ljava/lang/String;" ||
            method.parameterTypes.map { it.toString() } != listOf("I")) continue
        val code = method.implementation?.instructions?.toList() ?: continue
        val switch = code.firstOrNull()?.takeIf { it.opcode == Opcode.PACKED_SWITCH || it.opcode == Opcode.SPARSE_SWITCH } as? OffsetInstruction
            ?: continue
        val addresses = code.runningFold(0) { address, instruction -> address + instruction.codeUnits }
        val indexAt = code.indices.associateBy { addresses[it] }
        val payload = indexAt[switch.codeOffset]?.let(code::get) as? SwitchPayload ?: continue
        val cases = payload.switchElements.mapNotNull { case ->
            val at = indexAt[case.offset] ?: return@mapNotNull null
            val load = code[at]
            val exit = code.getOrNull(at + 1)
            val string = ((load as? ReferenceInstruction)?.reference as? StringReference)?.string
            if (string == null || load.opcode !in setOf(Opcode.CONST_STRING, Opcode.CONST_STRING_JUMBO) || exit?.opcode != Opcode.RETURN_OBJECT ||
                (exit as OneRegisterInstruction).registerA != (load as OneRegisterInstruction).registerA) null else case.key to string
        }.toMap()
        if (cases.isNotEmpty()) put(method.hookId(), cases)
    }
}

/** The tables of the APK being patched, kept for validators that follow a lookup instead of a literal. */
internal var activeStringTables: Map<String, Map<Int, String>> = emptyMap()

/** The string the call at [at] looks up in a [redexStringTables] table by a constant key loaded right before it, if it is one. */
internal fun List<Instruction>.tableLookupAt(at: Int, tables: Map<String, Map<Int, String>>): String? {
    val call = getOrNull(at) as? FiveRegisterInstruction ?: return null
    val table = tables[(call as? ReferenceInstruction)?.reference?.toString()] ?: return null
    val key = getOrNull(at - 1) ?: return null
    return if (call.opcode != Opcode.INVOKE_STATIC || call.registerCount != 1 ||
        key.opcode !in setOf(Opcode.CONST_4, Opcode.CONST_16, Opcode.CONST) || (key as? OneRegisterInstruction)?.registerA != call.registerC) null
    else table[(key as? NarrowLiteralInstruction)?.narrowLiteral]
}

/** The index of the instruction that loads [value]: its literal, or the call that looks it up in a string table. */
internal fun List<Instruction>.indexOfAnchor(value: String, tables: Map<String, Map<Int, String>> = activeStringTables): Int =
    indices.indexOfFirst { at ->
        ((this[at] as? ReferenceInstruction)?.reference as? StringReference)?.string == value || tableLookupAt(at, tables) == value
    }

/** The strings [this] looks up in a [redexStringTables] table by a constant key loaded right before the call. */
internal fun Method.tableStrings(tables: Map<String, Map<Int, String>>): Set<String> {
    val code = implementation?.instructions?.toList() ?: return emptySet()
    return code.indices.mapNotNull { code.tableLookupAt(it, tables) }.toSet()
}
