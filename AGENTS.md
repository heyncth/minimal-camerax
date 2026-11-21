# AGENTS.md — minimal-camerax

Guidance for AI coding agents (and humans) working in this repository. **Read this before making
changes.** It describes the rules, architecture, design system, and shared components.

Play Store camera app (`com.arindam.camerax`) built with Jetpack CameraX 1.6.2. Kotlin, Jetpack
Compose, minSdk 23, compileSdk/targetSdk 37. Goal: show other apps what the CameraX library can do,
with an iPhone-style minimal surface.

## Surface

The app exposes exactly three things:

| Surface | How it is reached | What it does |
|---|---|---|
| **Photo** | mode strip | Still capture (JPEG / HEIC-Ultra HDR / RAW), flash, timer, tap-to-focus, pinch zoom, 0.5× / 1× / 2× chips |
| **LIVE** | Photo-header chip | Apple Live Photo pair: still + `.mov` sibling sharing one base name |
| **Video** | mode strip | Record with audio, 60 fps / HDR when the device lists them, flip while recording |
| **DUAL** | Photo-header chip | Both lenses in one picture-in-picture still |

`CameraMode` is `PHOTO`, `VIDEO`, `DUAL_PHOTO`. `DUAL_PHOTO` is **not** a strip label: the strip
renders `PHOTO · VIDEO` and keeps PHOTO lit while Dual Photo is on. Slo-mo, Effects, Panorama, Dual
video and Others were removed from the product; their internals still live in `CameraSession` but
are unreachable — do not re-expose them without an explicit task.

The look is a black viewfinder with system type: no glass, no accent gradients, no technical status
badges. Selected mode / active chip is yellow `#FFD60A`. Photo preview is 4:3, Video 16:9; the
chrome letterboxes around the preview instead of stretching it.

## Architecture (Clean Architecture, single module)

```
ui (presentation) → domain ← data
```

Do not construct `CameraSession` from the UI. Keep use cases; do not collapse to ViewModel →
CameraSession. Do not split Gradle modules unless asked.

Disk and MediaStore work (`list` / `delete` / `publish` / `stitch` / Live Photo mux / still
effects) must stay off the main thread: repositories use `AppDispatchers.io`, ViewModels
`withContext`. Do not call `listFiles`, `BitmapFactory.decodeFile`, or `MediaMetadataRetriever` from
Compose or click handlers.

### File map (where to change what)

| Goal | Start here |
|---|---|
| Mode policy / visible strip labels | `domain/model/CameraModeCatalog.kt` + `CameraMode.labelRes` in `ui/home/camera/CameraModels.kt` |
| Bind / capture / zoom / flash / focus | `CameraViewModel` → `domain/usecase` → `CameraRepository` → `data/camera/CameraSession.kt` |
| Live-feed chrome (header, strip, shutter, zoom) | `ui/home/camera/CameraChrome.kt` |
| Live-feed layout, gestures, PiP drag | `ui/home/camera/CameraScreen.kt` |
| Settings rows | `settingsSections()` in `ui/settings/SettingsCatalog.kt`; capabilities in `SettingsViewModel` |
| Theme (Light/Dark/System) | `util/theme/NightMode.kt` + `SettingsActivity` |
| LIVE (Apple Live Photo) | `data/camera/AppleLivePhotoWriter.kt`; fallback `data/camera/MotionPhotoMuxer.kt`; trim `MotionPhotoTrimmer.kt` |
| Still JPEG EXIF (decode / re-encode) | `data/camera/StillImageExif.kt` |
| Dual Photo PiP geometry | `data/camera/DualComposition.kt`; merge `DualPhotoMerger.kt` |
| Publish to DCIM | `data/media/MediaStorePublisher.kt` via `PublishMedia` |
| Composition root | `di/AppContainer.kt` (`CameraInteractors`) |
| Shared glass chrome (gallery, confirm, settings) | `ui/compose/CameraGlassButton.kt` (`ChromeControlSize`), `ChromeActionPill.kt` |

