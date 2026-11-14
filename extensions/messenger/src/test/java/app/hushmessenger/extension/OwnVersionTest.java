package app.hushmessenger.extension;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import android.content.pm.PackageInfo;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 36})
public class OwnVersionTest {

    @After public void tearDown() {
        OwnVersion.reset();
        MessengerSignature.ownPackage = null;
    }

    private static PackageInfo info(String packageName, long code) {
        PackageInfo info = new PackageInfo();
        info.packageName = packageName;
        info.setLongVersionCode(code);
        return info;
    }

    /** Spoof package version raised the installed code; the app's own reads get the build it was patched from. */
    @Test public void theAppsOwnReadsGetTheOriginalCode() {
        MessengerSignature.ownPackage = "com.facebook.orca";
        OwnVersion.original = 346611369L;
        PackageInfo own = info("com.facebook.orca", Integer.MAX_VALUE);
        assertEquals(346611369, OwnVersion.versionCode(own));
        assertEquals(346611369L, OwnVersion.longVersionCode(own));
        // Android's high half stays as it reported it.
        assertEquals((5L << 32) | 346611369L, OwnVersion.longVersionCode(info("com.facebook.orca", (5L << 32) | Integer.MAX_VALUE)));
    }

    /** Google Play services, Facebook, stock Messenger beside a clone: what Android said, unchanged. */
    @Test public void anyOtherPackageKeepsWhatAndroidReported() {
        MessengerSignature.ownPackage = "com.facebook.orca.hush";
        OwnVersion.original = 346611369L;
        for (String other : new String[] {"com.google.android.gms", "com.facebook.katana", "com.facebook.orca", null}) {
            PackageInfo info = info(other, 254_513_000L);
            assertEquals(String.valueOf(other), 254_513_000, OwnVersion.versionCode(info));
            assertEquals(String.valueOf(other), 254_513_000L, OwnVersion.longVersionCode(info));
        }
        assertEquals(346611369, OwnVersion.versionCode(info("com.facebook.orca.hush", Integer.MAX_VALUE)));
    }

    /** Without a record of the patched-from build, nothing is guessed. */
    @Test public void anUnknownOriginalKeepsWhatAndroidReported() {
        MessengerSignature.ownPackage = "com.facebook.orca";
        PackageInfo own = info("com.facebook.orca", Integer.MAX_VALUE);
        // The extension alone, as HostScreens.originalBuild() is before the patch writes it.
        assertEquals(Integer.MAX_VALUE, OwnVersion.versionCode(own));
        assertEquals(0L, OwnVersion.original);
        assertEquals((long) Integer.MAX_VALUE, OwnVersion.longVersionCode(own));
    }

    /** A null PackageInfo throws the same NullPointerException the field read or getter would. */
    @Test public void aMissingPackageInfoThrowsLikeTheReadItReplaces() {
        assertThrows(NullPointerException.class, () -> OwnVersion.versionCode(null));
        assertThrows(NullPointerException.class, () -> OwnVersion.longVersionCode(null));
    }

    @Test public void onlyAWellFormedRecordGivesACode() {
        assertEquals(346611369L, OwnVersion.originalCode("583.0.0.29.95|346611369|base"));
        assertEquals(346611351L, OwnVersion.originalCode("583.0.0.29.95|346611351|346611351"));
        for (String odd : new String[] {"", null, "583.0.0.29.95", "583.0.0.29.95|0|base", "583.0.0.29.95|2147483648|base",
                "583.0.0.29.95|346611369|", "583.0.0.29.95|346611369|base|x"}) {
            assertEquals(String.valueOf(odd), 0L, OwnVersion.originalCode(odd));
        }
    }
}
