# ReFreePlay

**Instant replay and delayed video for Android.** ReFreePlay (referee + free play + replay) keeps
recording into memory, so you can look back at what just happened:

- **Replay mode** is for referees, e.g. in combat juggling. When a situation was too fast to
  judge, press **REPLAY**: the last *N* seconds play immediately. You can scrub, step frame by
  frame and slow it down to ½, ¼ or ⅛ speed.
- **Delay mode** is for practice: the screen continuously shows what the camera saw *N* seconds
  ago. Set up the phone, do your pattern, then walk over and watch yourself, like an outside eye
  on your technique and posture.

Clips can be cut to length and saved, or quick-saved with one tap.

## Install

Download `ReFreePlay.apk` from the [Releases](https://github.com/kilian-m/refreeplay/releases) page and
open it on the phone. Requires Android 10 or newer.

1. Android asks for permission to install from that source (Files, Chrome, Gmail…). Allow it,
   then tap **Install**.
2. **Samsung:** if the install is refused, turn off *Auto Blocker* under Settings → Security and
   privacy while installing.
3. Google Play Protect may warn about an unknown app. Choose *Install anyway*. The warning
   appears because ReFreePlay isn't distributed through the Play Store.

Or with USB debugging: `adb install -r ReFreePlay.apk`

## Using it

Switch between **Replay** and **Delay** at the top left. **⚙** opens the settings.

### Replay mode
- Live camera with one big **REPLAY** button. It shows the replay length.
- **Quick save** stores the whole buffer (the last *N* seconds) in `Movies/ReFreePlay`, without asking.
- **Volume keys** or a **Bluetooth selfie remote** (it sends Volume Up) also start a replay.
- Pinch to zoom; double-tap resets the zoom.
- **While a replay is open, the buffer is frozen.** The decision break isn't recorded, so the
  incident stays in memory and can be replayed again after going back to live.

### Delay mode
- The screen shows the camera image delayed by *N* seconds. Tap the blue **⏱ DELAY** badge to
  change the delay.
- Right after starting, or after changing the delay, the buffer fills first: "Delayed view starts
  in … s".
- **REVIEW** opens the whole buffer on the replay screen, with scrubbing and cutting, while
  recording continues.
- **Quick save**, Volume Up or a remote stores the whole buffer in `Movies/ReFreePlay`. Volume Down
  opens the review.
- With the front camera, the delayed image is mirrored, like a mirror.

### Replay screen
- **Timeline**: drag for coarse positioning. **Swipe on the video** for fine scrubbing: one
  screen width covers 4 s. **Tap the video** to play or pause.
- **◀| / |▶** step exactly one frame. **−1 s / +1 s** jump.
- The **speed button** cycles 1× → ½× → ¼× → ⅛×.
- The time display counts toward the moment the button was pressed (`0.00 s`).
- **✂ (cut)** opens a two-handle slider to choose the part to keep. The video shows the frame
  under the handle you drag. Then:
  - **Save as…** opens Android's file dialog, so you can choose any folder or Google Drive and
    a file name.
  - **Quick save** stores the cut clip in `Movies/ReFreePlay`.

  The start of a cut snaps to the nearest earlier key frame, at most 0.5 s earlier, because clips
  are cut without re-encoding.
- **⤓ (quick save)** stores the full clip in `Movies/ReFreePlay`.
- **LIVE**, Back or Volume Down returns. Volume Up / remote plays or pauses.

### Portrait and landscape
ReFreePlay follows the phone's orientation; if auto-rotate is locked, it stays in that orientation.
Rotating doesn't interrupt recording. Replays are shown upright according to how the phone was
held when the button was pressed.

### Settings
| Setting | Notes |
|---|---|
| Camera | All cameras and lenses the phone exposes, with field of view. Default: the widest back camera. |
| Resolution | 16:9, from 640×360 up to 4K. Default 1080p. |
| Frame rate | 60 or 30 fps. Default 60. |
| Quality | Bitrate: Standard / High / Maximum. |
| Replay length | Any length from 1 to 600 s; it's also the buffer length in replay mode. |
| Delay | Any delay from 1 to 600 s; it's also the buffer length in delay mode. |

The buffer always holds exactly the length you set (plus up to half a second, because it's
trimmed at key frames). The dialog shows how much memory a length needs at the chosen
resolution, frame rate and quality, and warns if that's a large part of the phone's RAM. The app
uses at most 30 % of the RAM (on an 8 GB phone: about 2.2 GB; more made the phone drop frames in
tests). If a length doesn't fit, the status line shows "⚠ memory full". As a guide,
1080p/60 at "High" needs about 3 MB per second, so 600 s need about 1.8 GB; at "Standard" or
30 fps it's less.

## Notes
- Some phones, for example Samsung, don't officially offer 60 fps to third-party apps. ReFreePlay
  then marks 60 fps as "not officially supported" and tries it anyway. The status line shows the
  measured frame rate in brackets; choose 30 fps if it stays low. Small sensors such as
  ultra-wide lenses may also drop below 60 fps in dim light.
- Keep the phone on a charger for long sessions. Running the camera and encoder nonstop uses
  a lot of power and warms the phone.
- Video stays in RAM until you save it. Nothing is written to storage on its own.

## How it works

```
Camera2 ──┬──> SurfaceView (live preview)
          └──> MediaCodec H.264 encoder (key frame every 0.5 s)
                    └──> FrameRingBuffer: compressed frames in native shared memory
                           ├─ REPLAY / REVIEW → virtual MP4 (header generated, frames read from RAM) → ExoPlayer
                           ├─ Delay view → MediaCodec decoder → TextureView, each frame shown N s late
                           └─ Quick save / cut → MediaMuxer (copy, no re-encoding) → Movies/ReFreePlay or "Save as…"
```

| File | Purpose |
|---|---|
| `CameraCatalog.kt` | Lists cameras/lenses, resolutions and frame rates; picks defaults |
| `CameraController.kt` | Camera2 session, zoom |
| `VideoEncoder.kt` | Surface-input H.264 encoder that feeds the buffer |
| `FrameRingBuffer.kt` | Ring buffer in shared memory segments (freeze, pins for replays and saving), MP4 writer |
| `VirtualMp4.kt` | Serves a replay to ExoPlayer as an MP4 that is never written: opens instantly at any length |
| `DelayedViewer.kt` | Decodes the buffer with a fixed delay for delay mode |
| `ReplayController.kt` | ExoPlayer wrapper: throttled exact scrubbing, frame stepping |
| `Prefs.kt` | Settings and buffer memory sizing |
| `MainActivity.kt` | UI, modes, gestures, keys, saving, settings dialog |

## Building

Requires JDK 17 and the Android SDK (platform 35). Open the folder in Android Studio, or:

```
gradlew assembleRelease
```

The release APK is signed with the local debug key, so it can be sideloaded:
`app/build/outputs/apk/release/app-release.apk`.

For testing over USB, settings can be passed as launch extras, and the app logs its capabilities,
measured fps and timings under the tag `ReFreePlay`:

```
adb shell am start -n io.github.kilianm.refreeplay/.MainActivity --ei mode 1 --ef delay 3 --ef replay 20
adb logcat -s ReFreePlay
```

## License

[PolyForm Noncommercial 1.0.0](LICENSE.md): free to use, change and share for any
**non-commercial** purpose, for example personal use, hobby projects, non-profit clubs,
teaching and research. Commercial use needs separate permission from the author.

Uses AndroidX, Material Components and Media3 (ExoPlayer), all under the Apache License 2.0.
