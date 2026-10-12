# Onyx EPD Compositor Research Plan

This document scopes what it would take to investigate whether Scrawl can change the way Onyx raw pen rendering composites with normal Android `View`/`Canvas` content.

The target behavior is:

- Raw pen strokes render with TouchHelper-level latency.
- Scrawl annotation overlays, selection UI, and toolbar affordances render above the raw ink layer.
- Erasing and tool switching do not blank, ghost, or desynchronize the visible canvas.

This is not an app feature plan. It is a research plan for the vendor EPD/raw-ink rendering stack.

## Current Observations

Scrawl experiments on Note Air 5C suggest TouchHelper raw rendering is not just a normal Android canvas draw path.

Observed behavior:

- Raw handwriting appears quickly and can visually sit above Scrawl's `View.onDraw` output.
- Canvas annotation overlays can register in the model but not reliably appear above active raw ink.
- Switching out of writing mode can briefly reveal annotation overlays, implying the Android canvas state exists underneath or behind the raw layer.
- `setRawDrawingEnabled(false)` can make existing raw strokes disappear visually even though Scrawl's model still contains strokes.
- `restartRawDrawing()` and close/reopen flows can blank the visible raw layer.
- Native `enableSideBtnErase(true)` can remove model strokes through raw erase callbacks, but it produced black eraser traces and raw-layer ghosting in this IME.
- Keeping raw render off after erase avoided one class of blanking but caused the next side-button gesture to draw a black native trace over stale/cleared raw state.

Working hypothesis:

TouchHelper uses a vendor-controlled raw/scribble layer that is composited outside normal `View` drawing. The public SDK controls raw input and raw render state, but does not expose z-order, alpha, blending, or a stable "draw Android Canvas over raw ink" contract.

## Research Questions

The investigation should answer these questions before attempting any firmware or framework patch.

1. What process owns the raw drawing surface?
2. Is the raw drawing surface a `SurfaceView`, SurfaceFlinger layer, hardware overlay, framebuffer path, EPD driver plane, or something else?
3. Does TouchHelper create a distinct SurfaceFlinger layer, or does it issue commands directly to an Onyx service/native library?
4. Is layer order configurable through hidden APIs, system properties, or native calls?
5. Is alpha/blending configurable, or is raw ink written directly into a monochrome/color EPD update buffer?
6. Does Boox Notes use the same TouchHelper path as third-party apps, or does it use privileged notecore/render-manager APIs?
7. Can alternate TouchHelper render modes, especially app render mode, give acceptable latency with normal Android compositing?
8. Is the compositor behavior device-specific, firmware-specific, or common across recent Onyx devices?

## Known Entry Points

Public or semi-public SDK APIs already seen locally:

- `com.onyx.android.sdk.pen.TouchHelper`
- `TouchHelper.create(view, callback)`
- `setRawDrawingEnabled`
- `forceSetRawDrawingEnabled`
- `setRawDrawingRenderEnabled`
- `setRawInputReaderEnable`
- `openRawDrawing`
- `closeRawDrawing`
- `enableSideBtnErase`
- `setLimitRect`
- `setExcludeRect`
- `setSingleRegionMode`
- `setPostInputEvent`
- `Device.currentDevice().setEraserRawDrawingEnabled`
- `EpdController.invalidate`
- `EpdController.setScreenHandWritingPenState`
- `EpdPenManager.pauseDrawing/resumeDrawing`

Internal/decompiled paths worth studying:

- `/tmp/knote2_src/sources/com/onyx/android/sdk/pen/TouchHelper.java`
- `/tmp/knote2_src/sources/com/onyx/android/sdk/pen/touch/SFTouchRender.java`
- `/tmp/knote2_src/sources/com/onyx/android/sdk/pen/touch/AppTouchRender.java`
- `/tmp/knote2_src/sources/com/onyx/android/sdk/pen/RawInputReader.java`
- `/tmp/knote2_src/sources/com/onyx/android/sdk/pen/RawInputManager.java`
- `/tmp/knote2_src/sources/com/onyx/android/note/note/action/render/InvalidateViewWithPenControlAction.java`
- `/tmp/knote2_src/sources/com/onyx/android/note/note/eventhandler/PenEventHandler.java`
- `/tmp/knote2_src/sources/com/onyx/android/note/note/request/pen/ResumeRawDrawingRequest.java`

## Investigation Track A: App-Level Boundary Tests

