# Implementation Plan - Fix HEVC 10-bit Playback Error on Emulator

The app is failing to play HEVC 10-bit content because the emulator's hardware-accelerated decoder (`c2.goldfish.hevc.decoder`) reports it cannot handle the format or crashes with `Error 0xe`. Even with decoder fallback enabled, the player may still be stuck or failing to find a viable alternative.

## User Review Required

> [!IMPORTANT]
> I will explicitly filter out the buggy `c2.goldfish.hevc.decoder` for HEVC content. This will force ExoPlayer to use the standard Android software decoder (`c2.android.hevc.decoder`), which is generally more stable for high-profile content like 10-bit HEVC in an emulator environment.

## Proposed Changes

### UI Layer

#### [MODIFY] [PlaybackScreen.kt](file:///Users/borisk/AndroidStudioProjects/Sratim/app/src/main/java/com/example/sratim/ui/playback/PlaybackScreen.kt)
- Create a custom `MediaCodecSelector` that filters out `c2.goldfish.hevc.decoder` specifically for `video/hevc` content.
- Inject this selector into the `DefaultRenderersFactory`.
- This ensures that when the app encounters HEVC content, it bypasses the buggy emulator-specific decoder.

## Verification Plan

### Automated Tests
- Verify that the app builds successfully.

### Manual Verification
- Deploy to the emulator.
- Try to play the 10-bit HEVC movie that previously failed.
- Check Logcat for "SratimPlayback" and "MediaCodec" logs to confirm that `c2.android.hevc.decoder` (or another fallback) is being used instead of `c2.goldfish.hevc.decoder`.
