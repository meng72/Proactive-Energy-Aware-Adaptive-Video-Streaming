package com.google.android.exoplayer2.trackselection;

import android.graphics.Point;
import android.support.annotation.Nullable;
import android.util.Log;
import android.util.Pair;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.RendererCapabilities;
import com.google.android.exoplayer2.RendererConfiguration;
import com.google.android.exoplayer2.source.TrackGroup;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.upstream.BandwidthMeter;
import com.google.android.exoplayer2.util.Assertions;
import com.google.android.exoplayer2.util.Util;

import org.checkerframework.checker.nullness.compatqual.NullableType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public class MyTrackSelector extends DefaultTrackSelector {
    private static String TAG = "MyTrackSelector";

    private final TrackSelection.Factory adaptiveTrackSelectionFactory;
    private final AtomicReference<Parameters> parametersReference;

    private static final float FRACTION_TO_CONSIDER_FULLSCREEN = 0.98f;
    private static final int[] NO_TRACKS = new int[0];

    public MyTrackSelector() {
        this(new MyTrackSelection.Factory());
    }

    public MyTrackSelector(BandwidthMeter bandwidthMeter) {
        this(new MyTrackSelection.Factory(bandwidthMeter));
    }

    public MyTrackSelector(TrackSelection.Factory adaptiveTrackSelectionFactory) {
        this.adaptiveTrackSelectionFactory = adaptiveTrackSelectionFactory;
        parametersReference = new AtomicReference<>(Parameters.DEFAULT);
    }

    /* select only once at the start */
    @Override
    protected @Nullable
    TrackSelection selectVideoTrack (
            TrackGroupArray groups,
            int[][] formatSupports,
            int mixedMimeTypeAdaptationSupports,
            DefaultTrackSelector.Parameters params,
            @Nullable TrackSelection.Factory adaptiveTrackSelectionFactory)
            throws ExoPlaybackException {
        //TrackSelection selection = selectFixedVideoTrack(groups, formatSupports, params);
        TrackSelection selection = selectAdaptiveVideoTrack(groups,
                formatSupports,
                mixedMimeTypeAdaptationSupports,
                params,
                adaptiveTrackSelectionFactory,
                getBandwidthMeter());
        return selection;
    }

    private static @Nullable TrackSelection selectFixedVideoTrack(
            TrackGroupArray groups, int[][] formatSupports, Parameters params) {
        Log.e(TAG, "Jiayi: selectFixedVideoTrack");

        TrackGroup selectedGroup = null;
        int selectedTrackIndex = 0;
        for (int groupIndex = 0; groupIndex < groups.length; groupIndex++) {
            TrackGroup trackGroup = groups.get(groupIndex);
            selectedGroup = trackGroup;
            selectedTrackIndex = 0;
        }
        return selectedGroup == null ? null
                : new FixedTrackSelection(selectedGroup, selectedTrackIndex);
    }

    /*
    private static @Nullable TrackSelection selectAdaptiveVideoTrack(
            TrackGroupArray groups,
            int[][] formatSupport,
            int mixedMimeTypeAdaptationSupports,
            Parameters params,
            TrackSelection.Factory adaptiveTrackSelectionFactory,
            BandwidthMeter bandwidthMeter)
            throws ExoPlaybackException {
        Log.e(TAG, "Jiayi: selectAdaptiveVideoTrack");
        for (int i = 0; i < groups.length; i++) {
            TrackGroup group = groups.get(i);

            ArrayList<Integer> selectedTrackIndices = new ArrayList<>(group.length);
            for (int j = 0; j < group.length; i++) {
                selectedTrackIndices.add(i);
            }
            int[] adaptiveTracks = Util.toArray(selectedTrackIndices);

            if (adaptiveTracks.length > 0) {
                return Assertions.checkNotNull(adaptiveTrackSelectionFactory)
                        .createTrackSelection(group, bandwidthMeter, adaptiveTracks);
            }
        }
        return null;
    }

     */

    private static @Nullable TrackSelection selectAdaptiveVideoTrack(
            TrackGroupArray groups,
            int[][] formatSupport,
            int mixedMimeTypeAdaptationSupports,
            Parameters params,
            TrackSelection.Factory adaptiveTrackSelectionFactory,
            BandwidthMeter bandwidthMeter)
            throws ExoPlaybackException {
        int requiredAdaptiveSupport = params.allowNonSeamlessAdaptiveness
                ? (RendererCapabilities.ADAPTIVE_NOT_SEAMLESS | RendererCapabilities.ADAPTIVE_SEAMLESS)
                : RendererCapabilities.ADAPTIVE_SEAMLESS;
        boolean allowMixedMimeTypes =
                params.allowMixedMimeAdaptiveness
                        && (mixedMimeTypeAdaptationSupports & requiredAdaptiveSupport) != 0;
        for (int i = 0; i < groups.length; i++) {
            TrackGroup group = groups.get(i);
            int[] adaptiveTracks =
                    getAdaptiveVideoTracksForGroup(
                            group,
                            formatSupport[i],
                            allowMixedMimeTypes,
                            requiredAdaptiveSupport,
                            params.maxVideoWidth,
                            params.maxVideoHeight,
                            params.maxVideoFrameRate,
                            params.maxVideoBitrate,
                            params.viewportWidth,
                            params.viewportHeight,
                            params.viewportOrientationMayChange);
            if (adaptiveTracks.length > 0) {
                return Assertions.checkNotNull(adaptiveTrackSelectionFactory)
                        .createTrackSelection(group, bandwidthMeter, adaptiveTracks);
            }
        }
        return null;
    }

    private static int[] getAdaptiveVideoTracksForGroup(
            TrackGroup group,
            int[] formatSupport,
            boolean allowMixedMimeTypes,
            int requiredAdaptiveSupport,
            int maxVideoWidth,
            int maxVideoHeight,
            int maxVideoFrameRate,
            int maxVideoBitrate,
            int viewportWidth,
            int viewportHeight,
            boolean viewportOrientationMayChange) {
        if (group.length < 2) {
            return NO_TRACKS;
        }

        List<Integer> selectedTrackIndices = getViewportFilteredTrackIndices(group, viewportWidth,
                viewportHeight, viewportOrientationMayChange);
        if (selectedTrackIndices.size() < 2) {
            return NO_TRACKS;
        }

        String selectedMimeType = null;
        if (!allowMixedMimeTypes) {
            // Select the mime type for which we have the most adaptive tracks.
            HashSet<@NullableType String> seenMimeTypes = new HashSet<>();
            int selectedMimeTypeTrackCount = 0;
            for (int i = 0; i < selectedTrackIndices.size(); i++) {
                int trackIndex = selectedTrackIndices.get(i);
                String sampleMimeType = group.getFormat(trackIndex).sampleMimeType;
                if (seenMimeTypes.add(sampleMimeType)) {
                    int countForMimeType =
                            getAdaptiveVideoTrackCountForMimeType(
                                    group,
                                    formatSupport,
                                    requiredAdaptiveSupport,
                                    sampleMimeType,
                                    maxVideoWidth,
                                    maxVideoHeight,
                                    maxVideoFrameRate,
                                    maxVideoBitrate,
                                    selectedTrackIndices);
                    if (countForMimeType > selectedMimeTypeTrackCount) {
                        selectedMimeType = sampleMimeType;
                        selectedMimeTypeTrackCount = countForMimeType;
                    }
                }
            }
        }

        // Filter by the selected mime type.
        filterAdaptiveVideoTrackCountForMimeType(
                group,
                formatSupport,
                requiredAdaptiveSupport,
                selectedMimeType,
                maxVideoWidth,
                maxVideoHeight,
                maxVideoFrameRate,
                maxVideoBitrate,
                selectedTrackIndices);

        return selectedTrackIndices.size() < 2 ? NO_TRACKS : Util.toArray(selectedTrackIndices);
    }

    private static int getAdaptiveVideoTrackCountForMimeType(
            TrackGroup group,
            int[] formatSupport,
            int requiredAdaptiveSupport,
            @Nullable String mimeType,
            int maxVideoWidth,
            int maxVideoHeight,
            int maxVideoFrameRate,
            int maxVideoBitrate,
            List<Integer> selectedTrackIndices) {
        int adaptiveTrackCount = 0;
        for (int i = 0; i < selectedTrackIndices.size(); i++) {
            int trackIndex = selectedTrackIndices.get(i);
            if (isSupportedAdaptiveVideoTrack(
                    group.getFormat(trackIndex),
                    mimeType,
                    formatSupport[trackIndex],
                    requiredAdaptiveSupport,
                    maxVideoWidth,
                    maxVideoHeight,
                    maxVideoFrameRate,
                    maxVideoBitrate)) {
                adaptiveTrackCount++;
            }
        }
        return adaptiveTrackCount;
    }

    private static void filterAdaptiveVideoTrackCountForMimeType(
            TrackGroup group,
            int[] formatSupport,
            int requiredAdaptiveSupport,
            @Nullable String mimeType,
            int maxVideoWidth,
            int maxVideoHeight,
            int maxVideoFrameRate,
            int maxVideoBitrate,
            List<Integer> selectedTrackIndices) {
        for (int i = selectedTrackIndices.size() - 1; i >= 0; i--) {
            int trackIndex = selectedTrackIndices.get(i);
            if (!isSupportedAdaptiveVideoTrack(
                    group.getFormat(trackIndex),
                    mimeType,
                    formatSupport[trackIndex],
                    requiredAdaptiveSupport,
                    maxVideoWidth,
                    maxVideoHeight,
                    maxVideoFrameRate,
                    maxVideoBitrate)) {
                selectedTrackIndices.remove(i);
            }
        }
    }

    private static boolean isSupportedAdaptiveVideoTrack(
            Format format,
            @Nullable String mimeType,
            int formatSupport,
            int requiredAdaptiveSupport,
            int maxVideoWidth,
            int maxVideoHeight,
            int maxVideoFrameRate,
            int maxVideoBitrate) {
        return isSupported(formatSupport, false)
                && ((formatSupport & requiredAdaptiveSupport) != 0)
                && (mimeType == null || Util.areEqual(format.sampleMimeType, mimeType))
                && (format.width == Format.NO_VALUE || format.width <= maxVideoWidth)
                && (format.height == Format.NO_VALUE || format.height <= maxVideoHeight)
                && (format.frameRate == Format.NO_VALUE || format.frameRate <= maxVideoFrameRate)
                && (format.bitrate == Format.NO_VALUE || format.bitrate <= maxVideoBitrate);
    }

    private static List<Integer> getViewportFilteredTrackIndices(TrackGroup group, int viewportWidth,
                                                                 int viewportHeight, boolean orientationMayChange) {
        // Initially include all indices.
        ArrayList<Integer> selectedTrackIndices = new ArrayList<>(group.length);
        for (int i = 0; i < group.length; i++) {
            selectedTrackIndices.add(i);
        }

        if (viewportWidth == Integer.MAX_VALUE || viewportHeight == Integer.MAX_VALUE) {
            // Viewport dimensions not set. Return the full set of indices.
            return selectedTrackIndices;
        }
        return selectedTrackIndices;
    }
}