`CameraFragment` is a thin Compose host only (plus IMAGE_CAPTURE / MOTION_PHOTO result delivery).
Do not resurrect the old View-based capture/flash/zoom code. Public types have KDoc describing
which layer they belong to.

## CameraX rules

- Bind `Preview + ImageCapture + VideoCapture` together when possible; fall back (drop video, stills
  only) on `IllegalArgumentException`.
- Rebind only for lens, physical back cameras (ultra-wide/tele), OEM extensions, night-scene
  auto-switch, Settings photo aspect / video quality / video stabilization / Ultra HDR / RAW / DNG /
  full-sensor RAW / 60 fps (`GroupableFeature.FPS_60`), entering/leaving Dual Photo, or a PiP
  corner/scale change while Dual Photo is bound. Zoom, torch, flash, target rotation, and exposure
  compensation must not rebind.
- Extra back cameras are listed from `availableCameraInfos` + focal length and shown as
  0.5x/1x/2x chips that rebind by camera id.
- Flip during a normal video recording is allowed when Settings **Flip while recording** is on
  (default off): start with `PendingRecording.asPersistentRecording()`, keep the same
  `VideoCapture`/`Recorder`, `unbindAll()`, and bind the new lens — do not stop the clip. Skip
  persistent recording for Dual Photo and LIVE.
- OEM extensions (HDR / Night / Portrait=`BOKEH` / Beauty=`FACE_RETOUCH`) only if
  `ExtensionsManager.isExtensionAvailable`. Extensions typically cannot bind with `VideoCapture`.
  There are **no manual extension chips** — extensions apply programmatically only (auto
  night-scene switch). Do not add extension chips unless asked.
- Overlay Compose chrome on `PreviewView` with `ImplementationMode.COMPATIBLE` (TextureView).
  `PERFORMANCE` / SurfaceView steals touches so the footer and header miss. Chrome uses `zIndex`,
  `WindowInsets.safeDrawing` for the header and `safeDrawing.union(systemGestures)` for the footer,
  and Material `minimumInteractiveComponentSize` (≥48dp touch) around 44dp controls.
- **Header** (one row, no chevron / no expandable panel): flash (left) … DUAL / LIVE / timer chips +
  settings gear (right). Recording replaces the row with a red dot + `mm:ss`. Grid stays in Settings
  → General → Grid (`pref_key_grid` → `CaptureSettings.gridEnabled`, session-only, no rebind).
- **Footer**: zoom pills (Photo + Video, hidden while recording), mode strip, then gallery thumb /
  shutter / flip. Shutter is a 76dp white disc (red with a stop square while recording).
- Stills: prefer reflected CameraX `*HEIC*` Ultra HDR if advertised, else
  `OUTPUT_FORMAT_JPEG_ULTRA_HDR`, else JPEG. All `ImageCapture` builders use
  **`CAPTURE_MODE_MAXIMIZE_QUALITY`**. Settings toggle (default on) when `ImageCapture` capabilities
  list an Ultra HDR format. RAW: Settings **RAW / DNG** (default off) when capabilities list
  `OUTPUT_FORMAT_RAW_JPEG`; bind that format and `takePicture` DNG + JPEG together. Settings **Full
  sensor RAW** (default off) when the camera lists an ultra-high-resolution / maximum-resolution RAW
  map; bind with `SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION` and highest still size. Skip Ultra HDR, OEM
  extensions, LIVE, and night auto-switch while RAW is on. Media whitelist: `jpg`, `jpeg`, `heic`,
  `dng`, `mp4`, `mov`.