Do these before any system modification. They establish what can be solved without firmware work.

### A1. Compare TouchHelper Render Backends

Goal: determine whether `SFTouchRender` is the compositing problem and whether `AppTouchRender` behaves like normal Android content.

Questions:

- Can Scrawl force `FEATURE_APP_PEN_TOUCH_RENDER` instead of `FEATURE_SF_TOUCH_RENDER`?
- Does app render mode allow Canvas annotations above ink?
- What is the latency cost?
- Does erase still clear/ghost?

Artifacts to collect:

- Build variant or runtime toggle for render backend.
- Side-by-side log markers for backend, begin/end raw stroke, annotation draw, erase.
- Short screen recordings for raw writing, annotation, erase, toolbar tap.

Pass criteria:

- App render path displays annotation overlays above ink without raw-layer blanking.
- Latency remains acceptable enough for handwriting.

Fail criteria:

- Same overlay/blanking behavior as SF render.
- Latency is worse than the old Canvas fallback.

### A2. Systematically Test Raw State Transitions

Goal: map which calls blank or preserve visible raw ink.

Test matrix:

- `setRawDrawingRenderEnabled(false)` only.
- `setRawInputReaderEnable(false)` only.
- `setRawDrawingEnabled(false)` only.
- `forceSetRawDrawingEnabled(false)` if accessible.
- `closeRawDrawing()`.
- `restartRawDrawing()`.
- Resume render first, input second.
- Resume input first, render second.
- Resume after 0ms, 50ms, 150ms, 500ms.
- `DU`, `GC`, `REGAL`, and handwriting update modes during repaint.

For each case record:

- Does visible raw ink disappear?
- Does Android model bitmap appear?
- Does next stroke write normally?
- Does eraser draw a trace?
- Does annotation overlay appear above raw?
- Does toolbar tapping clear the canvas?

### A3. Raw Region and Exclusion Behavior

Goal: find whether compositor behavior changes with region setup.

Test:

- Single limit rect.
- Multiple exclude rects.
- No single-region mode.
- Single-region mode.
- Toolbar rects as exclusions instead of shrinking the writing rect.
- Full-screen raw limit rect vs IME-only rect.

Question:

Does the raw layer allocate per-region surfaces, or does it always create one topmost plane?

### A4. Screen Capture and Visual Truth

Goal: determine whether Android screenshots include raw ink.

Test:

- Take Android screenshot while raw ink is visible.
- Compare screenshot to physical screen/photo.
- Repeat with annotation overlay visible or hidden.

Interpretation:

- If screenshots exclude raw ink, raw rendering is outside normal Android composition.
- If screenshots include raw ink but overlay order differs on device, the EPD refresh path is the issue.
- If screenshots match the device, SurfaceFlinger layer order may be inspectable and controllable.

## Investigation Track B: SurfaceFlinger and Window Composition

This track requires ADB shell access. It should be read-only at first.

### B1. Inspect SurfaceFlinger Layers

Commands to run while Scrawl raw drawing is active:

```bash
adb shell dumpsys SurfaceFlinger --list
adb shell dumpsys SurfaceFlinger
adb shell dumpsys window
adb shell dumpsys input
```

Look for:

- Layers created when Scrawl IME appears.
- Layers created only after first raw stroke.
- Names containing `scribble`, `pen`, `raw`, `touch`, `onyx`, `surface`, `input`, `ime`.
- Relative z-order between Scrawl IME window, raw layer, system IME layer, and app window.
- Whether raw layer disappears when `setRawDrawingRenderEnabled(false)` is called.

Useful experiment:

1. Dump SurfaceFlinger before IME opens.
2. Open Scrawl IME, dump again.
3. Draw one raw stroke, dump again.
4. Toggle annotation overlay, dump again.
5. Disable raw render, dump again.

Diff the outputs rather than reading them manually.

### B2. Inspect Window Types and Flags

Scrawl runs as an IME window. The raw renderer may assume normal app window behavior.

Inspect:

- IME window type.
- Whether the raw layer is attached to the IME view's window token.
- Whether the raw layer is global/top-level.
- Whether `FLAG_HARDWARE_ACCELERATED`, secure flags, or overlay flags affect the render path.

App-level experiments:

- Test the same canvas in a normal Activity instead of IME.
- Test in an Activity with and without `SurfaceView`.
- Test with hardware acceleration disabled for the canvas view.
- Test with a `TextureView` or `SurfaceView` annotation layer.

