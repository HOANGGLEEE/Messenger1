package app.hushmessenger.extension;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Archives only a completed local image URI supplied by Messenger's download callback.
 * This class never fetches a network URL and never attempts to decrypt Messenger media.
 */
final class UnsentMediaArchive {
    private static final long MAX_BYTES = 40L * 1024 * 1024;
    private static final int DECODE_BOUND = 256;

    static final class Result {
        final boolean saved;
        final boolean linked;
        final boolean usedDirectPath;
        final boolean duplicate;
        final String failure;
        final String localPath;

        private Result(boolean saved, boolean linked, boolean usedDirectPath,
                boolean duplicate, String failure, String localPath) {
            this.saved = saved;
            this.linked = linked;
            this.usedDirectPath = usedDirectPath;
            this.duplicate = duplicate;
            this.failure = failure;
            this.localPath = localPath;
        }

        static Result failure(String reason) {
            return new Result(false, false, false, false, reason, null);
        }
    }

    private UnsentMediaArchive() { }

    static synchronized Result capture(Context context, Uri uri, String directPath,
            String mimeType, long mediaKeyTimestamp, ClassLoader messengerLoader) {
        if (context == null) return Result.failure("no_context");
        if (uri == null) return Result.failure("missing_uri");
        if (mediaKeyTimestamp <= 0) return Result.failure("timestamp_missing");

        File temporary = null;
        try {
            File directory = new File(context.getFilesDir(), "hush_unsent_media");
            if (!directory.isDirectory() && !directory.mkdirs()) {
                return Result.failure("db_error");
            }
            temporary = File.createTempFile("incoming-", ".tmp", directory);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long copied = 0;
            InputStream opened = openCompletedStream(context, uri, messengerLoader);
            if (opened == null) return Result.failure("uri_open_failed");
            try (InputStream input = opened; FileOutputStream output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[16 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    copied += count;
                    if (copied > MAX_BYTES) return Result.failure("too_large");
                    digest.update(buffer, 0, count);
                    output.write(buffer, 0, count);
                }
                output.flush();
                output.getFD().sync();
            }
            if (copied <= 0 || !temporary.isFile() || temporary.length() != copied) {
                return Result.failure("missing_file");
            }

            String resolvedMime = normalizeMime(mimeType);
            if (!isImageMime(resolvedMime) || !Settings.hasImageFileSignature(temporary, resolvedMime)) {
                resolvedMime = inferMime(temporary);
            }
            if (resolvedMime == null || !Settings.hasImageFileSignature(temporary, resolvedMime)) {
                return Result.failure("bad_signature");
            }
            if (!canDecodeImage(temporary)) return Result.failure("decode_failed");

            String contentHash = hex(digest.digest());
            long now = System.currentTimeMillis();
            AntiUnsendStore store = AntiUnsendStore.get(context);
            String key = null;
            boolean hasDirectPath = directPath != null && !directPath.isEmpty();
            if (hasDirectPath) {
                key = AntiUnsendStore.mediaKey(directPath, mediaKeyTimestamp);
                if (key != null) {
                    store.captureMediaMetadata(directPath, mediaKeyTimestamp, resolvedMime, now);
                } else {
                    hasDirectPath = false;
                }
            }
            if (!hasDirectPath) {
                String fallbackKey = AntiUnsendStore.downloadedMediaKey(
                    mediaKeyTimestamp, resolvedMime, contentHash);
                key = store.capturePendingDownloadedMedia(
                    fallbackKey, mediaKeyTimestamp, resolvedMime, contentHash, now);
            }
            if (key == null || key.isEmpty()) return Result.failure("db_error");

            String fileName = key.replaceAll("[^A-Za-z0-9_-]", "_") + "." + extension(resolvedMime);
            File destination = new File(directory, fileName);
            boolean duplicate = false;
            if (destination.isFile() && destination.length() > 0) {
                String existingHash = sha256(destination);
                if (contentHash.equals(existingHash) &&
                        Settings.hasImageFileSignature(destination, resolvedMime) &&
                        canDecodeImage(destination)) {
                    duplicate = true;
                    temporary.delete();
                    temporary = null;
                } else if (!destination.delete()) {
                    return Result.failure("db_error");
                }
            }
            if (!duplicate) {
                if (!temporary.renameTo(destination)) return Result.failure("db_error");
                temporary = null;
            }

            boolean saved = hasDirectPath
                ? store.saveMediaFile(directPath, mediaKeyTimestamp,
                    destination.getAbsolutePath(), contentHash, now)
                : store.saveMediaFileByKey(key, destination.getAbsolutePath(), contentHash, now);
            if (!saved) {
                destination.delete();
                return Result.failure("db_error");
            }
            return new Result(true, store.mediaIsLinked(key), hasDirectPath,
                duplicate, null, destination.getAbsolutePath());
        } catch (Exception error) {
            return Result.failure("db_error");
        } finally {
            if (temporary != null && temporary.exists()) temporary.delete();
        }
    }

    private static InputStream openCompletedStream(Context context, Uri uri, ClassLoader loader) {
        try {
            InputStream input = context.getContentResolver().openInputStream(uri);
            if (input != null) return input;
        } catch (Exception ignored) { }

        String path = uri.getPath();
        if (path == null || path.isEmpty()) return null;
        if ("file".equalsIgnoreCase(uri.getScheme())) {
            try {
                File file = new File(path);
                if (file.isFile() && file.canRead()) return new FileInputStream(file);
            } catch (Exception ignored) { }
        }
        if (loader != null) {
            try {
                Class<?> helper = Class.forName("X.001", false, loader);
                Method resolver = helper.getDeclaredMethod("A0F", String.class);
                resolver.setAccessible(true);
                Object resolved = resolver.invoke(null, path);
                if (resolved instanceof File) {
                    File file = (File) resolved;
                    if (file.isFile() && file.canRead()) return new FileInputStream(file);
                }
            } catch (Exception ignored) { }
        }
        return null;
    }

    private static boolean canDecodeImage(File file) {
        if (file == null || !file.isFile()) return false;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false;

        int sample = 1;
        while (bounds.outWidth / (sample * 2) > DECODE_BOUND ||
                bounds.outHeight / (sample * 2) > DECODE_BOUND) {
            sample *= 2;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        Bitmap decoded = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        if (decoded == null) return false;
        decoded.recycle();
        return true;
    }

    private static String normalizeMime(String mimeType) {
        if (mimeType == null) return null;
        int separator = mimeType.indexOf(';');
        String value = (separator >= 0 ? mimeType.substring(0, separator) : mimeType).trim()
            .toLowerCase(Locale.ROOT);
        return value.isEmpty() ? null : value;
    }

    private static boolean isImageMime(String mimeType) {
        return mimeType != null && mimeType.startsWith("image/");
    }

    private static String inferMime(File file) throws java.io.IOException {
        String[] candidates = {
            "image/jpeg", "image/png", "image/webp", "image/gif",
            "image/heic", "image/heif", "image/avif"
        };
        for (String candidate : candidates) {
            if (Settings.hasImageFileSignature(file, candidate)) return candidate;
        }
        return null;
    }

    private static String extension(String mimeType) {
        switch (mimeType) {
            case "image/jpeg":
            case "image/jpg": return "jpg";
            case "image/png": return "png";
            case "image/webp": return "webp";
            case "image/gif": return "gif";
            case "image/heic": return "heic";
            case "image/heif": return "heif";
            case "image/avif": return "avif";
            default: return "img";
        }
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[16 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return hex(digest.digest());
    }

    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte item : bytes) value.append(String.format(Locale.ROOT, "%02x", item & 0xff));
        return value.toString();
    }
}
