package com.qabt.eztube

import android.app.Application
import com.qabt.eztube.youtube.NewPipeDownloader
import com.qabt.eztube.youtube.sabr.LocalDomPoTokenProvider
import org.schabi.newpipe.extractor.NewPipe

class EzTubeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        NewPipe.init(NewPipeDownloader())
        LocalDomPoTokenProvider.initialize(this)
        NewPipe.setYoutubePoTokenResolver(LocalDomPoTokenProvider::getPlayerPoToken)
    }
}
