# ezTube

An experimental Android **audio-first YouTube client** focused on reducing mobile data usage.

ezTube is designed for people who mostly listen to long-form YouTube content. The app aims to request and play an available **audio-only stream** instead of downloading the video stream.

> [!IMPORTANT]
> ezTube is an independent, unofficial project. It is not affiliated with, endorsed by, or sponsored by YouTube or Google.

## Goals

- Familiar feed and search experience
- Audio-only playback
- Data Saver / Standard / High audio quality modes
- Background playback
- Media notification and lock-screen controls
- Queue, history, favorites, and playlists
- Clean separation between extraction, playback, and UI

## Architecture

```
Compose UI
    |
YouTube source / extractor
    |
AudioStreamSelector
    |
Media3 / ExoPlayer
    |
MediaSessionService
```

The source/extractor layer is intentionally isolated so it can be replaced without rewriting the player or UI.

## Status

**Early development / bootstrap.** The first milestone establishes the Android + Jetpack Compose application shell. Audio extraction is not implemented yet.

## Tech stack

- Kotlin
- Jetpack Compose
- AndroidX Media3 (planned)
- Room (planned)
- NewPipeExtractor integration (planned; license compatibility will be reviewed before integration)

## Development

Open the project with a recent Android Studio and use JDK 17.

```bash
./gradlew assembleDebug
```

## Legal / distribution

This project is intended for research and personal/open-source development. Users and distributors are responsible for complying with YouTube's Terms of Service and applicable laws. The project does not ship Google/YouTube trademarks or claim to be an official client.

## License

License decision is intentionally pending until third-party dependency and distribution requirements are finalized.
