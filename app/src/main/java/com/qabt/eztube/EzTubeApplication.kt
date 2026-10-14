package com.qabt.eztube

import android.app.Application
import com.qabt.eztube.youtube.NewPipeDownloader
import com.qabt.eztube.playback.PlaybackPreferences
import com.qabt.eztube.youtube.sabr.LocalDomPoTokenProvider
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization

class EzTubeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Keep extractor text deterministic and user-controlled. Language controls
        // textual metadata; region controls which country's YouTube catalogue/feed is requested.
        val playbackPrefs = PlaybackPreferences(this)
        val metadataLanguage = playbackPrefs.loadMetadataLanguage()
        val contentRegion = playbackPrefs.loadContentRegion()
        NewPipe.init(
            NewPipeDownloader(),
            Localization(metadataLanguage, contentRegion),
            ContentCountry(contentRegion)
        )
        LocalDomPoTokenProvider.initialize(this)
        NewPipe.setYoutubePoTokenResolver(LocalDomPoTokenProvider::getPlayerPoToken)
    }
}