- Still orientation (EXIF): CameraX writes sensor-oriented JPEGs with an EXIF rotation tag. Keep
  `ImageCapture` / `Preview` / `VideoCapture` `targetRotation` in sync with
  `previewView.display.rotation` (no rebind). **Front mirror (still):** use
  `ImageCapture.Metadata.isReversedHorizontal` — do not decode/re-encode JPEGs just to mirror. Any
  JPEG decode/re-encode (Dual Photo merge, thumbnails) must go through `StillImageExif`: apply EXIF
  to pixels, then write with `ORIENTATION_NORMAL`. Ultra HDR / HEIC stills skip recompress.
  **Video:** no JPEG EXIF — orientation and front mirror come from `VideoCapture.targetRotation` and
  `MirrorMode.MIRROR_MODE_ON_FRONT_ONLY` at encode time; do not post-process MP4 orientation.
- After capture, `PublishMedia` copies into `DCIM/CameraX` via MediaStore so items appear in the
  system gallery. In-app gallery still reads the app pictures directory. Capture confirmation
  (Retake / Done) is off by default and can be turned on in Settings. Photo aspect defaults to
  Full; video quality defaults to FHD.
- Night auto-switch (API 36+): `CaptureResult.EXTENSION_NIGHT_MODE_INDICATOR`, debounce ~800ms, bind
  Night extension. Do not auto-switch if the user picked an extension, LIVE is on, RAW / DNG is on,
  or the mode is video.
- Low light boost: `CameraInfo.isLowLightBoostSupported` then
  `CameraControl.enableLowLightBoostAsync` (no rebind). Settings toggle (default on); disable the
  row when unsupported. Skip it while the torch is on.
- Video HDR: `Recorder.getVideoCapabilities` `getSupportedDynamicRanges`, then
  `VideoCapture.Builder.setDynamicRange`. Settings chips for SDR / HLG 10-bit / HDR10 / HDR10+ that
  the device lists (disabled if only SDR). Rebind on change.
- Video stabilization: `Preview.Builder.setPreviewStabilizationEnabled` and
  `VideoCapture.Builder.setVideoStabilizationEnabled` after the capability checks. Settings toggle
  (default on).
- Video 60 fps: query `CameraInfo.isSessionConfigSupported` on a `SessionConfig` with
  `GroupableFeature.FPS_60`, then bind that required feature group. Settings toggle (default off).
- Do not add `COLOR_CORRECTION_MODE_CCT` unless asked. Hybrid AE / exposure-compensation plumbing
  still exists in `CameraSession` but has no UI; do not add an exposure panel unless asked.

## LIVE (Apple Live Photo)

LIVE produces the same pair iOS writes, so other Apple software recognises it:

- Still: JPEG whose EXIF MakerNote carries the Apple `17` (still-image-time) tag.
- Clip: `.mov` sibling with the **same base name** (`IMG_1234.jpg` + `IMG_1234.mov`) containing
  `com.apple.quicktime.content.identifier` in `moov/meta` and a
  `com.apple.quicktime.still-image-time` track.
- `AppleLivePhotoWriter` builds both. It rewrites the JPEG EXIF and appends `moov`/`meta`/timed
  metadata to the MediaMuxer output. It only works when `moov` is the **last** box (MediaMuxer
  always emits it that way); if the layout does not fit it pads `moov` back to its original size and
  returns `false` rather than shifting `stco` offsets.
- If the Apple pair cannot be written, fall back to `MotionPhotoMuxer` (Android motion-photo v1:
  XMP `GCamera:MicroVideo` in a single JPEG). Never leave both formats ambiguous.
- Rolling capture: 1.5 s pre-roll + 1.5 s post-roll. `CameraViewModel.updateMotionRolling()` calls
  `setMotionRolling(...)` only while Photo has LIVE on (and not mid-capture). The session keeps a
  muted rolling 720p clip (`asPersistentRecording`, app cache `motion_preroll/`, never
  MediaStore/gallery) restarting every 20 s. Shutter marks `motionShutterAt`, the still fires,
  recording stops at +1.5 s; `finishMotionIfReady` trims keyframe-aligned
  `[shutter−1.5s, shutter+1.5s]` with `MotionPhotoTrimmer` (video-only, no re-encode) and writes the
  Apple pair with `presentationTimestampUs = shutter − trimmed start`.
  `captureMotionPhotoNoPreroll` is the fallback when no rolling clip is warm. Cleanup on LIVE off /
  mode switch / unbind (`cancelMotionRolling`). Enabling LIVE drops OEM extensions so `VideoCapture`
  can bind. Intents: `android.provider.action.MOTION_PHOTO_CAPTURE` / `_SECURE`.
