package com.qabt.eztube

import android.app.Application
import com.qabt.eztube.youtube.NewPipeDownloader
import com.qabt.eztube.youtube.sabr.LocalDomPoTokenProvider
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization

class EzTubeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Keep extractor text deterministic for the English ezTube UI while preserving
        // Vietnam as the content region. Without an explicit localization, YouTube/PipePipe
        // can occasionally return textual metadata (for example relative upload dates)
        // in an unrelated locale.
        NewPipe.init(
            NewPipeDownloader(),
            Localization("en", "VN"),
            ContentCountry("VN")
        )
        LocalDomPoTokenProvider.initialize(this)
        NewPipe.setYoutubePoTokenResolver(LocalDomPoTokenProvider::getPlayerPoToken)
    }
}
