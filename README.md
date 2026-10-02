# Anamorphic Stretch Cam

[![pipeline status](https://gitlab.com/AlexWeber64/digital-anamorphic-camera/badges/master/pipeline.svg)](https://gitlab.com/AlexWeber64/digital-anamorphic-camera/-/pipelines)

A Kotlin Android app that bakes a permanent, real, pixel-level horizontal
stretch/squeeze into recorded MP4 video, not a preview overlay, not a
metadata hint. What you see on the monitor is a proportional simulation of
what gets encoded; what gets encoded is the actual distorted frame content.

## Why CameraX `VideoCapture` is not used

CameraX's `VideoCapture` use case owns its own internal recording `Surface`
and hands it straight to its internal `Recorder`/`MediaMuxer` pipeline. There
is no supported hook to inject a GL-transformed frame as its source: a
`Matrix` applied to a `PreviewView`/`TextureView` only ever changes the
on-screen preview, because `VideoCapture` reads from the camera's surface
independently of whatever the preview view is doing. Any implementation that
transforms the preview and calls it done would ship an undistorted MP4.

Instead, this app uses CameraX for exactly one thing: camera session +
lifecycle management via `Preview`, with the app's own `SurfaceTexture`
(backed by an external-OES GL texture) supplied as the target surface instead
of a `PreviewView`. From there:

```
Camera -> SurfaceTexture (external OES texture)
       -> StretchGLRenderer (single shared EGL context, one draw call per frame)
            -> screen WindowSurface   (on-screen monitor)
            -> encoder WindowSurface  (MediaCodec.createInputSurface())
                 -> MediaCodec (H.264) -> MediaMuxer -> DCIM/Camera/*.mp4
```

Because both destinations are painted from the *same* draw call with the
*same* MVP matrix logic, the encoded file is a faithful, WYSIWYG match for
what the operator saw while filming (mode differences aside; see below).

## Stretch vs. Squeeze modes

Chosen once per recording (locked at `start()`, since MediaCodec can't change
output dimensions mid-stream) via the toggle above the scale slider:

- **Stretch**: the encoder's own output buffer is configured *wider* than
  the sensor (`sensorWidth * scale`, rounded to an even pixel count for the
  H.264 encoder). The full, unscaled camera texture is drawn across that
  wider buffer, so the buffer's own aspect ratio does the stretching: a
  1920x1080 sensor frame at 2.0x becomes an encoded **3840x1080** file. This
  is the unambiguous option: any player, on any device, shows the distortion
  with zero special handling.
- **Squeeze**: the encoder's output buffer stays at the sensor's native
  size. The quad is instead scaled down by `1/scale` on both destinations,
  compressing the image toward the center (with black pillarboxing) -
  approximating what raw, un-desqueezed anamorphic lens footage looks like.

The scale multiplier (1.0x-4.0x, default 2.0x) is a live GL uniform update in
both modes and can be adjusted mid-recording; only the mode toggle is locked
out once recording starts.

## Rotation handling

The app runs unlocked (both orientations) via `android:configChanges` so the
Activity, camera session, GL context, and any in-progress recording all
survive a rotation; only the `SurfaceView`'s own `Surface` is torn down and
recreated by the OS, which `StretchGLRenderer.setScreenSurface()` reacts to.

Device-rotation compensation (`CameraX SurfaceRequest.TransformationInfo`) is
applied **only** to the preview destination, so the monitor always matches
how the phone is being held. The encoder destination is left completely
untouched, and `MediaMuxer.setOrientationHint()` carries the playback
rotation instead. That's a standard, universally-supported orientation
hint unrelated to the anamorphic distortion, which stays a true per-pixel
transform in the encoded frames themselves either way.

**Not verified on physical hardware.** I could build and fully compile this
project on this machine (Gradle 8.14.4 / AGP 8.7.3 / compileSdk 34), but there
is no Android device or emulator available in this environment to actually
run it, point it at a real camera, and eyeball the rotation sign convention
or the on-device encode. If the preview appears mirrored or rotated the wrong
way on your S26 Ultra, the fix is a one-line sign flip in
`StretchGLRenderer.drawToSurface()` (`Matrix.rotateM(..., -rotationDegrees, ...)`
-> drop the negation). Please treat this as your first thing to check on
first run.

## Changing defaults

| What | Where |
|---|---|
| Default scale multiplier | `app/src/main/res/layout/activity_main.xml` -> `scaleSlider` `android:value` |
| Scale range | same `Slider`, `android:valueFrom` / `android:valueTo` / `android:stepSize` |
| Default mode (Stretch/Squeeze) | `MainActivity.setupControls()` -> `binding.modeStretchButton.isChecked = true` |
| Video bitrate | `MainActivity.startRecording()` -> `StretchRecorder.Config(videoBitRate = 16_000_000)` |
| Frame rate | same `Config(frameRate = 30)` |
| Max capture resolution cap | `CameraSource.pickNativeSensorSize()` -> `maxArea` (currently capped at 1920x1080 for real-time GL/encoder headroom; raise it for a beefier device) |
| Audio on/off default | `StretchRecorder.Config(useAudio = ...)`, currently tied to whether `RECORD_AUDIO` was granted |
| Output file naming / folder | `StretchRecorder.createOutputMuxer()` (`DCIM/Camera`, `ANAMORPHIC_<timestamp>.mp4`) |

## Verifying the bake-in on a real device

After recording, `MainActivity` logs (`Log.i("MainActivity", ...)`) the exact
encoded width/height used and the mode, e.g.:

```
Saved ANAMORPHIC_20260823_181700.mp4 at encoded size 3840x1080 (mode=STRETCH)
```

Pull the file (`adb pull /sdcard/DCIM/Camera/ANAMORPHIC_*.mp4`) and inspect it
with `ffprobe` or `MediaMetadataRetriever`. In Stretch mode the container's
actual coded width should be `sensorWidth * scale`, confirming the distortion
is a real pixel-dimension change baked into the encoded stream, not a
metadata tag or a preview-only effect. In Squeeze mode the coded size will
match the sensor, and the compression is visible in the pixel content itself
(open the file in any player with no desqueeze correction; subjects should
look narrower/taller than in Stretch mode).

## Known limitations / non-goals (by design, per spec)

- No lens-extension detection, no anamorphic metadata/hint boxes: the
  distortion is a real per-pixel GL resample, always.
- No dependency on `LENS_DISTORTION` or physical lens calibration: purely
  digital, forced distortion.
- No `AspectRatio.RATIO_16_9`/`RATIO_4_3` snapping; capture size comes from
  `Camera2CameraInfo` / `StreamConfigurationMap.getOutputSizes()` directly.
- Front-camera mirroring is not compensated for; back camera is the default
  and primary target.
- Audio/video sync relies on `AudioRecord` sample-count timestamps and the
  camera's own `SurfaceTexture` timestamps; not frame-accurate-verified
  against real hardware for the reason above.
