package com.google.android.exoplayer2.ext.okhttp;

import android.support.annotation.Nullable;

import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.upstream.HttpDataSource;
import com.google.android.exoplayer2.upstream.TransferListener;

import okhttp3.CacheControl;
import okhttp3.Call;
import okhttp3.OkHttpClient;

public final class WebSocketDataSourceFactory extends HttpDataSource.BaseFactory {

    // private final Call.Factory callFactory;
    private OkHttpClient callFactory;

    private final @Nullable
    String userAgent;
    private final @Nullable
    TransferListener listener;
    private final @Nullable
    CacheControl cacheControl;
    SimpleExoPlayer player;

    /**
     * @param callFactory A {@link OkHttpClient} (typically an {@link okhttp3.OkHttpClient}) for use
     *     by the sources created by the factory.
     * @param userAgent An optional User-Agent string.
     */
    public WebSocketDataSourceFactory(OkHttpClient callFactory, @Nullable String userAgent, SimpleExoPlayer player) {
        this(callFactory, userAgent, /* listener= */ null, /* cacheControl= */ null, player);
    }

    /**
     * @param callFactory A {@link OkHttpClient} (typically an {@link okhttp3.OkHttpClient}) for use
     *     by the sources created by the factory.
     * @param userAgent An optional User-Agent string.
     * @param cacheControl An optional {@link CacheControl} for setting the Cache-Control header.
     */
    public WebSocketDataSourceFactory(
            OkHttpClient callFactory, @Nullable String userAgent, @Nullable CacheControl cacheControl, SimpleExoPlayer player) {
        this(callFactory, userAgent, /* listener= */ null, cacheControl, player);
    }

    /**
     * @param callFactory A {@link OkHttpClient} (typically an {@link okhttp3.OkHttpClient}) for use
     *     by the sources created by the factory.
     * @param userAgent An optional User-Agent string.
     * @param listener An optional listener.
     */
    public WebSocketDataSourceFactory(
            OkHttpClient callFactory, @Nullable String userAgent, @Nullable TransferListener listener, SimpleExoPlayer player) {
        this(callFactory, userAgent, listener, /* cacheControl= */ null, player);
    }

    /**
     * @param callFactory A {@link OkHttpClient} (typically an {@link okhttp3.OkHttpClient}) for use
     *     by the sources created by the factory.
     * @param userAgent An optional User-Agent string.
     * @param listener An optional listener.
     * @param cacheControl An optional {@link CacheControl} for setting the Cache-Control header.
     */
    public WebSocketDataSourceFactory(
            OkHttpClient callFactory,
            @Nullable String userAgent,
            @Nullable TransferListener listener,
            @Nullable CacheControl cacheControl,
            SimpleExoPlayer player) {
        this.callFactory = callFactory;
        this.userAgent = userAgent;
        this.listener = listener;
        this.cacheControl = cacheControl;
        this.player = player;
    }

    @Override
    protected WebSocketDataSource createDataSourceInternal(
            HttpDataSource.RequestProperties defaultRequestProperties) {
        WebSocketDataSource dataSource =
                new WebSocketDataSource(
                        callFactory,
                        userAgent,
                        /* contentTypePredicate= */ null,
                        cacheControl,
                        player,
                        defaultRequestProperties);
        if (listener != null) {
            dataSource.addTransferListener(listener);
        }
        return dataSource;
    }
}
