package com.google.android.exoplayer2.ext.okhttp;

import android.net.Uri;
import android.os.ConditionVariable;
import android.os.SystemClock;
import android.support.annotation.Nullable;
import android.util.Log;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ExoPlayerLibraryInfo;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.upstream.BaseDataSource;
import com.google.android.exoplayer2.upstream.DataSourceException;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.HttpDataSource;
import com.google.android.exoplayer2.util.Assertions;
import com.google.android.exoplayer2.util.Predicate;
import com.google.android.exoplayer2.util.Util;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.text.DecimalFormat;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import okhttp3.CacheControl;
import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import okio.Pipe;

import static com.google.android.exoplayer2.util.Util.castNonNull;

public class WebSocketDataSource extends BaseDataSource implements HttpDataSource {

    static {
        ExoPlayerLibraryInfo.registerModule("goog.exo.okhttp");
    }

    private static String TAG = "WebSocketDataSource";

    private ConditionVariable isOpenCV = new ConditionVariable();
    private ConditionVariable isAudioReady = new ConditionVariable();

    private static final byte[] SKIP_BUFFER = new byte[4096];

    private final OkHttpClient callFactory;
    private final RequestProperties requestProperties;

    private final @Nullable
    String userAgent;
    private final @Nullable
    Predicate<String> contentTypePredicate;
    private final @Nullable
    CacheControl cacheControl;
    private final @Nullable
    RequestProperties defaultRequestProperties;

    private @Nullable
    DataSpec dataSpec;
    private @Nullable
    Response response;
    ResponseBody responseBody;
    private @Nullable
    InputStream responseByteStream;
    private boolean opened;

    private long bytesToSkip;
    private long bytesToRead;

    private long bytesSkipped;
    private long bytesRead;

    String initId = "298665506";
    String sessionKey = "376m3bv1pkvgczfcscalwul558bcgtvn";
    int screenWidth = 1440;
    int screenHeight = 900;

    int mediaLen = 0;
    byte[] mediadata = null;
    PipedInputStream mediaDataInputStream = new PipedInputStream(16000000); // 655350; // 15 * 1MB =
    PipedOutputStream mediaDataOutputStream = new PipedOutputStream();

    long bufferedTimeline = 0;
    long cumRebufferMs = 0;
    int state = 0;
    String videoBuffer = null;
    String audioBuffer = null;
    String cumRebuffer = null;
    String videoTimeline = null;
    DecimalFormat df = new DecimalFormat("0.000");

    private File logFile = null;
    private FileOutputStream fos = null;
    private BufferedWriter logStream = null;

    SimpleExoPlayer player;

    /**
     * @param callFactory          A {@link OkHttpClient} (typically an {@link okhttp3.OkHttpClient}) for use
     *                             by the source.
     * @param userAgent            An optional User-Agent string.
     * @param contentTypePredicate An optional {@link Predicate}. If a content type is rejected by the
     *                             predicate then a {@link InvalidContentTypeException} is thrown from {@link
     *                             #open(DataSpec)}.
     */
    public WebSocketDataSource(
            OkHttpClient callFactory,
            @Nullable String userAgent,
            @Nullable Predicate<String> contentTypePredicate) {
        this(
                callFactory,
                userAgent,
                contentTypePredicate,
                /* cacheControl= */ null,
                /* defaultRequestProperties= */ null);
    }

    /**
     * @param callFactory              A {@link OkHttpClient} (typically an {@link okhttp3.OkHttpClient}) for use
     *                                 by the source.
     * @param userAgent                An optional User-Agent string.
     * @param contentTypePredicate     An optional {@link Predicate}. If a content type is rejected by the
     *                                 predicate then a {@link InvalidContentTypeException} is thrown from {@link
     *                                 #open(DataSpec)}.
     * @param cacheControl             An optional {@link CacheControl} for setting the Cache-Control header.
     * @param defaultRequestProperties The optional default {@link RequestProperties} to be sent to
     *                                 the server as HTTP headers on every request.
     */
    public WebSocketDataSource(
            OkHttpClient callFactory,
            @Nullable String userAgent,
            @Nullable Predicate<String> contentTypePredicate,
            @Nullable CacheControl cacheControl,
            @Nullable RequestProperties defaultRequestProperties) {
        super(/* isNetwork= */ true);
        this.callFactory = Assertions.checkNotNull(callFactory);
        this.userAgent = userAgent;
        this.contentTypePredicate = contentTypePredicate;
        this.cacheControl = cacheControl;
        this.defaultRequestProperties = defaultRequestProperties;
        this.requestProperties = new RequestProperties();
    }