If normal Activity composition works but IME composition fails, the issue is likely window-type/layer policy rather than TouchHelper alone.

### B3. SurfaceControl Possibilities

Modern Android uses `SurfaceControl` for layer transactions, but app access is restricted.

Research:

- Does the raw layer expose a child surface under Scrawl's view root?
- Can an app-level `SurfaceView` be placed above the raw layer?
- Does `setZOrderOnTop(true)` affect annotation `SurfaceView` relative to raw ink?
- Does Onyx firmware permit non-system apps to adjust relevant `SurfaceControl` properties?

Likely result:

Normal apps cannot reorder vendor-created privileged layers if they are not children of the app window.

## Investigation Track C: Onyx Framework and Native SDK

This track studies the vendor SDK and framework implementation.

### C1. Decompile and Map TouchHelper

Build a call graph for:

- `TouchHelper.create`
- `TouchHelper.setRawDrawingEnabled`
- `TouchHelper.setRawDrawingRenderEnabled`
- `TouchHelper.setRawInputReaderEnable`
- `SFTouchRender.openRawDrawing`
- `SFTouchRender.leaveScribbleMode`
- `SFTouchRender.pauseDrawing`
- `AppTouchRender`
- `RawInputReader.native*` calls

Questions:

- Which methods cross into JNI?
- Which methods call framework hidden APIs?
- Which methods call `Device.currentDevice()` reflection wrappers?
- Which methods interact with `View` directly?
- Which methods invoke EPD controller state?

Expected outcome:

A diagram of public TouchHelper calls to native/framework calls, with the exact call that creates, pauses, resumes, and destroys the raw render surface.

### C2. Identify Native Libraries

Find native libraries used by the Onyx SDK and system framework.

Commands:

```bash
adb shell pm path com.onyx.android.note
adb shell pm path com.onyx
adb shell find /system /vendor /product -iname '*onyx*' -o -iname '*epd*' -o -iname '*scribble*' -o -iname '*pen*'
adb shell dumpsys package | grep -i onyx
```

Likely targets:

- Onyx SDK jars in app/system framework.
- Native libraries backing `RawInputReader`.
- EPD controller libraries.
- System services related to handwriting, scribble, or display updates.

For extracted libraries:

- Use `strings`.
- Use `nm` if symbols are present.
- Use `readelf`.
- Use Ghidra only after Java-level mapping identifies a precise target.

Do not start with blind native reversing. The Java call graph should narrow the target first.

### C3. Hidden View and Device Methods

The SDK uses reflection into Onyx-specific framework methods on `View` and `Device`.

Research:

- Methods named `setEraserRawDrawingEnabled`.
- Raw drawing region methods.
- Scribble mode enter/leave methods.
- Pen state methods.
- Any method that accepts blend, alpha, layer, surface, region, or mode parameters.

Search terms:

- `rawDrawing`
- `scribble`
- `handWriting`
- `Epd`
- `pen`
- `layer`
- `surface`
- `alpha`
- `blend`
- `compose`
- `overlay`
- `zOrder`
- `region`

If a hidden setter exists for layer order or alpha, app-level reflection might be enough. If it requires system permissions, that becomes a privileged-app or framework-patch problem.

## Investigation Track D: Boox Notes Privileged Path

Boox Notes may not be using the same contract third-party apps get.

Known Notes architecture:

- `PauseRawPenAction`
- `ResumeRawPenAction`
- `PauseResumeRawPenAction`
- `InvalidateScreenAction`
- `InvalidateViewWithPenControlAction`
- `PenEventHandler`
- `ResumeRawDrawingRequest`
- Render manager and notecore shape renderers.

Important distinction:

Notes does not simply draw Android overlays over TouchHelper raw ink. It coordinates raw pen state with its document renderer and EPD refresh pipeline.

Research goals:

- Determine whether Notes ever displays normal Android UI above active raw ink, or whether it pauses raw render first.
- Determine whether Notes has privileged access to render into the same raw/EPD buffer.
- Determine whether Notes uses special render managers unavailable to third-party apps.
- Determine whether Notes app is signed with platform or Onyx keys and receives hidden permissions.

Useful checks:

```bash
adb shell dumpsys package com.onyx.android.note
adb shell dumpsys package com.onyx.android.note | grep -i permission
adb shell dumpsys package com.onyx.android.note | grep -i sharedUserId
```

