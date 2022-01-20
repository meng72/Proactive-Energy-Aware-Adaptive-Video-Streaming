package modem_milab.exoplayer_vs;

import android.Manifest;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.View;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.ExoPlayerFactory;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.RendererCapabilities;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.Timeline;
import com.google.android.exoplayer2.drm.DefaultDrmSessionManager;
import com.google.android.exoplayer2.drm.FrameworkMediaCrypto;
import com.google.android.exoplayer2.ext.okhttp.OkHttpDataSourceFactory;
import com.google.android.exoplayer2.ext.okhttp.WebSocketDataSourceFactory;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.source.dash.DashChunkSource;
import com.google.android.exoplayer2.source.dash.DashMediaSource;
import com.google.android.exoplayer2.source.dash.DefaultDashChunkSource;
import com.google.android.exoplayer2.trackselection.AdaptiveTrackSelection;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.trackselection.MappingTrackSelector;
import com.google.android.exoplayer2.trackselection.MyTrackSelector;
import com.google.android.exoplayer2.trackselection.TrackSelection;
import com.google.android.exoplayer2.trackselection.TrackSelector;
import com.google.android.exoplayer2.trackselection.TrackSelectorResult;
import com.google.android.exoplayer2.ui.PlayerView;
import com.google.android.exoplayer2.ui.spherical.SphericalSurfaceView;
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter;
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSourceFactory;
import com.google.android.exoplayer2.util.Log;
import com.google.android.exoplayer2.util.Util;
import com.google.android.exoplayer2.source.ExtractorMediaSource;
import com.google.android.exoplayer2.video.spherical.CameraMotionListener;

import org.json.JSONObject;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.util.Arrays;

import okhttp3.OkHttpClient;
import tech.gusavila92.websocketclient.WebSocketClient;

public class MainActivity extends AppCompatActivity {

    private static String TAG = "MainActivity";

    private PlayerView playerView;
    private SimpleExoPlayer player;

    private WebSocketClient webSocketClient;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        View decorView = getWindow().getDecorView();
        // Hide the status bar.
        int uiOptions = View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE;
        decorView.setSystemUiVisibility(uiOptions);

        getSupportActionBar().hide();

        playerView = findViewById(R.id.player_view);

        // For 360 VR
        ((SphericalSurfaceView) playerView.getVideoSurfaceView()).setDefaultStereoMode(C.STEREO_MODE_MONO);

        isStoragePermissionGranted();
    }

    @Override
    protected void onStart() {
        super.onStart();
        playerView.onResume();

        DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(this);

        player = ExoPlayerFactory.newSimpleInstance(this,
                renderersFactory, new DefaultTrackSelector());

        playerView.setPlayer(player);
        player.setPlayWhenReady(true);
        player.setRepeatMode(Player.REPEAT_MODE_ONE);

        // HTTP Streaming
        OkHttpClient okHttpClient = new OkHttpClient();

        WebSocketDataSourceFactory okHttpDataSourceFactory = new WebSocketDataSourceFactory(okHttpClient,
                Util.getUserAgent(this, "exo-demo"), player);

        ExtractorMediaSource mediaSource = new ExtractorMediaSource.Factory(okHttpDataSourceFactory)
                .createMediaSource(Uri.parse("ws://192.168.1.8:50001/"));


        player.prepare(mediaSource);

        playerView.hideController();
    }

    @Override
    protected void onStop() {
        super.onStop();
        playerView.onPause();

        playerView.setPlayer(null);
        player.release();
        player = null;
    }

    public boolean isStoragePermissionGranted() {
        if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Permission is granted");
            return true;
        } else {
            Log.e(TAG, "Permission is revoked");
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
            return false;
        }
    }

}
