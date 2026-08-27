# CLAUDE.md

Anamorphic Stretch Cam - Android camera app that bakes a horizontal
anamorphic stretch/squeeze permanently into recorded video/photo pixels,
not just a preview effect.

## Build / run

```
./gradlew assembleDebug              # build debug APK
./gradlew :app:compileDebugKotlin    # fast compile-only check
./gradlew installDebug               # install to a connected device/emulator
```

No automated tests exist in this repo yet (no `androidTest`/`test` source
sets). Requires JDK 17+ (AGP 8.7.3 / Gradle 8.14.4 minimum). If `./gradlew`
fails with a `JavaVersion.parse` error, the environment's default `java` is
too new (e.g. JDK 26) for this Gradle/Kotlin-DSL version - point `JAVA_HOME`
at a JDK 17-21 install instead.

**Current versions:** minSdk 26, targetSdk/compileSdk 34, Kotlin 2.0.21,
AGP 8.7.3, Java 17 (`sourceCompatibility`/`targetCompatibility`/`jvmTarget`
in `app/build.gradle.kts`).

## Architecture decisions - do not revert these

- **Recording bypasses CameraX's `VideoCapture` use case entirely.** CameraX
  is used only for camera lifecycle management and the `Preview` use case
  (`CameraSource.kt`). Pipeline: Camera -> `SurfaceTexture` -> GL renderer
  (`StretchGLRenderer`) -> dual render to (a) the screen preview surface and
  (b) a MediaCodec input surface -> MediaCodec H.264 + MediaCodec AAC ->
  `MediaMuxer` -> MP4 via `MediaStore` into `DCIM/Camera`. `VideoCapture`
  can't have a custom GL-transformed surface injected as its source, and the
  whole point of this app is baking the stretch into recorded pixels, not
  applying it as a preview-only or metadata-only effect.
