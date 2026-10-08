package com.qabt.eztube.playback.sabr;

import androidx.media3.common.C;
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy;
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy;

import java.io.IOException;

final class SabrLoadErrorHandlingPolicy extends DefaultLoadErrorHandlingPolicy {
    private static final long PENDING_RETRY_DELAY_MS = 100L;
    private static final int MAX_SEGMENT_RETRIES = 3;

    @Override
    public long getRetryDelayMsFor(
            LoadErrorHandlingPolicy.LoadErrorInfo loadErrorInfo
    ) {
        if (isPending(loadErrorInfo.exception)) {
            return PENDING_RETRY_DELAY_MS;
        }
        if (loadErrorInfo.errorCount <= MAX_SEGMENT_RETRIES) {
            return Math.min(1_000L, loadErrorInfo.errorCount * 250L);
        }
        return C.TIME_UNSET;
    }

    @Override
    public int getMinimumLoadableRetryCount(int dataType) {
        return MAX_SEGMENT_RETRIES;
    }

    private static boolean isPending(IOException exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof SabrSegmentPendingException) return true;
            current = current.getCause();
        }
        return false;
    }
}
