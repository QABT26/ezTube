package com.qabt.eztube.playback.sabr;

import java.io.IOException;

final class SabrSegmentPendingException extends IOException {
    SabrSegmentPendingException(String message) {
        super(message);
    }
}
