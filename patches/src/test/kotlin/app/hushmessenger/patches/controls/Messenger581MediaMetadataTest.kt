package app.hushmessenger.patches.controls

import app.morphe.patcher.patch.PatchException
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import kotlin.test.Test
import kotlin.test.assertFailsWith

class Messenger581MediaMetadataTest {
    private fun field(owner: String, name: String, type: String) =
        ImmutableField(owner, name, type, AccessFlags.PUBLIC.value, null, null, null)

    private fun downloader() = fixtureClass(
        "LX/SUm;",
        extraFields = listOf(field("LX/SUm;", "A07", "LX/8uu;")),
    )

    @Test fun acceptsTheVerifiedA05DirectPathField() {
        val metadata = fixtureClass(
            "LX/8uu;",
            extraFields = listOf(
                field("LX/8uu;", "A03", "J"),
                field("LX/8uu;", "A05", "Ljava/lang/String;"),
                field("LX/8uu;", "A06", "Ljava/lang/String;"),
                // A07 exists in Messenger 581, but is not the direct path.
                field("LX/8uu;", "A07", "Ljava/lang/String;"),
            ),
        )

        validateMessenger581ImageMetadataContract(metadata, downloader())
    }

    @Test fun rejectsTheOldIncorrectA07DirectPathAssumption() {
        val metadata = fixtureClass(
            "LX/8uu;",
            extraFields = listOf(
                field("LX/8uu;", "A03", "J"),
                field("LX/8uu;", "A06", "Ljava/lang/String;"),
                field("LX/8uu;", "A07", "Ljava/lang/String;"),
            ),
        )

        assertFailsWith<PatchException> {
            validateMessenger581ImageMetadataContract(metadata, downloader())
        }
    }
}
