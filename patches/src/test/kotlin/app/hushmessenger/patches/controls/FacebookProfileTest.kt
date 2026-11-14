package app.hushmessenger.patches.controls

import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Keep the Profile button (#51): the chat details Profile button's gates are a hook key of their own. */
class FacebookProfileTest {
    @AfterTest fun restoreProfile() { activeProfile = SYNTHETIC_PROFILE }

    @Test fun theProfileGatesAskTheirOwnGetter() {
        val method = MutableMethod(fixtureMethod("LX/Pf1;->A01()Z", "new-instance v0, $FACEBOOK_PROFILE_BUTTON\nconst/4 v0, 0x1\nreturn v0"))
        injectControl("facebook", mapOf(FACEBOOK_PROFILE to listOf(method)))
        val calls = method.implementation!!.instructions.mapNotNull { (it as? ReferenceInstruction)?.reference?.toString() }
        assertTrue("$SETTINGS->hideFacebookProfile()Z" in calls, calls.toString())
        assertTrue("$SETTINGS->hideFacebook()Z" !in calls, calls.toString())
    }

    // Every supported build splits the same way: each Profile gate builds the button and no other Facebook shortcut.
    @Test fun nativeBuildsSplitTheProfileGatesFromTheOtherShortcuts() {
        val root = System.getenv("HUSH_NATIVE_FIXTURES")
        assumeTrue(root != null, "Set HUSH_NATIVE_FIXTURES to the exact stock fixture directory")
        val apks = Files.list(Path.of(root!!)).use { it.filter { p -> p.toString().endsWith(".apk") }.sorted().toList() }
        assertEquals(controlProfiles.size, apks.size)
        for (apk in apks) {
            val code = apk.fileName.toString().substringBeforeLast(".apk").substringAfterLast('-')
            activeProfile = controlProfileFor(code)
            val dex = DexFileFactory.loadDexContainer(apk.toFile(), Opcodes.forApi(35))
            val found = findControls(dex.dexEntryNames.flatMap { dex.getEntry(it)!!.dexFile.classes })
            validateControls(found, setOf("facebook", FACEBOOK_PROFILE))
            assertEquals(13, found.getValue(FACEBOOK_PROFILE).size, code)
            for (gate in found.getValue(FACEBOOK_PROFILE)) {
                val created = gate.implementation!!.instructions.filter { it.opcode == Opcode.NEW_INSTANCE }
                    .map { ((it as ReferenceInstruction).reference as TypeReference).type }
                assertTrue(FACEBOOK_PROFILE_BUTTON in created, "$code ${gate.hookId()}")
            }
            for (gate in found.getValue("facebook")) {
                val created = gate.implementation!!.instructions.filter { it.opcode == Opcode.NEW_INSTANCE }
                    .map { ((it as ReferenceInstruction).reference as TypeReference).type }
                assertTrue(created.any { it != FACEBOOK_PROFILE_BUTTON && it.contains("facebook", ignoreCase = true) }, "$code ${gate.hookId()}")
            }
        }
    }
}
