package app.hushmessenger.patches.controls

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The synthetic label for the detector, named after the first build it was found in. */
internal const val RECENT_MEDIA_DETECTOR = "LX/A0t;->A01(LX/5Td;Lkotlin/jvm/functions/Function0;)V"

private const val DETECTOR_OWNER = "LX/A0t;"

/**
 * The detector cut down to what every build shares: it reads the newest photo from MediaStore's image table and
 * builds the card's show event through the event's static factory, with locals to spare above its two parameters.
 */
private val DETECTOR_BODY = """
    sget-object v0, $IMAGES_URI
    const-string v1, "image/jpeg"
    const/4 v2, 0x0
    const/4 v3, 0x0
    const-wide/16 v4, 0x0
    invoke-static/range {v0 .. v5}, $QUICK_SEND_SHOW->A00(Landroid/net/Uri;Ljava/lang/String;IIJ)$QUICK_SEND_SHOW
    move-result-object v0
    return-void
""".trimIndent()

private fun detector(
    id: String = RECENT_MEDIA_DETECTOR,
    body: String = DETECTOR_BODY,
    registers: Int = 12,
    flags: Int = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
): MutableMethod = fixtureMethod(id, body, registers, flags)

/** The detector beside the quick send handler that takes the event it built, which never gets the hook. */
internal fun recentMediaFixture(detectorMethod: MutableMethod = detector()): List<MutableClass> = listOf(
    fixtureClass(DETECTOR_OWNER, listOf(
        detectorMethod,
        detector("$DETECTOR_OWNER->A02(${QUICK_SEND_SHOW}Lkotlin/jvm/functions/Function0;)V"),
    )),
)

private fun Method.code() = implementation!!.instructions.toList()

class RecentMediaTest {
    @AfterTest fun reset() {
        activeProfile = SYNTHETIC_PROFILE
    }

    private fun found(classes: List<MutableClass>) = mirroredControls(classes).getValue(RECENT_MEDIA).map { it.hookId() }

    @Test fun theDetectorIsFoundByTheEventItBuildsAndThePhotosItReads() {
        assertEquals(listOf(RECENT_MEDIA_DETECTOR), found(recentMediaFixture()))
        validateControls(mirroredControls(recentMediaFixture()), setOf(RECENT_MEDIA))
    }

    @Test fun lookalikesAreNotTheDetector() {
        val noScan = DETECTOR_BODY.replace(IMAGES_URI, "Landroid/net/Uri;->EMPTY:Landroid/net/Uri;")
        val otherFactory = DETECTOR_BODY.replace("$QUICK_SEND_SHOW->A00", "LX/Other;->A00")
        val cases = mapOf(
            "not static" to recentMediaFixture(detector(flags = AccessFlags.PUBLIC.value)),
            "no photo scan" to recentMediaFixture(detector(body = noScan)),
            "another factory" to recentMediaFixture(detector(body = otherFactory)),
            "returns a value" to recentMediaFixture(detector(id = RECENT_MEDIA_DETECTOR.replace(")V", ")Z"),
                body = DETECTOR_BODY.replace("return-void", "const/4 v0, 0x0\nreturn v0"))),
            "on the event" to listOf(fixtureClass(QUICK_SEND_SHOW, listOf(detector(id = "$QUICK_SEND_SHOW->A01(LX/5Td;Lkotlin/jvm/functions/Function0;)V")))),
            "only the handler" to listOf(fixtureClass(DETECTOR_OWNER, listOf(
                detector("$DETECTOR_OWNER->A02(${QUICK_SEND_SHOW}Lkotlin/jvm/functions/Function0;)V")))),
        )
        for ((case, classes) in cases) {
            assertTrue(found(classes).isEmpty(), case)
            assertFailsWith<PatchException>(case) { validateControls(mirroredControls(classes), setOf(RECENT_MEDIA)) }
        }
    }

    @Test fun theSwitchSkipsThePhotoLookupBeforeAnyStockWork() {
        val method = detector()
        val before = method.code()
        injectControl(RECENT_MEDIA, mapOf(RECENT_MEDIA to listOf(method)))
        val code = method.code()
        assertEquals("$SETTINGS->hideRecentMedia()Z", ((code[0] as ReferenceInstruction).reference).toString())
        assertEquals(Opcode.MOVE_RESULT, code[1].opcode)
        assertEquals(0, (code[1] as OneRegisterInstruction).registerA)
        assertEquals(Opcode.IF_EQZ, code[2].opcode)
        assertEquals(4, code.branchTarget(2))
        assertEquals(Opcode.RETURN_VOID, code[3].opcode)
        // Off, Pause and safe mode fall through to Messenger's own lookup, unchanged.
        assertEquals(before, code.drop(4))
    }

    @Test fun anUnusableDetectorRefusesTheSwitchBeforeAnyEdit() {
        val cases = mapOf(
            // Two registers are exactly the parameter words, so v0 would be the first parameter.
            "no free register" to detector(registers = 2, body = """
                sget-object v0, $IMAGES_URI
                const-string v1, "image/jpeg"
                invoke-static {v0, v1}, $QUICK_SEND_SHOW->A00(Landroid/net/Uri;Ljava/lang/String;)$QUICK_SEND_SHOW
                return-void
            """.trimIndent()),
            "the handler" to detector("$DETECTOR_OWNER->A02(${QUICK_SEND_SHOW}Lkotlin/jvm/functions/Function0;)V"),
            "no photo scan" to detector(body = DETECTOR_BODY.replace(IMAGES_URI, "Landroid/net/Uri;->EMPTY:Landroid/net/Uri;")),
        )
        for ((case, bad) in cases) {
            val badBefore = bad.code()
            assertFailsWith<PatchException>(case) {
                injectControl(RECENT_MEDIA, mapOf(RECENT_MEDIA to listOf(bad)))
            }
            assertEquals(badBefore, bad.code(), case)
        }
    }

    @Test fun everySupportedBuildHooksItsOneDetector() {
        val root = System.getenv("HUSH_NATIVE_FIXTURES")
        assumeTrue(root != null, "Set HUSH_NATIVE_FIXTURES to the exact stock fixture directory")
        val apks = Files.list(Path.of(root!!)).use { it.filter { p -> p.toString().endsWith(".apk") }.sorted().toList() }
        assertEquals(controlProfiles.size, apks.size)
        for (apk in apks) {
            val code = apk.fileName.toString().substringBeforeLast(".apk").substringAfterLast('-')
            activeProfile = controlProfileFor(code)
            val dex = DexFileFactory.loadDexContainer(apk.toFile(), Opcodes.forApi(35))
            val detectors = findRecentMedia(dex.dexEntryNames.flatMap { dex.getEntry(it)!!.dexFile.classes })
            assertEquals(activeProfile.hooks.getValue(RECENT_MEDIA), detectors.map { it.hookId() }.toSet(), code)
            validateControls(mapOf(RECENT_MEDIA to detectors), setOf(RECENT_MEDIA))
            val method = MutableMethod(detectors.single())
            val before = method.code()
            method.injectRecentMedia()
            val after = method.code()
            assertEquals("$SETTINGS->hideRecentMedia()Z", ((after[0] as ReferenceInstruction).reference).toString(), code)
            assertEquals(4, after.branchTarget(2), code)
            assertEquals(Opcode.RETURN_VOID, after[3].opcode, code)
            assertEquals(before.map { it.opcode }.filter { it != Opcode.NOP }, after.drop(4).map { it.opcode }.filter { it != Opcode.NOP }, code)
        }
    }
}
