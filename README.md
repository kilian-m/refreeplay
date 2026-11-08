# ReFreePlay

**Instant replay and delayed video for Android.** ReFreePlay (referee + free play + replay) keeps
recording into memory, so you can look back at what just happened:

- **Refereeing**, e.g. in combat juggling: when a situation was too fast to judge, press
  **REPLAY**. The last *N* seconds play immediately. You can scrub, step frame by frame and slow
  it down to ½, ¼ or ⅛ speed.
- **Practice**: set a display delay and the screen continuously shows what the camera saw *N*
  seconds ago. Set up the phone, do your pattern, then walk over and watch yourself, like an
  outside eye on your technique and posture.

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

**⚙** opens the settings. Two of them decide how ReFreePlay behaves:

- **Replay length**: how much video is kept in memory. **REPLAY** shows all of it.
- **Display delay**: the screen shows the camera picture this many seconds late. **0** shows the
  live picture (for refereeing); for example **5** gives you time to walk over and watch yourself
  (for practice). The delay can't be longer than the replay length.

### Live screen
- **REPLAY** (it shows the replay length) opens the replay screen.
- **Quick save** stores the whole buffer (the last *N* seconds) in `Movies/ReFreePlay`, without asking.
- **Volume keys** or a **Bluetooth selfie remote** (it sends Volume Up) also start a replay.
- Pinch to zoom; double-tap resets the zoom.
- With a display delay, the blue **⏱ DELAY** badge shows it (tap it to open the settings).
  Right after starting or changing settings, the buffer fills first: "Delayed view starts in … s".
  With the front camera, the delayed image is mirrored, like a mirror.
- **Without a display delay, the buffer is frozen while a replay is open.** The decision break
  isn't recorded, so the incident stays in memory and can be replayed again after going back.
  With a display delay, recording continues while you watch the replay.

### Replay screen
- **Timeline**: drag for coarse positioning. **Swipe on the video** for fine scrubbing: one
  screen width covers 4 s.
- **Tap the video** to hide the controls and see the whole picture; a thin progress line and the
  time stay at the bottom, and swiping still scrubs. Tap again to show the controls.
  **Double-tap** plays or pauses.
- **◀| / |▶** step exactly one frame. **−1 s / +1 s** jump.
- The **speed button** cycles 1× → ½× → ¼× → ⅛×.
- The time display counts toward the moment the button was pressed (`0.00 s`).
- **✂ (cut)** opens a two-handle slider to choose the part to keep. The video shows the frame
  under the handle you drag; a white line marks the current position. **▶ Play** plays exactly
  the part that will be saved. Here too, tapping the video hides the cut panel; the thin line at the
  bottom then shows the selected part in red with a white marker at the current position, and the
  time and the length of the cut stay. Double-tapping plays the selected part.
  **Save as…** then opens Android's file dialog, so you can choose any folder or Google Drive
  and a file name.

  The start of a cut snaps to the nearest earlier key frame, at most 0.5 s earlier, because clips
  are cut without re-encoding.
- **⤓ (quick save)** stores the full clip in `Movies/ReFreePlay`.
- **LIVE**, Back or Volume Down returns. Volume Up / remote plays or pauses.

### Portrait and landscape
ReFreePlay follows the phone's orientation; if auto-rotate is locked, it stays in that orientation.
Rotating doesn't interrupt recording. Replays are shown upright according to how the phone was
held when the button was pressed. In landscape, REPLAY and Quick save sit at the bottom left, so they
stay reachable on a half-folded foldable phone standing like a camera stand.

### Settings
Changes are applied when you close the settings dialog.

| Setting | Notes |
|---|---|
| Camera | All cameras and lenses the phone exposes, with field of view. Default: the widest back camera. |
| Resolution | 16:9, from 640×360 up to 4K. Default 1080p. |
| Frame rate | 60 or 30 fps. Default 60. |
| Quality | Bitrate: Standard / High / Maximum. |
| Replay length | 1 to 600 s: how much video is kept in memory. |
| Display delay | 0 (live) up to the replay length. |

The buffer always holds exactly the replay length (plus up to half a second, because it's
trimmed at key frames). The dialog shows how much memory the settings need and how much this
phone can safely use: 30 % of its RAM (on an 8 GB phone about 2.2 GB; more made the phone drop
frames in tests). **Settings that need more are not applied**; a short message explains why, and
you can go back and change them. Settings stored earlier that don't fit (for example from an
older version) are never changed automatically: each time the app is opened fresh, a warning
lets you change them or continue anyway. As a
guide, 1080p/60 at "High" needs about 3 MB per second, so
600 s need about 1.8 GB; at "Standard" or 30 fps it's less. If a busy scene needs more than
estimated, the buffer simply uses more memory, up to the safe limit; only if even that is not
enough does the status line show "⚠ memory full" and the buffer gets a little shorter.

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
                           ├─ Display delay → MediaCodec decoder → TextureView, each frame shown N s late
                           └─ Quick save / cut → MediaMuxer (copy, no re-encoding) → Movies/ReFreePlay or "Save as…"
```

| File | Purpose |
|---|---|
| `CameraCatalog.kt` | Lists cameras/lenses, resolutions and frame rates; picks defaults |
| `CameraController.kt` | Camera2 session, zoom |
| `VideoEncoder.kt` | Surface-input H.264 encoder that feeds the buffer |
| `FrameRingBuffer.kt` | Ring buffer in shared memory segments (freeze, pins for replays and saving), MP4 writer |
| `VirtualMp4.kt` | Serves a replay to ExoPlayer as an MP4 that is never written: opens instantly at any length |
| `DelayedViewer.kt` | Decodes the buffer with a fixed delay for the display delay |
| `ReplayController.kt` | ExoPlayer wrapper: throttled exact scrubbing, frame stepping |
| `Prefs.kt` | Settings and buffer memory sizing |
| `MainActivity.kt` | UI, gestures, keys, saving, settings dialog with memory check |

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
adb shell am start -n io.github.kilianm.refreeplay/.MainActivity --ef replay 30 --ef delay 3
adb logcat -s ReFreePlay
```

## License

[PolyForm Noncommercial 1.0.0](LICENSE.md): free to use, change and share for any
**non-commercial** purpose, for example personal use, hobby projects, non-profit clubs,
teaching and research. Commercial use needs separate permission from the author.

Uses AndroidX, Material Components and Media3 (ExoPlayer), all under the Apache License 2.0.
