package app.hushmessenger.extension;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.lang.ref.WeakReference;

/**
 * Lets a user choose a local audio file and hands it to Messenger's existing voice-message sender.
 * The outgoing object is Messenger's own MediaResource + WaveformData, not a generic file attachment.
 */
public final class AudioPickerActivity extends Activity {
    private static final int REQUEST_OPEN_AUDIO = 7104;
    private static volatile WeakReference<Object> pendingSender = new WeakReference<>(null);

    private MediaPlayer previewPlayer;
    private AlertDialog confirmDialog;
    private TextView statusView;
    private Button sendButton;
    private Uri selectedUri;

    private static final int WAVEFORM_BARS = 48;
    private static final long MAX_WAVEFORM_DECODE_MS = 10L * 60L * 1000L;

    private static final String MEDIA_RESOURCE = "com.facebook.ui.media.attachments.model.MediaResource";
    private static final String MEDIA_RESOURCE_BUILDER = "LX.8yp";
    private static final String MEDIA_RESOURCE_SEND_SOURCE = "com.facebook.ui.media.attachments.source.MediaResourceSendSource";
    private static final String WAVEFORM_DATA = "com.facebook.xapp.messaging.threadview.model.audio.WaveformData";
    private static final String IMMUTABLE_LIST = "com.google.common.collect.ImmutableList";

    public static void launch(Activity host, Object voiceMessageSender) {
        if (host == null || voiceMessageSender == null) return;
        pendingSender = new WeakReference<>(voiceMessageSender);
        Intent intent = new Intent(host, AudioPickerActivity.class);
        host.startActivity(intent);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        openAudioDocument();
    }

