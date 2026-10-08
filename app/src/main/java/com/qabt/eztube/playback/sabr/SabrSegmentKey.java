package com.qabt.eztube.playback.sabr;

import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrInfo;

import java.util.Objects;

final class SabrSegmentKey {
    private final YoutubeSabrInfo.Format format;
    private final boolean initialization;
    private final int sequenceNumber;

    private SabrSegmentKey(
            YoutubeSabrInfo.Format format,
            boolean initialization,
            int sequenceNumber
    ) {
        this.format = format;
        this.initialization = initialization;
        this.sequenceNumber = sequenceNumber;
    }

    static SabrSegmentKey initialization(YoutubeSabrInfo.Format format) {
        return new SabrSegmentKey(format, true, -1);
    }

    static SabrSegmentKey media(YoutubeSabrInfo.Format format, int sequenceNumber) {
        if (sequenceNumber <= 0) throw new IllegalArgumentException("Bad SABR sequence");
        return new SabrSegmentKey(format, false, sequenceNumber);
    }

    YoutubeSabrInfo.Format getFormat() { return format; }
    boolean isInitialization() { return initialization; }
    int getSequenceNumber() { return sequenceNumber; }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof SabrSegmentKey)) return false;
        SabrSegmentKey that = (SabrSegmentKey) other;
        return format.getItag() == that.format.getItag()
                && format.getLastModified() == that.format.getLastModified()
                && Objects.equals(format.getXtags(), that.format.getXtags())
                && initialization == that.initialization
                && sequenceNumber == that.sequenceNumber;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                format.getItag(), format.getLastModified(), format.getXtags(),
                initialization, sequenceNumber
        );
    }
}