- The `.mov` is a companion, not a gallery item: `MediaFiles.listedCaptures` filters it, the
  publisher files it as `video/quicktime`, and the gallery/review play it as part of the Live Photo.
- `MediaFiles` / gallery detect a Live Photo by the presence of that sibling `.mov`.

## Dual Photo

`CameraMode.DUAL_PHOTO` is a **Photo-header DUAL toggle** (not a strip label; chip only when the
device lists a front + back camera). It captures both lenses into one PiP still.

- Simultaneous tier: non-composition `ConcurrentCamera` (`SingleCameraConfig`s — primary `Preview` +
  `ImageCapture`, secondary `ImageCapture` only; composition mode cannot bind `ImageCapture`).
- Probe-gated fallback: if that bind throws, latch `dualPhotoSequentialOnly` and use the sequential
  tier — capture the primary still, rebind to the other lens (keep `dualPhoto = true` so stills stay
  plain JPEG), capture, restore the primary.
- Merge in `DualPhotoMerger` on `cameraExecutor` (`StillImageExif` in, `ORIENTATION_NORMAL` out).
  Merge failure keeps the primary alone; one-side failure keeps the surviving still.
- Inset geometry comes from **`DualComposition`** (NDC, default top-left like iPhone Dual Capture):
  one source shared by the photo merge and the **static white PiP outline** over the preview.
- The outline is **draggable 1:1 with the finger**, snaps to the nearest of the four corners on
  release (haptic + spring), and is pinch-resizable (`DualPipOverlay` in `CameraScreen.kt`). The
  shared `PipCorner` / scale state persists as silent prefs (`pref_key_pip_corner`,
  `pref_key_pip_scale`) with no Settings row; changing either while Dual Photo is bound bumps
  `bindRevision`. Do not restart the gesture mid-drag.
- Flip swaps which lens is the full-frame primary (`state.lens` → `toggleLens`, rebinds).
- Skip Ultra HDR / RAW / extensions / night auto / LIVE in this mode.

## UI

Edge-to-edge black preview, flat chrome, system font. There is no glass on the live feed: header
icons and text chips are plain white (yellow when active), 44dp visual, ≥48dp touch.

Modes: `PHOTO · VIDEO` (Dual Photo is the DUAL chip in the Photo header). Hide the DUAL chip when no
front + back pair is listed. Gallery videos expose 0.5x–2x playback speed. Settings is Compose
(`SettingsScreen`); add rows in `settingsSections()` in `SettingsCatalog.kt`. Themed launcher icon
includes `<monochrome>`. Large-screen rotation: `setTargetRotation` only, chrome stacks (no
hardcoded 210/300 dp).

### Shared chrome components

Full-screen overlay controls (Gallery, Confirm / review, Permissions, Settings) still use the shared
glass components: **`ChromeControlSize` = 44dp** in `ui/compose/CameraGlassButton.kt`
(`CameraGlassButton`, `ChromeActionPill`). Do not invent a second height (no 52dp / 56dp pills);
prefer those components over one-off `size` / `height` / `diameter` values. Touch targets expand to
≥48dp via `minimumInteractiveComponentSize`.

### Chrome screen insets / header padding

Full-screen overlays with a back button (Gallery, Confirm / review, Permissions header band,
Settings) must match **Settings** edge spacing. Reference implementations: `SettingsScreen`,
`Gallery` header, `CaptureConfirm` header.

**Header (top chrome)**

1. Apply **`WindowInsets.safeDrawing` only** for top + horizontal — **do not**
   `.union(WindowInsets.systemGestures)` on the header. Stacking `systemGestures` with safe drawing
   adds a large extra inset and pushes content too far in (regression we already fixed once).
