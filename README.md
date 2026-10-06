# ezTube

ezTube is an experimental Android **audio-first YouTube client** focused on listening with less mobile data.

> **Unofficial project:** ezTube is not affiliated with, endorsed by, or sponsored by YouTube or Google.

## Beta V1

Current Beta V1 includes:

- YouTube search through NewPipeExtractor
- audio-only stream selection with Saver / Standard / High modes
- compatibility fallback for videos where a separate audio stream is unavailable
- Media3 / ExoPlayer background playback
- media notification and lock-screen controls
- queue, previous / next and autoplay
- draggable seek bar and ±10 second controls
- playback speed and sleep timer
- resume last track and position
- Room-backed listening history and favorites
- persistent playback settings
- Home, Search and Library screens

When ezTube must use a muxed compatibility stream, the app explicitly warns that it may use more data.

## Architecture

```
Jetpack Compose UI
        |
YouTubeSource / NewPipeExtractor
        |
AudioStreamSelector
        |
Media3 / ExoPlayer
        |
MediaSessionService

Room -> History / Favorites
SharedPreferences -> Playback state / Settings
```

The extraction layer is intentionally isolated because upstream YouTube behavior can change.

## Build

Requirements: Android SDK 35 and JDK 17 or newer supported by the configured Android Gradle Plugin.

```bash
./gradlew clean assembleDebug
```

Debug APK:

```
app/build/outputs/apk/debug/app-debug.apk
```

## Beta limitations

- YouTube can change extraction or streaming behavior without notice.
- Some videos expose no direct audio-only URL and require a higher-data compatibility stream.
- Queue is currently created from search results and is not yet a persistent playlist system.
- Beta builds should be tested on-device before wider distribution.

## Legal / distribution

This project is intended for research, personal use, and open-source development. Users and distributors are responsible for complying with applicable laws and service terms. The project does not claim to be an official YouTube client.

## License

ezTube source code is licensed under the GNU General Public License v3.0 or later (GPL-3.0-or-later). See `LICENSE` and `NOTICE`.

NewPipeExtractor is a third-party project licensed under GPL-3.0-or-later. Other Android dependencies retain their respective licenses.