    public WebSocketDataSource(
            OkHttpClient callFactory,
            @Nullable String userAgent,
            @Nullable Predicate<String> contentTypePredicate,
            @Nullable CacheControl cacheControl,
            SimpleExoPlayer player,
            @Nullable RequestProperties defaultRequestProperties) {
        super(/* isNetwork= */ true);
        this.callFactory = Assertions.checkNotNull(callFactory);
        this.userAgent = userAgent;
        this.contentTypePredicate = contentTypePredicate;
        this.cacheControl = cacheControl;
        this.defaultRequestProperties = defaultRequestProperties;
        this.requestProperties = new RequestProperties();
        this.player = player;

        logFile = new File("/sdcard/", "log-exoplayer-net-" + SystemClock.elapsedRealtime() + ".txt");
        try {
            fos = new FileOutputStream(logFile);
            logStream = new BufferedWriter(new OutputStreamWriter(fos));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public @Nullable
    Uri getUri() {
        return response == null ? null : Uri.parse(response.request().url().toString());
    }

    @Override
    public Map<String, List<String>> getResponseHeaders() {
        return response == null ? Collections.emptyMap() : response.headers().toMultimap();
    }

    @Override
    public void setRequestProperty(String name, String value) {
        Assertions.checkNotNull(name);
        Assertions.checkNotNull(value);
        requestProperties.set(name, value);
    }

    @Override
    public void clearRequestProperty(String name) {
        Assertions.checkNotNull(name);
        requestProperties.remove(name);
    }

    @Override
    public void clearAllRequestProperties() {
        requestProperties.clear();
    }

    @Override
    public long open(DataSpec dataSpec) throws HttpDataSourceException {
        Log.e(TAG, "Jiayi: open " + Thread.currentThread().getId());

        try {
            mediaDataInputStream.connect(mediaDataOutputStream);
        } catch (Exception e) {
            e.printStackTrace();
        }

        this.dataSpec = dataSpec;
        this.bytesRead = 0;
        this.bytesSkipped = 0;
        transferInitializing(dataSpec);

        String in = "{\"initId\":" + initId +
                ",\"sessionKey\": \"" + sessionKey + "\"" +
                ",\"userName\":\"test\",\"channel\":\"cbs\",\"os\":\"Mac OS X\",\"browser\":\"Chrome\"" +
                ",\"screenWidth\":" + Integer.toString(screenWidth) +
                ",\"screenHeight\":" + Integer.toString(screenHeight) + ",\"type\":\"client-init\"}";

        // Log.e(TAG, "Jiayi: in open " + Long.toString(dataSpec.position) + ", " + Long.toString(dataSpec.length));

        // Request request = makeRequest(dataSpec);
        Request request = new Request.Builder().url(dataSpec.uri.toString())
                .addHeader("Origin", dataSpec.uri.toString())
                .build();

        WebSocketListener webSocketListener = new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response _response) {
                super.onOpen(webSocket, _response);
                webSocket.send(in);
                response = _response;
                responseBody = Assertions.checkNotNull(response.body());
                responseByteStream = responseBody.byteStream();

                try {
                    logStream.write("{\"time\":" + SystemClock.elapsedRealtime() + ", \"type\":\"server-init\"}");
                    logStream.newLine();
                } catch (Exception e) {
                    e.printStackTrace();
                }

                state = 1;

                Thread timerUpdate = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        double vbs = 0.0;
                        while (true) {
                            try {
                                Thread.sleep(250);
                            } catch (Exception e) {
                                e.printStackTrace();
                            }

                            vbs = 1.0 * (bufferedTimeline - player.getCurrentPosition()) / 1000.0;
                            if (vbs >= 7) continue;

                            send_client_info(webSocket, "timer");
                        }
                    }
                });