2. Then pad **`start = 20.dp`, `end = 20.dp`, `top = 8.dp`** (no asymmetric 4/12 header padding).
3. Settings does the same via `Scaffold(contentWindowInsets = WindowInsets.safeDrawing)` + content
   `padding(start = 20.dp, end = 20.dp)` and header row `padding(top = 8.dp)`.

The live-feed header follows the same 20dp / 8dp rule.

**Footer (bottom chrome)**

- Bottom action rows (Retake/Done, gallery share/delete, permission pills, live shutter row):
  `WindowInsets.safeDrawing.union(WindowInsets.systemGestures)` for **bottom + horizontal** (gesture
  nav needs the extra bottom inset).
- Typical footer content padding: horizontal **16.dp** for full-width pills, **4.dp** bottom under
  the button row (above the inset).

**Do not** copy old live-feed `12.dp` / `6.dp` paddings onto Gallery / Confirm / Settings.

## Platform

- Predictive back: `android:enableOnBackInvokedCallback="true"`. No orientation lock.
- 16 KB: `packaging.jniLibs.useLegacyPackaging = false` (do not set `android:extractNativeLibs` in
  the manifest).
- Share / capture results: `ClipData` + `FLAG_GRANT_READ_URI_PERMISSION`. IMAGE_CAPTURE writes
  `EXTRA_OUTPUT` or returns a thumbnail / FileProvider URI (FileProvider for motion photos and HEIC).
- Stop recording on `ON_STOP`.
- `android.hardware.microphone` is optional.
- Release: R8 minify + resource shrink, native `SYMBOL_TABLE` for Play Console, Crashlytics mapping
  when `app/google-services.json` is present. After `bundleRelease`, run
  `./gradlew printNativeDebugSymbols` and upload
  `app/build/outputs/native-debug-symbols/release/native-debug-symbols.zip` in Play Console when the
  zip exists (dependency `.so` files are often pre-stripped, so Play may still warn). Firebase
  Analytics collection stays off (`firebase_analytics_collection_enabled=false`). Backup is off
  (`allowBackup=false` + data-extraction rules). Public types should carry layer-tagged KDoc
  (`App:` / `DI:` / `Domain:` / `Data:` / `Presentation:` / `Util:`). Debug builds enable StrictMode
  (log only). See [RELEASE.md](RELEASE.md) for the Play Store checklist.

## Play Console release notes

When the user says **bump version** / **version bump** / **bump the version**, follow
[`.cursor/rules/bump-version.mdc`](.cursor/rules/bump-version.mdc) or
[`.agents/skills/bump-version/SKILL.md`](.agents/skills/bump-version/SKILL.md) (increment
`versionCodeOffset`, then print Play notes from `git log` since the previous upload).

When drafting Google Play **Release details**, prefer the **long bullet** form (not a one-liner).
Also see [`.cursor/rules/play-console-release-notes.mdc`](.cursor/rules/play-console-release-notes.mdc).

1. **Release name** — `versionName` only, ≤50 characters. Internal; not shown on Play.
2. **Release notes** — paste-ready with language tags. Source from `git log` since the previous tag,
   the README feature table, and the shipping version in root `build.gradle.kts`. User-facing
   highlights only (modes, capture formats, gallery, Settings, UI). Skip architecture / DI / agent
   docs / build-only churn. Prefer “when your device supports them” for capability-gated APIs. End
   with a stability / polish bullet when the release includes fixes.

```
Release name: 1.7.0

<en-US>
What's new in CameraX 1.7.0

• Simplified Photo / Video camera with iPhone-style chrome
• LIVE captures a real Apple Live Photo (JPEG + MOV) that other apps can play
• DUAL captures both cameras into one picture-in-picture photo — drag the inset anywhere
• Ultra HDR, RAW/DNG, video HDR and 60 fps when your device supports them
• Stability and performance improvements
</en-US>
```

## Do not

- Force-push, amend pushed commits, or commit unless asked.
- Edit the plan file in `.cursor/plans/`.
