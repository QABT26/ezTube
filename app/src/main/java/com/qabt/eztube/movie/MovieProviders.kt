package com.qabt.eztube.movie

object MovieProviders {
    /**
     * Provider configuration only. Domain changes do not affect playback architecture;
     * update the base URL list without touching the generic adapter/player core.
     */
    val MOTPHIM = MovieProviderConfig(
        id = "motphim",
        displayName = "MotPhim",
        baseUrls = listOf("https://motphimc.pw"),
        episodesPaths = listOf("/api/episodes", "/episodes"),
        hostHints = listOf("motphim")
    )
}