    private void openAudioDocument() {
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        picker.addCategory(Intent.CATEGORY_OPENABLE);
        picker.setType("audio/*");
        picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(picker, REQUEST_OPEN_AUDIO);
        } catch (Throwable error) {
            Toast.makeText(this, "Không mở được bộ chọn tệp âm thanh.", Toast.LENGTH_LONG).show();
            clearSender();
            finish();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_OPEN_AUDIO) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            clearSender();
            finish();
            return;
        }

        selectedUri = data.getData();
        int grantFlags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        if ((grantFlags & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) {
            try {
                getContentResolver().takePersistableUriPermission(selectedUri, grantFlags);
            } catch (SecurityException ignored) {
                // The temporary result grant remains valid for this immediate send.
            }
        }
        showConfirmation(selectedUri);
    }

    private void showConfirmation(Uri uri) {
        String name = displayName(uri);
        long durationMs = durationMillis(uri);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        content.setPadding(pad, dp(8), pad, dp(8));

        TextView fileName = new TextView(this);
        fileName.setText(name);
        fileName.setTextSize(16);
        fileName.setMaxLines(2);
        content.addView(fileName, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView details = new TextView(this);
        details.setText(durationMs > 0 ? formatDuration(durationMs) + " • Tin nhắn thoại" : "Tệp âm thanh • Tin nhắn thoại");
        details.setTextSize(13);
        details.setPadding(0, dp(5), 0, dp(10));
        content.addView(details);

        Button preview = new Button(this);
        preview.setText("▶  Nghe thử");
        preview.setAllCaps(false);
        content.addView(preview, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        preview.setOnClickListener(view -> togglePreview(uri, preview));

        statusView = new TextView(this);
        statusView.setText("Nhạc sẽ được gửi dưới dạng bong bóng âm thanh trong Messenger.");
        statusView.setTextSize(12);
        statusView.setPadding(0, dp(10), 0, 0);
        content.addView(statusView);

        confirmDialog = new AlertDialog.Builder(this)
                .setTitle("Gửi nhạc dưới dạng tin nhắn thoại")
                .setView(content)
                .setNegativeButton("Hủy", (dialog, which) -> {
                    stopPreview();
                    clearSender();
                    finish();
                })
                .setPositiveButton("Gửi", null)
                .create();
        confirmDialog.setOnCancelListener(dialog -> {
            stopPreview();
            clearSender();
            finish();
        });
        confirmDialog.setOnShowListener(dialog -> {
            sendButton = confirmDialog.getButton(AlertDialog.BUTTON_POSITIVE);
            sendButton.setOnClickListener(view -> beginSend(uri, durationMs));
        });
        confirmDialog.show();
    }

    private void togglePreview(Uri uri, Button button) {
        try {
            if (previewPlayer != null && previewPlayer.isPlaying()) {
                previewPlayer.pause();
                button.setText("▶  Nghe thử");
                return;
            }
            if (previewPlayer != null) {
                previewPlayer.start();
                button.setText("Ⅱ  Tạm dừng");
                return;
            }
            previewPlayer = new MediaPlayer();
            previewPlayer.setDataSource(this, uri);
            previewPlayer.setOnPreparedListener(player -> {
                player.start();
                button.setText("Ⅱ  Tạm dừng");
            });
            previewPlayer.setOnCompletionListener(player -> button.setText("▶  Nghe thử"));
            previewPlayer.setOnErrorListener((player, what, extra) -> {
                button.setText("▶  Nghe thử");
                stopPreview();
                Toast.makeText(this, "Không phát thử được tệp này.", Toast.LENGTH_SHORT).show();
                return true;
            });
            button.setText("Đang tải...");
            previewPlayer.prepareAsync();
        } catch (Throwable error) {
            stopPreview();
            button.setText("▶  Nghe thử");
            Toast.makeText(this, "Không phát thử được tệp này.", Toast.LENGTH_SHORT).show();
        }
    }

    private void beginSend(Uri uri, long durationMs) {
        final Object sender = pendingSender.get();
        if (sender == null) {
            Toast.makeText(this, "Phiên gửi âm thanh đã hết. Hãy mở lại bảng ghi âm.", Toast.LENGTH_LONG).show();
            clearSender();
            finish();
            return;
        }
        if (sendButton != null) sendButton.setEnabled(false);
        statusView.setText("Đang chuẩn bị sóng âm và chuyển cho Messenger...");
        stopPreview();

        Thread worker = new Thread(() -> {
            try {
                long actualDuration = durationMs > 0 ? durationMs : durationMillis(uri);
                if (actualDuration <= 0) actualDuration = 1000;
                List<Double> waveform = extractWaveform(uri, actualDuration);
                Object resource = createNativeMediaResource(uri, actualDuration, waveform);
                runOnUiThread(() -> {
                    try {
                        sendThroughMessenger(sender, resource);
                        Toast.makeText(this, "Đã chuyển tin nhắn âm thanh cho Messenger.", Toast.LENGTH_LONG).show();
                        clearSender();
                        if (confirmDialog != null) confirmDialog.dismiss();
                        finish();
                    } catch (Throwable error) {
                        showSendError(error);
                    }
                });
            } catch (Throwable error) {
                runOnUiThread(() -> showSendError(error));
            }
        }, "HushMessenger-AudioPrepare");
        worker.start();
    }

    private void showSendError(Throwable error) {
        android.util.Log.e("HushMessengerAudio", "Native voice-message handoff failed", error);
        if (statusView != null) statusView.setText("Messenger chưa nhận được tệp này. Hãy thử một tệp MP3, M4A hoặc WAV khác.");
        if (sendButton != null) sendButton.setEnabled(true);
        Toast.makeText(this, "Không gửi được âm thanh. Hãy thử tệp khác.", Toast.LENGTH_LONG).show();
    }

    private Object createNativeMediaResource(Uri uri, long durationMs, List<Double> rawWaveform) throws Exception {
        Class<?> builderClass = Class.forName(MEDIA_RESOURCE_BUILDER);
        Object builder = builderClass.getDeclaredConstructor().newInstance();

        Method setUri = builderClass.getDeclaredMethod("A00", Uri.class);
        setUri.setAccessible(true);
        setUri.invoke(builder, uri);

        Object audioType = staticValue("LX.7oe", "A04");
        Object composerLongPress = staticValue("LX.8yq", "A07");
        Object sendSurface = staticValue("LX.8yt", "A09");
        Object captureOrigin = staticValue("LX.8yu", "A02");

        Class<?> surfaceClass = Class.forName("LX.8yt");
        Class<?> originClass = Class.forName("LX.8yu");
        Class<?> sourceClass = Class.forName(MEDIA_RESOURCE_SEND_SOURCE);
        Constructor<?> sourceConstructor = sourceClass.getDeclaredConstructor(surfaceClass, originClass);
        sourceConstructor.setAccessible(true);
        Object sendSource = sourceConstructor.newInstance(sendSurface, captureOrigin);

        Class<?> waveformUtil = Class.forName("LX.93h");
        Method normalize = waveformUtil.getDeclaredMethod("A02", List.class, boolean.class);
        normalize.setAccessible(true);
        Object amplitudes = normalize.invoke(null, rawWaveform, true);

        Field intervalField = Class.forName("LX.5ua").getDeclaredField("A00");
        intervalField.setAccessible(true);
        int waveformInterval = intervalField.getInt(null);

        Class<?> immutableListClass = Class.forName(IMMUTABLE_LIST);
        Class<?> waveformClass = Class.forName(WAVEFORM_DATA);
        Constructor<?> waveformConstructor = waveformClass.getDeclaredConstructor(immutableListClass, int.class);
        waveformConstructor.setAccessible(true);
        Object waveformData = waveformConstructor.newInstance(amplitudes, waveformInterval);

        setField(builder, "A0R", audioType);
        setField(builder, "A0P", composerLongPress);
        setField(builder, "A0Z", sendSource);
        setLongField(builder, "A08", durationMs);
        setField(builder, "A0a", waveformData);

        Class<?> mediaResourceClass = Class.forName(MEDIA_RESOURCE);
        Constructor<?> resourceConstructor = mediaResourceClass.getDeclaredConstructor(builderClass);
        resourceConstructor.setAccessible(true);
        return resourceConstructor.newInstance(builder);
    }

    private static void sendThroughMessenger(Object sender, Object mediaResource) throws Exception {
        Class<?> senderClass = Class.forName("LX.V3J");
        if (!senderClass.isInstance(sender)) throw new IllegalStateException("The voice-message sender is unavailable.");
        Class<?> resourceClass = Class.forName(MEDIA_RESOURCE);
        Method send = senderClass.getDeclaredMethod("A02", senderClass, resourceClass);
        send.setAccessible(true);
        try {
            send.invoke(null, sender, mediaResource);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw new IllegalStateException("Messenger rejected the audio resource.", cause);
        }
    }

    private static Object staticValue(String className, String fieldName) throws Exception {
        Field field = Class.forName(className).getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(null);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void setLongField(Object target, String name, long value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.setLong(target, value);
    }

    private long durationMillis(Uri uri) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(this, uri);
            String value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (value != null) return Long.parseLong(value);
        } catch (Throwable ignored) {
        } finally {
            try { retriever.release(); } catch (Throwable ignored) { }
        }
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(this, uri, null);
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                if (format.containsKey(MediaFormat.KEY_MIME)
                        && format.getString(MediaFormat.KEY_MIME).startsWith("audio/")
                        && format.containsKey(MediaFormat.KEY_DURATION)) {
                    return format.getLong(MediaFormat.KEY_DURATION) / 1000L;
                }
            }
        } catch (Throwable ignored) {
        } finally {
            try { extractor.release(); } catch (Throwable ignored) { }
        }
        return 0;
    }

    private String displayName(Uri uri) {
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String name = cursor.getString(index);
                    if (name != null && !name.trim().isEmpty()) return name;
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (cursor != null) cursor.close();
        }
        String fallback = uri.getLastPathSegment();
        return fallback == null || fallback.isEmpty() ? "Tệp âm thanh" : fallback;
    }

    private List<Double> extractWaveform(Uri uri, long durationMs) {
        try {
            return decodeWaveform(uri, durationMs);
        } catch (Throwable error) {
            android.util.Log.w("HushMessengerAudio", "Audio decoder unavailable; using a bounded fallback waveform", error);
            return fallbackWaveform(uri);
        }
    }

    private List<Double> decodeWaveform(Uri uri, long durationMs) throws Exception {
        double[] sums = new double[WAVEFORM_BARS];
        int[] counts = new int[WAVEFORM_BARS];
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec decoder = null;
        boolean decoderStarted = false;
        try {
            extractor.setDataSource(this, uri, null);
            int audioTrack = -1;
            MediaFormat inputFormat = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat candidate = extractor.getTrackFormat(i);
                if (candidate.containsKey(MediaFormat.KEY_MIME)
                        && candidate.getString(MediaFormat.KEY_MIME).startsWith("audio/")) {
                    audioTrack = i;
                    inputFormat = candidate;
                    break;
                }
            }
            if (audioTrack < 0 || inputFormat == null) throw new IllegalArgumentException("No audio track.");
            extractor.selectTrack(audioTrack);
            String mime = inputFormat.getString(MediaFormat.KEY_MIME);
            decoder = MediaCodec.createDecoderByType(mime);
            decoder.configure(inputFormat, null, null, 0);
            decoder.start();
            decoderStarted = true;

            int sampleRate = inputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                    ? inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
            int channels = inputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;
            int pcmEncoding = AudioFormat.ENCODING_PCM_16BIT;
            long durationUs = Math.min(Math.max(1L, durationMs), MAX_WAVEFORM_DECODE_MS) * 1000L;
            if (durationMs <= 0 && inputFormat.containsKey(MediaFormat.KEY_DURATION)) {
                durationUs = inputFormat.getLong(MediaFormat.KEY_DURATION);
            }

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean outputDone = false;
            int guard = 0;
            while (!outputDone && guard++ < 12000) {
                if (!inputDone) {
                    int inputIndex = decoder.dequeueInputBuffer(10000);
                    if (inputIndex >= 0) {
                        ByteBuffer input = decoder.getInputBuffer(inputIndex);
                        if (input == null) throw new IllegalStateException("Decoder input buffer unavailable.");
                        input.clear();
                        int size = extractor.readSampleData(input, 0);
                        if (size < 0) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            long sampleTime = extractor.getSampleTime();
                            decoder.queueInputBuffer(inputIndex, 0, size, Math.max(0L, sampleTime), 0);
                            extractor.advance();
                        }
                    }
                }

                int outputIndex = decoder.dequeueOutputBuffer(info, 10000);
                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat outputFormat = decoder.getOutputFormat();
                    if (outputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        sampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    }
                    if (outputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        channels = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    }
                    if (outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        pcmEncoding = outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING);
                    }
                } else if (outputIndex >= 0) {
                    ByteBuffer output = decoder.getOutputBuffer(outputIndex);
                    if (output != null && info.size > 0 && sampleRate > 0 && channels > 0) {
                        output.position(info.offset);
                        output.limit(info.offset + info.size);
                        output.order(ByteOrder.LITTLE_ENDIAN);
                        accumulatePcm(output, info.presentationTimeUs, sampleRate, channels,
                                pcmEncoding, durationUs, sums, counts);
                    }
                    boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    decoder.releaseOutputBuffer(outputIndex, false);
                    if (eos) outputDone = true;
                } else if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone) {
                    // Continue until the decoder emits its end-of-stream buffer.
                }
            }
        } finally {
            if (decoder != null) {
                if (decoderStarted) {
                    try { decoder.stop(); } catch (Throwable ignored) { }
                }
                try { decoder.release(); } catch (Throwable ignored) { }
            }
            try { extractor.release(); } catch (Throwable ignored) { }
        }

        ArrayList<Double> result = new ArrayList<>(WAVEFORM_BARS);
        for (int i = 0; i < WAVEFORM_BARS; i++) {
            double value = counts[i] == 0 ? 0.035 : Math.sqrt(sums[i] / counts[i]);
            result.add(Math.max(0.015, Math.min(1.0, value)));
        }
        return result;
    }

    private static void accumulatePcm(ByteBuffer pcm, long presentationTimeUs, int sampleRate,
                                      int channels, int encoding, long durationUs,
                                      double[] sums, int[] counts) {
        int bytesPerSample;
        if (encoding == AudioFormat.ENCODING_PCM_FLOAT || encoding == AudioFormat.ENCODING_PCM_32BIT) {
            bytesPerSample = 4;
        } else if (encoding == AudioFormat.ENCODING_PCM_8BIT) {
            bytesPerSample = 1;
        } else if (encoding == AudioFormat.ENCODING_PCM_24BIT_PACKED) {
            bytesPerSample = 3;
        } else {
            bytesPerSample = 2;
        }
        int frameSize = bytesPerSample * channels;
        int frames = pcm.remaining() / frameSize;
        for (int frame = 0; frame < frames; frame++) {
            double amplitude = 0;
            for (int channel = 0; channel < channels; channel++) {
                double sample;
                if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                    sample = pcm.getFloat();
                } else if (encoding == AudioFormat.ENCODING_PCM_32BIT) {
                    sample = pcm.getInt() / 2147483648.0;
                } else if (encoding == AudioFormat.ENCODING_PCM_24BIT_PACKED) {
                    int b0 = pcm.get() & 0xff;
                    int b1 = pcm.get() & 0xff;
                    int b2 = pcm.get();
                    int value = (b2 << 16) | (b1 << 8) | b0;
                    sample = value / 8388608.0;
                } else if (encoding == AudioFormat.ENCODING_PCM_8BIT) {
                    sample = ((pcm.get() & 0xff) - 128) / 128.0;
                } else {
                    sample = pcm.getShort() / 32768.0;
                }
                amplitude += Math.abs(sample);
            }
            amplitude /= channels;
            long timeUs = presentationTimeUs + (frame * 1000000L / sampleRate);
            int bin = durationUs > 0
                    ? (int) Math.min(WAVEFORM_BARS - 1, Math.max(0, timeUs * WAVEFORM_BARS / durationUs))
                    : Math.min(WAVEFORM_BARS - 1, frame * WAVEFORM_BARS / Math.max(1, frames));
            sums[bin] += Math.min(1.0, amplitude);
            counts[bin]++;
        }
    }

    private List<Double> fallbackWaveform(Uri uri) {
        double[] sums = new double[WAVEFORM_BARS];
        int[] counts = new int[WAVEFORM_BARS];
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IllegalStateException("Audio stream unavailable.");
            byte[] buffer = new byte[8192];
            long consumed = 0;
            final long limit = 512L * 1024L;
            int read;
            while (consumed < limit && (read = input.read(buffer, 0, (int) Math.min(buffer.length, limit - consumed))) > 0) {
                for (int i = 0; i < read; i++) {
                    int bin = (int) Math.min(WAVEFORM_BARS - 1, (consumed + i) * WAVEFORM_BARS / limit);
                    sums[bin] += Math.abs((buffer[i] & 0xff) - 128) / 128.0;
                    counts[bin]++;
                }
                consumed += read;
            }
        } catch (Throwable ignored) {
        }
        ArrayList<Double> result = new ArrayList<>(WAVEFORM_BARS);
        for (int i = 0; i < WAVEFORM_BARS; i++) {
            double value = counts[i] == 0 ? 0.08 : Math.sqrt(sums[i] / counts[i]);
            result.add(Math.max(0.02, Math.min(1.0, value)));
        }
        return result;
    }

    private void stopPreview() {
        if (previewPlayer != null) {
            try { previewPlayer.stop(); } catch (Throwable ignored) { }
            try { previewPlayer.release(); } catch (Throwable ignored) { }
            previewPlayer = null;
        }
    }

    private void clearSender() {
        pendingSender = new WeakReference<>(null);
    }

    @Override
    protected void onDestroy() {
        stopPreview();
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String formatDuration(long durationMs) {
        long totalSeconds = Math.max(0, durationMs / 1000L);
        return String.format(Locale.getDefault(), "%d:%02d", totalSeconds / 60L, totalSeconds % 60L);
    }
}
