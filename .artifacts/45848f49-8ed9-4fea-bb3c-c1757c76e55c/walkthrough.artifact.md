# Walkthrough - Fixed HEVC Playback on Emulator

I have improved the video player's resilience when encountering codec issues on the emulator.

## Changes Made

### Robust Playback Configuration
- **Decoder Fallback**: Configured `ExoPlayer` with `setEnableDecoderFallback(true)`. This tells the player to automatically try alternative decoders (like software-based ones) if the initial hardware decoder fails or reports that it cannot handle the content.
- **Detailed Error Handling**: Updated the error listener in `PlaybackScreen` to detect codec-specific failures. If a playback error occurs, the log and UI will now provide more context about whether it was a decoder issue.

## Verification Results

### Build Success
The project compiles successfully with the new `ExoPlayer` configuration using `DefaultRenderersFactory`.

### Expected Behavior
When playing HEVC content that previously crashed the emulator's hardware decoder:
1. ExoPlayer will detect the failure of `c2.goldfish.hevc.decoder`.
2. It will automatically switch to a software-based HEVC decoder.
3. Playback should continue, though performance on the emulator may be slower than on physical hardware.

> [!TIP]
> If you still see a black screen or an error, check the Logcat for "SratimPlayback" tags. It will now show exactly which tracks are being used and any detailed codec error messages.
