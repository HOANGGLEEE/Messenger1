package app.hushmessenger.patches.controls

import app.morphe.patcher.extensions.InstructionExtensions.removeInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableDexFile
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MessageLogImagesTest {
    @Test fun exact587280PreviewRoutesTakeTheHooksAndRoundTripAsDex() {
        val path = System.getenv("HUSH_IMAGE_APK")?.let(Path::of)
        assumeTrue(path != null && Files.isRegularFile(path), "Set HUSH_IMAGE_APK to stock Messenger 347415280")
        val dex = DexFileFactory.loadDexContainer(path!!.toFile(), Opcodes.forApi(35))
        val classes = dex.dexEntryNames.flatMap { dex.getEntry(it)!!.dexFile.classes }
        val rendererClass = MutableClass(classes.single { it.type == IMAGE_RENDERER_280 })
        val updaterClass = MutableClass(classes.single { it.type == IMAGE_UPDATER_280 })
        val renderer = rendererClass.methods.single { it.name == "A06" }
        val updater = updaterClass.methods.single { it.name == "A00" }
        val sites = renderer.messageLogImageSites280()
        assertEquals(MessageLogImageSites(1135, 1165, 1204), sites)
        updater.validateMessageLogImageUpdater280()
        val broken = MutableMethod(renderer)
        broken.removeInstruction(1082)
        assertFailsWith<PatchException> { broken.messageLogImageSites280() }
        renderer.injectMessageLogImages280(sites)
        updater.injectMessageLogImageUpdater280()
        fun references(method: MutableMethod) = method.implementation!!.instructions.mapNotNull {
            (it as? ReferenceInstruction)?.reference?.toString()
        }
        val sink = "$SETTINGS->recordReceivedImage(Ljava/lang/String;Ljava/lang/String;Ljava/io/File;)V"
        assertEquals(1, references(renderer).count { it == sink })
        assertEquals(1, references(updater).count { it == sink })
        assertEquals(2, references(renderer).count { it == "$SETTINGS->logReceivedMessages()Z" })
        val output = Files.createTempFile("messenger-image-route", ".dex")
        try {
            DexFileFactory.writeDexFile(output.toString(), ImmutableDexFile(Opcodes.forApi(28), listOf(rendererClass, updaterClass)))
            val reread = DexFileFactory.loadDexFile(output.toFile(), Opcodes.forApi(28))
            assertEquals(2, reread.classes.size)
            assertTrue(reread.classes.flatMap { it.methods }.any { method ->
                method.implementation?.instructions?.any { (it as? ReferenceInstruction)?.reference?.toString() == sink } == true
            })
        } finally { Files.deleteIfExists(output) }
    }
}
