package com.qabt.eztube.playback.sabr;

import android.net.Uri;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.datasource.DataSource;
import androidx.media3.exoplayer.dash.DashMediaSource;
import androidx.media3.exoplayer.dash.DefaultDashChunkSource;
import androidx.media3.exoplayer.dash.manifest.DashManifest;
import androidx.media3.exoplayer.dash.manifest.DashManifestParser;
import androidx.media3.exoplayer.source.MediaSource;

import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrFormatTimeline;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrInfo;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrSession;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

public final class SabrMediaSourceFactory {
    private SabrMediaSourceFactory() {
    }

    public static MediaSource create(
            MediaItem mediaItem,
            SabrSourceSpec spec,
            long initialPositionMs
    ) throws IOException {
        final YoutubeSabrSession session =
                new YoutubeSabrSession(spec.getInfo());

        final byte[] poToken = spec.getInfo().getPoToken();
        if (poToken != null && poToken.length > 0) {
            session.setPoToken(poToken);
        }

        final SabrMediaBridge bridge = new SabrMediaBridge(session, spec);
        bridge.seedSegments(spec.takeBootstrapMediaSegments());

        try {
            bridge.prepareTimelines(Math.max(0L, initialPositionMs));
        } catch (ExtractionException error) {
            bridge.stop();
            throw new IOException("Could not prepare SABR timelines", error);
        }

        final DashManifest manifest = buildManifest(
                spec,
                spec.getDurationMs(),
                bridge
        );

        final DataSource.Factory segmentFactory =
                () -> new SabrSegmentDataSource(spec, bridge);

        return new DashMediaSource.Factory(
                new DefaultDashChunkSource.Factory(segmentFactory),
                segmentFactory
        )
                .setLoadErrorHandlingPolicy(new SabrLoadErrorHandlingPolicy())
                .createMediaSource(manifest, mediaItem);
    }

    private static DashManifest buildManifest(
            SabrSourceSpec spec,
            long durationMs,
            SabrMediaBridge bridge
    ) throws IOException {
        final String mpd =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                        + "<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" type=\"static\" "
                        + "profiles=\"urn:mpeg:dash:profile:isoff-on-demand:2011\" "
                        + "minBufferTime=\"PT0.8S\" mediaPresentationDuration=\""
                        + formatDuration(durationMs) + "\">"
                        + "<Period id=\"0\" start=\"PT0S\">"
                        + videoAdaptationSet(spec, bridge)
                        + audioAdaptationSet(spec, bridge)
                        + "</Period></MPD>";

        return new DashManifestParser().parse(
                Uri.parse("sabr://" + spec.getVideoId()),
                new ByteArrayInputStream(mpd.getBytes(StandardCharsets.UTF_8))
        );
    }

    private static String videoAdaptationSet(
            SabrSourceSpec spec,
            SabrMediaBridge bridge
    ) {
        return adaptationSet(
                spec,
                bridge,
                spec.getVideoFormats(),
                false,
                "0"
        );
    }

    private static String audioAdaptationSet(
            SabrSourceSpec spec,
            SabrMediaBridge bridge
    ) {
        return adaptationSet(
                spec,
                bridge,
                spec.getAudioFormats(),
                true,
                "1"
        );
    }

    private static String adaptationSet(
            SabrSourceSpec spec,
            SabrMediaBridge bridge,
            List<YoutubeSabrInfo.Format> formats,
            boolean audio,
            String id
    ) {
        final YoutubeSabrInfo.Format first = formats.get(0);
        final String mime = containerMimeType(first);

        final StringBuilder builder = new StringBuilder()
                .append("<AdaptationSet id=\"")
                .append(id)
                .append("\" contentType=\"")
                .append(audio ? "audio" : "video")
                .append("\" mimeType=\"")
                .append(xml(mime))
                .append("\" segmentAlignment=\"true\" startWithSAP=\"1\">");

        for (YoutubeSabrInfo.Format format : formats) {
            builder.append("<Representation id=\"")
                    .append(spec.getFormatKey(format))
                    .append("\" bandwidth=\"")
                    .append(Math.max(1, format.getBitrate()))
                    .append("\"");

            final String codecs = codecs(format);
            if (codecs != null && !codecs.isEmpty()) {
                builder.append(" codecs=\"")
                        .append(xml(codecs))
                        .append("\"");
            }

            if (audio) {
                builder.append(" audioSamplingRate=\"48000\"");
            } else {
                builder.append(" width=\"")
                        .append(Math.max(1, format.getWidth()))
                        .append("\" height=\"")
                        .append(Math.max(1, format.getHeight()))
                        .append("\"");
            }

            builder.append("><BaseURL>sabrseg://")
                    .append(spec.getFormatKey(format))
                    .append("/</BaseURL>")
                    .append(segmentTemplate(
                            format,
                            bridge.getTimeline(format)
                    ))
                    .append("</Representation>");
        }

        return builder.append("</AdaptationSet>").toString();
    }

    private static String segmentTemplate(
            YoutubeSabrInfo.Format format,
            YoutubeSabrFormatTimeline timeline
    ) {
        final int endSegment = timeline.getEndSequence();
        if (endSegment <= 0) {
            throw new IllegalStateException(
                    "Invalid SABR segment count: itag="
                            + format.getItag()
                            + ", count=" + endSegment
            );
        }

        final StringBuilder builder = new StringBuilder()
                .append("<SegmentTemplate timescale=\"1000\" startNumber=\"1\" ")
                .append("initialization=\"init\" media=\"$Number$\">")
                .append("<SegmentTimeline>");

        for (int sequence = 1; sequence <= endSegment; sequence++) {
            final long startMs = timeline.getStartMs(sequence);
            final long endMs = timeline.getEndMs(sequence);
            final long durationMs = Math.max(1L, endMs - startMs);
            builder.append("<S t=\"")
                    .append(Math.max(0L, startMs))
                    .append("\" d=\"")
                    .append(durationMs)
                    .append("\"/>");
        }

        return builder
                .append("</SegmentTimeline></SegmentTemplate>")
                .toString();
    }

    private static String formatDuration(long durationMs) {
        final long safe = Math.max(1L, durationMs);
        return "PT" + (safe / 1000L) + "."
                + String.format(
                        Locale.US,
                        "%03d",
                        safe % 1000L
                )
                + "S";
    }

    private static String containerMimeType(YoutubeSabrInfo.Format format) {
        final String mime = format.getMimeType();
        if (mime == null || mime.isEmpty()) {
            return format.isAudio()
                    ? MimeTypes.AUDIO_MP4
                    : MimeTypes.VIDEO_MP4;
        }
        final int semicolon = mime.indexOf(';');
        return semicolon >= 0
                ? mime.substring(0, semicolon).trim()
                : mime.trim();
    }

    private static String codecs(YoutubeSabrInfo.Format format) {
        final String mime = format.getMimeType();
        if (mime == null) return null;

        final int start = mime.indexOf("codecs=");
        if (start < 0) return null;

        return mime.substring(start + "codecs=".length())
                .replace("\"", "")
                .trim();
    }

    private static String xml(String value) {
        return value
                .replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