If Notes has privileged permissions or platform signature access, copying its behavior may require system-level install/signing.

## Investigation Track E: System Modification Options

This is the risky track. Treat it as exploratory only until Tracks A-D prove the exact missing lever.

### E1. Privileged App Experiment

Goal: test whether Scrawl gains extra compositor control when installed as a privileged/system app.

Possible methods:

- Rooted device with `/system/priv-app` or Magisk module.
- Grant hidden permissions if any are discovered.
- Sign with available test/platform keys only if the firmware accepts them, which production devices usually do not.

Pass criteria:

- Hidden APIs become callable.
- Raw layer ordering or blending changes.
- Behavior differs from normal user app.

Fail criteria:

- Same TouchHelper behavior.
- Hidden APIs still unavailable.
- System app status does not affect vendor raw renderer.

### E2. Framework Hooking

Goal: intercept calls around raw drawing creation and layer configuration.

Possible tools:

- LSPosed/Xposed-style Java hooks.
- Frida for temporary dynamic tracing.
- Magisk module for persistent library/framework replacement.

Targets:

- `TouchHelper`
- `SFTouchRender`
- `RawInputReader`
- Onyx `Device` implementation.
- EPD controller service APIs.
- Any framework method that creates or updates the scribble layer.

Start with tracing, not patching:

- Log arguments.
- Log call order.
- Log returned objects and surface/layer IDs if present.
- Compare Scrawl vs Boox Notes.

### E3. Native Patching

Only consider this if:

- Java/framework tracing identifies the exact native method responsible for layer config.
- The native library is extractable and load path is understood.
- There is a reversible way to test.

Risks:

- Boot loops.
- Display corruption.
- Input failure.
- Firmware update breakage.
- Device-specific binary offsets.
- Hard-to-debug EPD damage-like behavior, even if only software state.

Minimum safety requirements:

- Full firmware image or recovery package.
- Known restore path.
- ADB/root access confirmed after failed app launches.
- Exact device/firmware version documented.
- No persistent patch until dynamic hooks prove the desired effect.

## Practical Experiment Order

Recommended order:

1. Build a Scrawl debug screen that can toggle raw render backend, raw input, raw render, update mode, and annotation layer type.
2. Add a visual test pattern: raw strokes, model bitmap strokes, annotation rectangles, toolbar, and a moving overlay marker.
3. Run the raw state transition matrix on Note Air 5C.
4. Run the same test in a normal Activity, not IME.
5. Try annotation overlays using plain `View`, `SurfaceView`, and `TextureView`.
6. Dump SurfaceFlinger layers at each state.
7. Compare Scrawl layer dumps to Boox Notes layer dumps while drawing.
8. Decompile TouchHelper/SFTouchRender/AppTouchRender into a call graph.
9. Trace Java calls with Frida/LSPosed before patching anything.
10. Only then consider privileged install or native hooks.

## Success Criteria

A compositor-level solution is worth pursuing only if one of these is found:

- A public or hidden API controls raw layer z-order or blending.
- App render mode composes correctly with acceptable latency.
- A `SurfaceView`/`TextureView` overlay can reliably sit above raw ink.
- A system/privileged permission unlocks a documented Onyx path.
- A small, reversible hook changes z-order/blending without breaking raw input.

Otherwise, Scrawl should keep the app architecture conservative:

- TouchHelper for low-latency raw writing.
- App model as source of truth.
- Canvas overlays only when raw render is paused or inactive.
- Avoid native side-button erase for this IME.
- Prefer mode boundaries over trying to continuously composite semantic annotations above active raw ink.

## Non-Goals

Do not spend time on these until the layer model is understood:

- Blind native binary patching.
- Replacing random Onyx libraries.
- Rewriting Scrawl around assumptions from Boox Notes without confirming privileges.
- Building a custom ROM as the first step.
- Treating screenshots alone as proof of physical EPD composition.

## Current Recommendation

The most promising next app-level experiment is not firmware patching. It is a controlled render-backend test:

- Force or expose `AppTouchRender` if possible.
- Compare it against the default `SFTouchRender`.
- Verify whether normal Android overlays can draw above app-rendered ink.
- Measure whether latency is acceptable.

If that fails, the next best research artifact is a SurfaceFlinger layer diff across Scrawl and Boox Notes. That will tell us whether the missing lever is ordinary layer ordering, a privileged Onyx service, or a lower-level EPD update path.
