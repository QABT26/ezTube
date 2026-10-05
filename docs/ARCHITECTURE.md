# Architecture

ezTube keeps the content-source implementation behind a small boundary.

```
Compose UI
   |
YouTubeSource
   |
audio stream metadata
   |
AudioStreamSelector
   |
Media3 player (next milestone)
```

## Why this boundary exists

YouTube delivery mechanisms change over time. UI, persistence and playback should not depend directly on one extractor implementation.

## Audio quality policy

- **Data Saver**: prefer the highest available stream at or below 64 kbps.
- **Standard**: prefer the highest available stream at or below 128 kbps.
- **High**: prefer the highest bitrate available.

The selector operates only on audio stream metadata. No video stream is selected by this component.

## Next milestone

1. Review extractor dependency licensing and current Android compatibility.
2. Add an implementation of `YouTubeSource`.
3. Add AndroidX Media3 playback and MediaSessionService.
4. Add tests for stream selection and source mapping.