                Thread checkRebuffering = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        long rebuffer_start_ms = 0;
                        long last_rebuffer_ms = 0;
                        long curr_ms = 0;
                        while (true) {
                            try {
                                Thread.sleep(50);
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                            curr_ms = System.currentTimeMillis();
                            // player is rebuffering
                            if (player.getPlaybackState() == 2)  {
                                if (rebuffer_start_ms == 0) {
                                    send_client_info(webSocket, "rebuffer");
                                    rebuffer_start_ms = curr_ms;
                                }
                                if (last_rebuffer_ms != 0) cumRebufferMs += curr_ms - last_rebuffer_ms;
                                last_rebuffer_ms = curr_ms;
                            } else {
                                if (rebuffer_start_ms != 0) send_client_info(webSocket, "play");
                                rebuffer_start_ms = 0;
                                last_rebuffer_ms = 0;
                            }
                        }
                    }
                });

                timerUpdate.start();
                checkRebuffering.start();
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                Log.i(TAG, "Jiayi: onMessage (string) with sizeof data " + text.length() + "; state " + Integer.toString(state));
            }

            @Override
            public void onMessage(WebSocket webSocket, ByteString bytes) {
                byte[] data = bytes.toByteArray();

                int metadataLen = 0;
                byte[] raw_metadata = null;

                String msgType = null;

                String channel = null;
                String format = null;
                int timestamp = 0;
                int byteOffset = 0;
                int totalByteLength = 0;
                int byteLength = 0;
                boolean isLastFragReceived = false;
                double ssim = 0.0;
                if (state == 1) {
                    String s = new String(data);
                    if (!s.contains("server-init")) {
                        Log.e(TAG + " Jiayi", "state = 1 != server-init");
                    }

                    send_client_info(webSocket, "startup");
                    state = 2;
                } else if (state == 2) {
                    metadataLen = 0;
                    for (int i = 0; i < 2; i++)
                        metadataLen = metadataLen * 10 + Byte.toUnsignedInt(data[i]);
                    // Log.i(TAG + " Jiayi", Integer.toString(metadataLen));

                    raw_metadata = new byte[metadataLen];
                    System.arraycopy(data, 2, raw_metadata, 0, metadataLen);

                    // Log.i(TAG + " Jiayi", new String(raw_metadata));
                    JSONObject metadataObject = null;
                    try {
                        metadataObject = new JSONObject(new String(raw_metadata));
                        msgType = metadataObject.getString("type");
                        byteOffset = metadataObject.getInt("byteOffset");
                        totalByteLength = metadataObject.getInt("totalByteLength");
                        byteLength = data.length - metadataLen - 2;
                        isLastFragReceived = ((byteOffset + byteLength) == totalByteLength);
                        if (isLastFragReceived) bufferedTimeline += 2002;
                        if (msgType.contains("server-video") || msgType.contains("server-audio")) {
                            channel = metadataObject.getString("channel");
                            format = metadataObject.getString("format");
                            timestamp = metadataObject.getInt("timestamp");
                        }
                        if (msgType.contains("server-video"))
                            ssim = metadataObject.getDouble("ssim");
                        // Log.i(TAG + " Jiayi", msgType + ", receive last fragment " + isLastFragReceived + ", " + byteOffset
                        //        + ", " + byteLength + ", " + totalByteLength);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }

                    if (msgType.contains("server-video")) { // || msgType.contains("server-audio")) {
                        mediaLen = data.length - metadataLen - 2;
                        mediadata = new byte[mediaLen];
                        System.arraycopy(data, 2 + metadataLen, mediadata, 0, mediaLen);
                        try {
                            mediaDataOutputStream.write(mediadata);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }

                    videoBuffer = df.format(1.0 * (bufferedTimeline - player.getCurrentPosition()) / 1000.0);
                    audioBuffer = videoBuffer;
                    cumRebuffer = df.format(1.0 * cumRebufferMs / 1000.0);
                    videoTimeline = df.format(1.0 * player.getCurrentPosition() / 1000.0);
                    // Log.e(TAG, "Jiayi; acked bufferedTimeline " + bufferedTimeline + ", buffer size " + player.getBufferedPosition() + ", " +
                    //         player.getCurrentPosition() + ", " + videoBuffer);

                    // send client ack video
                    String ackMsg = null;
                    if (msgType.contains("server-audio")) {
                        ackMsg = "{\"initId\": " + initId +
                                ", \"videoBuffer\": " + videoBuffer +
                                ", \"audioBuffer\": " + audioBuffer +
                                ", \"cumRebuffer\": " + cumRebuffer +
                                ", \"channel\": \"" + channel + "\"" +
                                ", \"format\": \"" + format + "\"" +
                                ", \"timestamp\": " + timestamp +
                                ", \"byteOffset\": " + byteOffset +
                                ", \"totalByteLength\": " + totalByteLength +
                                ", \"byteLength\": " + byteLength +
                                ", \"type\": \"client-audack\"" +
                                "}";
                        webSocket.send(ackMsg);
                        // Log.i(TAG + " Jiayi", SystemClock.elapsedRealtime() + ": ackMsg: " + ackMsg);
                    }
                    if (msgType.contains("server-video")) {
                        isOpenCV.open();
                        ackMsg = "{\"initId\": " + initId +
                                ", \"videoBuffer\": " + videoBuffer +
                                ", \"audioBuffer\": " + audioBuffer +
                                ", \"cumRebuffer\": " + cumRebuffer +
                                ", \"videoTimeline\": " + videoTimeline +
                                ", \"channel\": \"" + channel + "\"" +
                                ", \"format\": \"" + format + "\"" +
                                ", \"timestamp\": " + timestamp +
                                ", \"byteOffset\": " + byteOffset +
                                ", \"totalByteLength\": " + totalByteLength +
                                ", \"byteLength\": " + byteLength +
                                ", \"ssim\": " + ssim +
                                ", \"type\": \"client-vidack\"" +
                                "}";
                        webSocket.send(ackMsg);
                        // Log.i(TAG + " Jiayi", SystemClock.elapsedRealtime() + ": ackMsg: " + ackMsg);
                    }
                    try {
                        logStream.write("{\"time\":" + SystemClock.elapsedRealtime() + ", \"ack\":" + ackMsg + "}");
                        logStream.newLine();
                        logStream.flush();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }

                } else {
                    /* Server messages are of the form: "metadataLen|metadata_json|data" */
                    ;
                }

            }

            @Override
            public void onClosing(WebSocket webSocket, int code, String reason) {
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                try {
                    logStream.close();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, @Nullable Response response) {
            }

            private void send_client_info(WebSocket webSocket, String eventType) {
                videoBuffer = df.format(1.0 * (bufferedTimeline - player.getCurrentPosition()) / 1000.0);
                audioBuffer = videoBuffer;
                cumRebuffer = df.format(1.0 * cumRebufferMs / 1000.0);
                videoTimeline = df.format(1.0 * player.getCurrentPosition() / 1000.0);

                //Log.e(TAG, "Jiayi; " + eventType + " bufferedTimeline " + bufferedTimeline + ", buffer size " + player.getBufferedPosition() +
                //        ", currPos " + player.getCurrentPosition() + ", videoBuf " + videoBuffer +
                //        ", playState " + player.getPlaybackState());

                // send client rebuffering
                String infoMsg = null;
                infoMsg = "{\"initId\": " + initId +
                        ", \"videoBuffer\": " + videoBuffer +
                        ", \"audioBuffer\": " + audioBuffer +
                        ", \"cumRebuffer\": " + cumRebuffer +
                        ", \"videoTimeline\": " + videoTimeline +
                        ", \"event\": \"" + eventType + "\"" +
                        ", \"screenWidth\": " + Integer.toString(screenWidth) +
                        ", \"screenHeight\": " + Integer.toString(screenHeight) +
                        ", \"event\": 0" +
                        ", \"type\": \"client-info\"" +
                        "}";
                webSocket.send(infoMsg);
                // Log.i(TAG + " Jiayi", "rebuffering infoMsg: " + infoMsg);
            }
        };
        WebSocket webSocket = callFactory.newWebSocket(request, webSocketListener);

        bytesToSkip = 0;
        bytesToRead = -1;

        isOpenCV.block();

        opened = true;
        transferStarted(dataSpec);

        return bytesToRead;
    }

    @Override
    public int read(byte[] buffer, int offset, int readLength) throws HttpDataSourceException {
        // Log.e(TAG, "Jiayi: WebSocket read len " + Integer.toString(readLength));

        try {
            // skipInternal();
            return readInternal(buffer, offset, readLength);
        } catch (IOException e) {
            throw new HttpDataSourceException(
                    e, Assertions.checkNotNull(dataSpec), HttpDataSourceException.TYPE_READ);
        }
    }

    @Override
    public void close() throws HttpDataSourceException {
        if (opened) {
            opened = false;
            transferEnded();
            closeConnectionQuietly();
            try {
                logStream.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Returns the number of bytes that have been skipped since the most recent call to
     * {@link #open(DataSpec)}.
     *
     * @return The number of bytes skipped.
     */
    protected final long bytesSkipped() {
        return bytesSkipped;
    }

    /**
     * Returns the number of bytes that have been read since the most recent call to
     * {@link #open(DataSpec)}.
     *
     * @return The number of bytes read.
     */
    protected final long bytesRead() {
        return bytesRead;
    }

    /**
     * Returns the number of bytes that are still to be read for the current {@link DataSpec}.
     * <p>
     * If the total length of the data being read is known, then this length minus {@code bytesRead()}
     * is returned. If the total length is unknown, {@link C#LENGTH_UNSET} is returned.
     *
     * @return The remaining length, or {@link C#LENGTH_UNSET}.
     */
    protected final long bytesRemaining() {
        return bytesToRead == C.LENGTH_UNSET ? bytesToRead : bytesToRead - bytesRead;
    }

    /**
     * Skips any bytes that need skipping. Else does nothing.
     * <p>
     * This implementation is based roughly on {@code libcore.io.Streams.skipByReading()}.
     *
     * @throws InterruptedIOException If the thread is interrupted during the operation.
     * @throws EOFException           If the end of the input stream is reached before the bytes are skipped.
     */
    private void skipInternal() throws IOException {
        if (bytesSkipped == bytesToSkip) {
            return;
        }

        while (bytesSkipped != bytesToSkip) {
            int readLength = (int) Math.min(bytesToSkip - bytesSkipped, SKIP_BUFFER.length);
            int read = castNonNull(responseByteStream).read(SKIP_BUFFER, 0, readLength);
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException();
            }
            if (read == -1) {
                throw new EOFException();
            }
            bytesSkipped += read;
            bytesTransferred(read);
        }
    }

    /**
     * Reads up to {@code length} bytes of data and stores them into {@code buffer}, starting at
     * index {@code offset}.
     * <p>
     * This method blocks until at least one byte of data can be read, the end of the opened range is
     * detected, or an exception is thrown.
     *
     * @param buffer     The buffer into which the read data should be stored.
     * @param offset     The start offset into {@code buffer} at which data should be written.
     * @param readLength The maximum number of bytes to read.
     * @return The number of bytes read, or {@link C#RESULT_END_OF_INPUT} if the end of the opened
     * range is reached.
     * @throws IOException If an error occurs reading from the source.
     */
    private int readInternal(byte[] buffer, int offset, int readLength) throws IOException {
        if (readLength == 0) {
            return 0;
        }
        if (bytesToRead != C.LENGTH_UNSET) {
            long bytesRemaining = bytesToRead - bytesRead;
            if (bytesRemaining == 0) {
                return C.RESULT_END_OF_INPUT;
            }
            readLength = (int) Math.min(readLength, bytesRemaining);
        }
        // Log.e(TAG, "Jiayi: mediadata len " + Integer.toString(mediaLen) + "; buffer len " + Integer.toString(buffer.length));
        // if (buffer.length < mediaLen) System.arraycopy(mediadata, 0, buffer, 0, buffer.length);
        // else System.arraycopy(mediadata, 0, buffer, 0, mediaLen);
        int read = castNonNull(mediaDataInputStream).read(buffer, offset, readLength);
        if (read == -1) {
            if (bytesToRead != C.LENGTH_UNSET) {
                // End of stream reached having not read sufficient data.
                throw new EOFException();
            }
            return C.RESULT_END_OF_INPUT;
        }

        bytesRead += read;
        bytesTransferred(read);
        return read;
    }

    /**
     * Closes the current connection quietly, if there is one.
     */
    private void closeConnectionQuietly() {
        if (response != null) {
            Assertions.checkNotNull(response.body()).close();
            response = null;
        }
        responseByteStream = null;
    }

}