- **Two recording modes** (`StretchGLRenderer.Mode`):
  - STRETCH: encoder output buffer width = sensorWidth * scale (e.g.
    1920x1080 at 2.0x -> 3840x1080). The wider buffer does the stretching;
    the encoder's GL matrix scale factor is fixed at 1.0.
  - SQUEEZE: encoder buffer stays at native sensor size; the GL quad is
    scaled by 1/scale on both destinations (pillarboxing).
  - Mode is locked once recording starts (`modeToggleGroup` is disabled in
    `MainActivity.setRecordingUiState()`) - MediaCodec output dimensions
    can't change without a full re-init.
  - The scale slider is *not* disabled during recording, but this only
    matters live in SQUEEZE mode. In STRETCH mode the encoder's scale is
    hardcoded to 1.0 (see above), so moving the slider mid-recording changes
    only the live preview, not the actual recorded output. Don't "fix" this
    asymmetry without deciding whether STRETCH should support live scale
    changes at all (it would need buffer dimensions fixed at record-start
    regardless, since MediaCodec can't resize).

## Known-fragile areas - rotation

Rotation has broken and been re-fixed multiple times across past sessions.
Current (correct) architecture, as of the `bugfix/ui-rotation-docking` merge:

- **The live camera preview never rotates.** It shows the raw sensor-oriented
  feed as-is, like a real optical viewfinder tilting with the physical
  device - this was a deliberate decision after an earlier version made the
  preview auto-level itself, which is wrong (native camera apps don't do
  this). Do not reintroduce any `Matrix.rotateM` call in
  `StretchGLRenderer.drawToSurface()`.
- **Do not use CameraX `Preview`'s `TransformationInfo.rotationDegrees` /
  `DisplayManager` display-rotation as the source of truth for anything.**
  It tracks window/display rotation, not physical device orientation, and
  is actively wrong once the activity is orientation-locked (it is - see
  `AndroidManifest.xml`, `screenOrientation="portrait"`, specifically so the
  control chrome never re-flows on rotation).
- **The only rotation signal is `MainActivity`'s live `OrientationEventListener`**,
  combined with the active camera's `CameraCharacteristics.SENSOR_ORIENTATION`
  and `LENS_FACING` (`MainActivity.rawOrientationHint()`). This feeds
  `StretchGLRenderer.rotationDegrees` continuously (not just once at camera
  bind or at recording start), which `correctedRotationDegrees()` calibrates
  further (see its doc comment - an empirically-derived offset, confirmed
  on real devices, not re-derivable from the formula alone). This calibrated
  value is used *only* for `MediaMuxer.setOrientationHint()` (video) and a
  post-capture `Bitmap` rotation (`MainActivity.capturePhoto()`) - never for
  anything drawn to screen or to the encoder surface.
- **UI chrome rotation is a separate, unrelated mechanism.** Only icon-only
  controls (the camera-flip button) counter-rotate in place to stay upright
  as the device physically rotates (`MainActivity.rotatingChromeViews`/
  `applyChromeCounterRotation()`). Text-bearing controls (mode-toggle
  labels, the scale readout) deliberately do not rotate at all - this is a
  design decision matching native camera apps (e.g. Samsung's), not a
  limitation: an earlier version did rotate the text and a real clipping bug
  (rotated pill/button bounds not swapping width/height) was found and
  fixed, but text rotation was removed afterward anyway for UX reasons
  unrelated to that bug.
- **Recorded-file timestamps must be normalized.**
  `SurfaceTexture.getTimestamp()` returns boot-relative nanoseconds, not
  recording-relative. `StretchGLRenderer` captures the first frame's
  timestamp after `attachEncoderSurface()` as a baseline
  (`encoderTimestampBaseNanos`) and subtracts it from every subsequent
  frame before handing it to the encoder - otherwise the muxer reports a
  wildly wrong duration and playback doesn't start until the file's actual
  midpoint.

## Known-fragile areas - audio

- `RECORD_AUDIO` must be requested independently of `CAMERA`, not only
  bundled in the same permission-launcher call. If `CAMERA` is already
  granted (e.g. from an earlier install) while `RECORD_AUDIO` isn't, a
  bundled-only request means the mic prompt silently never appears and every
  recording has no audio, with no visible error. See
  `MainActivity.initPipelineIfNeeded()` / the permission-check block in
  `onCreate()`.
- `AudioRecord` with `AudioSource.CAMCORDER` isn't guaranteed to initialize
  on every device/emulator; `StretchRecorder.createAudioRecord()` falls back
  to `AudioSource.MIC` if so. Audio setup is probed *before* the muxer's
  expected track count is decided, so a failed audio init falls back to a
  video-only recording instead of hanging the muxer waiting on a track that
  never arrives.

## File responsibilities

- `MainActivity.kt` - UI wiring, permissions, orientation signal
  (`OrientationEventListener`), start/stop recording and photo capture,
  computes the calibrated orientation hint used by both.
- `CameraSource.kt` - CameraX lifecycle/`Preview` binding only, exposes the
  active camera's `lensFacing`/`sensorOrientation`, front/back camera switch.
- `gl/StretchGLRenderer.kt` - owns the shared EGL context and camera
  `SurfaceTexture`; draws every frame to the screen surface and (while
  recording) the encoder surface; applies the anamorphic stretch/squeeze as
  a real GL vertex-matrix transform; timestamp baselining.
- `gl/EglCore.kt` / `gl/WindowSurface.kt` / `gl/StretchShaderProgram.kt` -
  thin EGL/GLES helpers (context/surface management, shader compile+draw).
- `StretchRecorder.kt` - hand-rolled MediaCodec (H.264 + AAC) + `MediaMuxer`
  recording path, `MediaStore` output.

## Git / GitHub workflow conventions

- PR-based workflow: branch, push, open a PR, merge via GitHub. Branch
  protection on `master` is enforced server-side (confirmed: GitHub rejects
  direct pushes with "Changes must be made through a pull request") - there
  is no direct-push exception, even for repo-meta files.
- Branch naming: `feature/<short-description>`, `bugfix/<short-description>`,
  `refactor/<short-description>`, `chore/<short-description>`.
- Commit messages: conventional-commit prefixes (`feat:`, `fix:`, `chore:`),
  imperative mood, no period on the summary line. Never include
  "Co-Authored-By", "Generated with Claude Code", emoji, or any other
  attribution/signature line in any commit.
- `.github/workflows/android-build.yml` runs `./gradlew assembleDebug` and
  uploads the APK as a build artifact on every push to `feature/**`,
  `bugfix/**`, `refactor/**` and on PRs targeting `master`. Debug builds
  only - no signing config/keystore secrets exist in this repo.
