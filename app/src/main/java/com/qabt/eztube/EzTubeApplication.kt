package com.qabt.eztube

import android.app.Application
import com.qabt.eztube.youtube.NewPipeDownloader
import org.schabi.newpipe.extractor.NewPipe

class EzTubeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        NewPipe.init(NewPipeDownloader())
    }
}
