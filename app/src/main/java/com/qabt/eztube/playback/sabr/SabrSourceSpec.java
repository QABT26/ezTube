package com.qabt.eztube.playback.sabr;

import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrInfo;
import org.schabi.newpipe.extractor.services.youtube.sabr.media.SabrMediaSegment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

public final class SabrSourceSpec {
    private final String videoId;
    private final YoutubeSabrInfo info;
    private final YoutubeSabrInfo.Format bootstrapAudioFormat;
    private final List<YoutubeSabrInfo.Format> audioFormats;
    private final List<YoutubeSabrInfo.Format> videoFormats;
    private final YoutubeSabrInfo.Format bootstrapVideoFormat;
    private final Map<String, YoutubeSabrInfo.Format> formatsByKey;
    private final Map<YoutubeSabrInfo.Format, String> keysByFormat;
    private final AtomicReference<byte[]> audioInitializationData = new AtomicReference<>();
    private final AtomicReference<byte[]> videoInitializationData = new AtomicReference<>();
    private final AtomicReference<List<SabrMediaSegment>> bootstrapMediaSegments;

    public SabrSourceSpec(
            String videoId,
            YoutubeSabrInfo info,
            YoutubeSabrInfo.Format bootstrapAudioFormat,
            List<YoutubeSabrInfo.Format> audioFormats,
            List<YoutubeSabrInfo.Format> videoFormats,
            YoutubeSabrInfo.Format bootstrapVideoFormat
    ) {
        if (audioFormats.isEmpty() || !audioFormats.contains(bootstrapAudioFormat)) {
            throw new IllegalArgumentException("SABR audio group is empty");
        }
        if (videoFormats.isEmpty() || !videoFormats.contains(bootstrapVideoFormat)) {
            throw new IllegalArgumentException("SABR video group is empty");
        }
        this.videoId = videoId;
        this.info = info;
        this.bootstrapAudioFormat = bootstrapAudioFormat;
        this.audioFormats = Collections.unmodifiableList(new ArrayList<>(audioFormats));
        this.videoFormats = Collections.unmodifiableList(new ArrayList<>(videoFormats));
        this.bootstrapVideoFormat = bootstrapVideoFormat;

        final Map<String, YoutubeSabrInfo.Format> byKey = new LinkedHashMap<>();
        final Map<YoutubeSabrInfo.Format, String> byFormat = new ConcurrentHashMap<>();
        for (int i = 0; i < videoFormats.size(); i++) {
            final String key = "v" + i;
            byKey.put(key, videoFormats.get(i));
            byFormat.put(videoFormats.get(i), key);
        }
        for (int i = 0; i < audioFormats.size(); i++) {
            final String key = "a" + i;
            byKey.put(key, audioFormats.get(i));
            byFormat.put(audioFormats.get(i), key);
        }
        formatsByKey = Collections.unmodifiableMap(byKey);
        keysByFormat = Collections.unmodifiableMap(byFormat);
        bootstrapMediaSegments = new AtomicReference<>(Collections.emptyList());
    }

    public String getVideoId() { return videoId; }
    public YoutubeSabrInfo getInfo() { return info; }
    public YoutubeSabrInfo.Format getBootstrapAudioFormat() { return bootstrapAudioFormat; }
    public List<YoutubeSabrInfo.Format> getAudioFormats() { return audioFormats; }
    public List<YoutubeSabrInfo.Format> getVideoFormats() { return videoFormats; }
    public YoutubeSabrInfo.Format getBootstrapVideoFormat() { return bootstrapVideoFormat; }

    YoutubeSabrInfo.Format getFormat(String key) { return formatsByKey.get(key); }

    String getFormatKey(YoutubeSabrInfo.Format format) {
        final String key = keysByFormat.get(format);
        if (key == null) throw new IllegalArgumentException("Unknown SABR format");
        return key;
    }

    byte[] getInitializationData(YoutubeSabrInfo.Format format) {
        final byte[] data = format.isAudio()
                ? audioInitializationData.get()
                : format.isVideo() ? videoInitializationData.get() : null;
        return data == null ? null : data.clone();
    }

    void putInitializationData(YoutubeSabrInfo.Format format, byte[] data) {
        if (format.isAudio()) audioInitializationData.compareAndSet(null, data.clone());
        else if (format.isVideo()) videoInitializationData.compareAndSet(null, data.clone());
    }

    long getDurationMs() {
        return Math.max(
                bootstrapAudioFormat.getApproxDurationMs(),
                bootstrapVideoFormat.getApproxDurationMs()
        );
    }

    List<SabrMediaSegment> takeBootstrapMediaSegments() {
        return bootstrapMediaSegments.getAndSet(Collections.emptyList());
    }
}
