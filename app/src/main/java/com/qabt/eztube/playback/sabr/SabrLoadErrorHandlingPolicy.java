package com.qabt.eztube.playback.sabr;

import androidx.media3.common.C;
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy;
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy;

import java.io.IOException;

final class SabrLoadErrorHandlingPolicy extends DefaultLoadErrorHandlingPolicy {
    private static final long PENDING_RETRY_DELAY_MS = 100L;

    @Override
    public long getRetryDelayMsFor(
            LoadErrorHandlingPolicy.LoadErrorInfo loadErrorInfo
    ) {
        if (isPending(loadErrorInfo.exception)) {
            return PENDING_RETRY_DELAY_MS;
        }
        final int normalRetryCount = super.getMinimumLoadableRetryCount(
                loadErrorInfo.mediaLoadData.dataType
        );
        return loadErrorInfo.errorCount > normalRetryCount
                ? C.TIME_UNSET
                : super.getRetryDelayMsFor(loadErrorInfo);
    }

    @Override
    public int getMinimumLoadableRetryCount(int dataType) {
        return Integer.MAX_VALUE;
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
