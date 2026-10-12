# Scrawl Render Manager Architecture

This document describes the target rendering architecture for Scrawl's handwriting IME. It is written from the current working conclusion:

- The reliable product path is standard Android `Canvas` rendering with EPD turbo disabled.
- Boox native raw/EPD paths are useful accelerators, but they should not own Scrawl's model, toolbar behavior, sidebar behavior, or correctness.
- Any Boox-specific fast path must be isolated behind a render manager so tools issue semantic render requests instead of calling `TouchHelper`, `EpdController`, `invalidate`, or delayed resume logic directly.

Implementation note: the current stable runtime baseline is Canvas-only and intentionally removes the EPD/raw drawing path. See `docs/canvas-only-rendering-observations.md` for the debugging observations, layer model, and safe reintroduction strategy.

## Goals

Scrawl needs three properties at the same time:

1. **Correctness.** Eraser, undo/redo, clear, sidebar annotations, selection, and structured tools must always match the document model after a redraw.
2. **Interaction reliability.** Toolbars, sidebars, popups, and finger interactions must remain usable while pen input is active.
3. **Optional low latency.** On Boox devices, pen strokes should be able to use faster EPD paths when those paths do not break correctness or UI composition.

The architecture should make the correct path simple and the fast path replaceable.

## Evidence From Boox Notes

The native Notes app does not appear to solve this by drawing every overlay directly over a permanently active raw pen layer.

Decompiled device APK:

- APK: `reverse/boox-notes/knote2-release.apk`
- Decompiled tree: `reverse/boox-notes/decompiled`

Relevant findings:

- `NoteManager.bindPenHostView()` creates `TouchHelper.create(view, 3, callback, false)`, meaning `FEATURE_SF_TOUCH_RENDER | FEATURE_APP_TOUCH_RENDER`.
- Text handwriting uses `TouchHelper.create(view, 4, callback)`, meaning `FEATURE_APP_PEN_TOUCH_RENDER`, and forwards normal `MotionEvent`s into `touchHelper.onTouchEvent(event)`.
- `UniversalShapeHandler.canPenRawRender()` returns `false`.
- `RenderUniversalShapesAction` registers a Canvas renderer with `InkRenderManager`.
- `InvalidateViewWithPenControlAction` does `pauseRawPen -> renderManager.invalidate -> resumeRawPen`.
- `RawPenArgs.DELAY_ENABLE_RAW_DRAWING_MILLS` is `150ms` on monochrome devices and `500ms` on color devices.

The important lesson is architectural: native Notes treats raw pen as one controlled rendering mode inside a larger editor/render-manager system. Structured shapes and UI-like overlays route through Canvas renderers and coordinated invalidation.

## Current Scrawl Direction

The current preferred backend is:

```text
render_backend = canvas
```

In this mode:

- Scrawl skips `TouchHelper`.
- Scrawl renders normal `MotionEvent` input through Android `Canvas`.
- EPD turbo is disabled during strokes.
- Eraser works reliably because model mutation and view repaint happen in the same Android rendering pipeline.

This should remain the default until a faster backend proves it can preserve eraser/sidebar/tool reliability.

## Core Design

The render manager owns rendering policy. Tools own user intent.

```text
MotionEvent / raw pen callback
        |
        v
ToolController
        |
        v
Document model mutation + render intent
        |
        v
ScrawlRenderManager
        |
        +--> CanvasRenderer
        +--> LiveInkRenderer
        +--> OverlayRenderer
        +--> BooxPenController
        +--> DirtyRegionTracker
```

Tools must not directly call:

- `TouchHelper.setRawDrawingEnabled`
- `TouchHelper.setStrokeStyle`
- `EpdController.moveTo`
- `EpdController.quadTo`
- `invalidate`
- delayed raw-pen resume handlers
- full bitmap redraw helpers

Instead, tools emit semantic operations:

```kotlin
renderManager.beginStroke(point, tool = Pen)
renderManager.updateStroke(point)
renderManager.commitStroke()

renderManager.beginErase(point)
renderManager.updateErase(point)
renderManager.commitErase()

renderManager.showOverlay(Overlay.EraserCursor(point))
renderManager.clearOverlay(OverlayKey.EraserCursor)

renderManager.invalidateModel(reason = Undo)
renderManager.invalidateRegion(rect, reason = AnnotationChanged)
```

The render manager decides whether those operations are Canvas-only, raw pen, app-level EPD, or pause/resume transactions.

## Layers

Scrawl should model rendering as layers even if the first implementation uses one Android `View`.

### 1. Persistent Model Layer

The source of truth.

Contains:

- saved strokes
- erased/remaining stroke geometry
- sidebar annotation regions
- text/metadata annotations
- selection state that should survive redraws

Responsibilities:

- replay model into a bitmap or Canvas
- support full redraw
- support dirty-region redraw when safe
- produce deterministic output after undo/redo

This is the only layer that matters for saved document correctness.

### 2. Live Ink Layer

Transient preview for the current pen stroke.

Backends:

- Canvas live path: draw the active stroke in `onDraw`.
- `app_pen` path: forward MotionEvents into `TouchHelper` feature `4`.
- `sf_raw` path: native raw TouchHelper rendering.
- `epd_direct` path: direct `EpdController.moveTo/quadTo` experiment.

Rules:

- Live ink is never the source of truth.
- On pen-up, the stroke commits into the model.
- If the live layer is lost or cleared, the model redraw must recover the visible state.
- Live ink should not intercept toolbar/sidebar interactions.

### 3. Overlay Layer

Transient UI-like drawing that must compose correctly with controls.

Contains:

- eraser cursor
- lasso preview
- selection handles
- shape previews
- screenshot/tag/wiki/header rectangles
- drag affordances

Rules:

- Prefer Canvas for overlays.
- Do not rely on raw TouchHelper compositing for overlays.
- Overlay updates should be dirty-region invalidations where possible.
- Overlays may be hidden during raw pen transactions if needed, then restored from state.

### 4. Android UI Layer

Normal Views managed by the IME service.

Contains:

- toolbar
- sidebar
- mode controls
- popups
- handles

Rules:

- Finger interaction must remain first-class.
- Raw pen regions must not cover toolbar/sidebar hit targets.
- UI visibility changes should notify the render manager so it can update exclude/limit regions for non-canvas backends.

## Backends

The backend is a policy choice, not a tool behavior.

```kotlin
enum class RenderBackend {
    Canvas,
    AppPen,
    SfRaw,
    EpdDirect,
    AppTouch
}
```

### Canvas

Default backend.

Behavior:

- no `TouchHelper`
- no `EpdController` stroke path
- no EPD turbo during strokes
- normal Android `Canvas` rendering

Use for:

- default shipping behavior
- eraser reliability
- sidebar reliability
- structured overlays
- regression baseline

### AppPen

SDK-managed app-level pen path.

Behavior:

- `TouchHelper.create(view, 4, callback)`
- Scrawl forwards normal `MotionEvent`s into `touchHelper.onTouchEvent(event)`
- SDK path draws through `EpdController.moveTo/quadTo`
- input is still Android dispatch, not `/dev/input` raw reader

Use for:

- future low-latency pen preview experiment
- IME-compatible fast path candidate

Risks:

- must verify eraser and sidebar composition
- must ensure Scrawl still commits strokes through its own model
- current source has the backend flag but does not forward MotionEvents to TouchHelper

### SfRaw

Native raw TouchHelper path.

Behavior:

- `TouchHelper.create(view, 2 or 3, callback)`
- `SFTouchRender` uses `RawInputReader` and `EpdPenManager`
- pausing render can call `leaveScribbleMode`, which may clear/corrupt the visible raw layer

Use for:

- Activity-window experiments
- native raw latency research

Avoid for:

- default IME behavior
- toolbar/sidebar-heavy interactions
- structured overlays

### EpdDirect

Scrawl-owned direct calls to `EpdController.moveTo/quadTo/penUp`.

Use for:

- research only
- comparing against AppPen

Risks:

- hidden/platform API behavior varies by device
- may not render from IME windows
- still bypasses normal Canvas composition

### AppTouch

Base SDK app render path.

Behavior:

- useful mostly for non-pen/finger events because base `AppTouchRender` intercept logic excludes stylus

Use for:

- not useful for Scrawl stylus input unless a future device changes the SDK behavior

## Render Transactions

The render manager should expose transactions with explicit lifecycle and backend policy.

```kotlin
sealed class RenderTransaction {
    data class LiveStrokeBegin(...)
    data class LiveStrokeMove(...)
    data class LiveStrokeCommit(...)
    data class EraseCommit(...)
    data class OverlayUpdate(...)
    data class ModelInvalidate(...)
    data class FullRepaint(...)
    data class RawPenPauseRefreshResume(...)
}
```

Transactions should be serializable enough to test. They do not need to persist to disk, but they should be inspectable in fake tests.

### Canvas Transaction

```text
input event
-> update active tool state
-> mutate model if commit point
-> invalidate dirty rect
-> onDraw renders persistent model + live preview + overlays
```

No raw pen pause/resume is allowed in Canvas mode.

### Raw Pause/Refresh/Resume Transaction

Used only for raw/EPD backends when Scrawl must reconcile the raw layer with the model layer.

```text
pause raw pen
redraw model layer
request EPD refresh if needed
resume raw pen after backend delay
```

Boox Notes uses this pattern through `InvalidateViewWithPenControlAction`.

Default delay:

- monochrome: `150ms`
- color: `500ms`

The delay is a backend policy, not a hardcoded tool behavior.

### Commit Transaction

Pen-up, erase-up, undo, redo, clear, and annotation changes all become commits.

Every commit must define:

- model mutation
- affected dirty region
- whether active live ink should be discarded
- whether overlays should remain visible
- whether raw pen control is required

## Tool Policy

Tools should express intent and desired preview type, not backend calls.

| Tool | Model Commit | Preferred Preview | Notes |
| --- | --- | --- | --- |
| Pen | Stroke | Canvas by default, AppPen experiment later | Live preview is disposable |
| Eraser | Remove/hide stroke geometry | Canvas cursor/preview | Correctness over latency |
| Lasso | Selection path | Canvas overlay | Never raw-only |
| Tag | Annotation rect | Canvas overlay | Needs exact UI composition |
| Wiki | Annotation rect | Canvas overlay | Same as tag |
| Header | Annotation rect | Canvas overlay | Same as tag |
| Screenshot | Capture rect | Canvas overlay | Must avoid raw layer ambiguity |
| Sidebar | Annotation UI state | Android Views + Canvas highlights | Raw pen must not own this |
| Toolbar | Service/UI state | Android Views | Finger-first |

## Dirty Regions

The render manager should own dirty-region tracking.

Dirty-region inputs:

- stroke bounds plus pen width
- eraser radius/path bounds
- annotation rects
- old and new overlay bounds
- undo/redo affected bounds
- viewport resize
- backend transition

Policy:

- Use dirty redraw for local changes when the model layer can be replayed safely.
- Use full redraw after backend transitions, raw-layer loss, clear-all, major viewport changes, or uncertain eraser geometry.
- Expand dirty rects conservatively for EPD artifacts.

## Raw Pen Region Policy

For non-canvas backends, raw pen must have a clear region contract.

Inputs:

- writing canvas bounds
- toolbar bounds
- sidebar bounds
- popup bounds
- system inset bounds
- active tool requirements

Outputs:

- raw drawing limit rects
- raw drawing exclude rects if the backend supports them
- fallback to one stable writing rect if multi-region support is unreliable

Rules:

- Do not reconfigure raw regions on every minor UI movement if it causes flicker.
- Reconfigure at stable interaction boundaries.
- If exact exclusion is unavailable, prefer Canvas backend over allowing raw pen to consume toolbar/sidebar interactions.

## Proposed Interfaces

### ScrawlRenderManager

```kotlin
interface ScrawlRenderManager {
    fun setBackend(backend: RenderBackend)
    fun setViewport(viewport: RenderViewport)
    fun setUiRegions(regions: UiRegions)

    fun beginStroke(point: InputPoint, style: StrokeStyle)
    fun updateStroke(point: InputPoint)
    fun commitStroke(point: InputPoint)
    fun cancelStroke(reason: CancelReason)

    fun beginErase(point: InputPoint, style: EraserStyle)
    fun updateErase(point: InputPoint)
    fun commitErase(point: InputPoint)

    fun showOverlay(overlay: RenderOverlay)
    fun updateOverlay(overlay: RenderOverlay)
    fun clearOverlay(key: OverlayKey)

    fun invalidateRegion(region: RectF, reason: InvalidateReason)
    fun invalidateModel(reason: InvalidateReason)
    fun fullRepaint(reason: InvalidateReason)
}
```

### RenderBackendController

```kotlin
interface RenderBackendController {
    val backend: RenderBackend

    fun attach(view: View)
    fun detach()

    fun beginLiveStroke(point: InputPoint, style: StrokeStyle): BackendResult
    fun updateLiveStroke(point: InputPoint): BackendResult
    fun endLiveStroke(point: InputPoint): BackendResult
    fun cancelLiveStroke(): BackendResult

    fun pauseForModelRepaint(reason: InvalidateReason): RawPenPauseToken?
    fun resumeAfterModelRepaint(token: RawPenPauseToken)

    fun updateRegions(regions: RawPenRegions)
    fun supportsMotionEventForwarding(): Boolean
    fun onMotionEvent(event: MotionEvent): Boolean
}
```

### CanvasRenderer

```kotlin
interface CanvasRenderer {
    fun drawModel(canvas: Canvas, model: DocumentModel, dirty: RectF?)
    fun drawLiveStroke(canvas: Canvas, stroke: LiveStroke?)
    fun drawOverlays(canvas: Canvas, overlays: List<RenderOverlay>)
}
```

### DirtyRegionTracker

```kotlin
interface DirtyRegionTracker {
    fun mark(rect: RectF)
    fun markStroke(stroke: Stroke)
    fun markOverlay(old: RenderOverlay?, new: RenderOverlay?)
    fun consume(): RectF?
    fun forceFull()
}
```

## State Machine

The render manager should have explicit state.

```text
Idle
  -> DrawingStroke
  -> Erasing
  -> StructuredOverlay
  -> Repainting
  -> RawPaused
```

Important transitions:

```text
Idle -> DrawingStroke
  begin stroke preview

DrawingStroke -> Repainting
  commit stroke into model
  repaint dirty region if needed

Erasing -> Repainting
  mutate model
  redraw affected area

Any -> RawPaused
  only for non-canvas backend when model repaint must reconcile raw layer

RawPaused -> Idle
  after repaint and delayed raw resume
```

Invalid transitions should be logged and ignored or coerced into a full repaint.

Examples:

- receiving `updateStroke` in `Idle` should not crash
- receiving `commitErase` while raw resume is pending should queue or force a full repaint
- switching backend mid-stroke should cancel live preview, commit/cancel according to tool policy, and full repaint

## Testing Strategy

The Boox compositor itself is not a good unit-test target. Scrawl should test its own policy and state transitions.

### JVM/Robolectric Tests

Use fake backend controllers.

Test:

- Canvas backend never calls raw pause/resume.
- Eraser commit mutates model and requests dirty repaint.
- Undo/redo requests repaint.
- Full repaint clears live preview but preserves model.
- Backend switch cancels live preview and invalidates.
- Toolbar/sidebar region update reaches backend policy but does not mutate the model.

### Fake TouchHelper Tests

Wrap `TouchHelper` behind a small interface and fake it.

Assert call order:

```text
setRawDrawingEnabled(false)
repaint model
schedule resume delay
setRawDrawingEnabled(true)
```

Also assert Canvas mode never creates the wrapper.

### Transaction Tests

Given a tool action sequence, assert emitted transactions:

```text
draw stroke -> commit -> dirty repaint
draw stroke -> erase hit -> model erase -> dirty repaint
draw stroke -> undo -> model restore -> repaint
open sidebar -> raw region update or Canvas no-op
```

### Device Smoke Tests

Run on Boox hardware.

Minimum checks:

- install build
- set backend pref
- confirm selected IME
- draw pen stroke
- erase stroke
- tap/drag sidebar
- inspect logcat for backend markers

Backend-specific checks:

- `canvas`: no `TouchHelper initialized`, no `EPD turbo`, eraser works
- `app_pen`: MotionEvents are forwarded to TouchHelper, model still commits
- `sf`: raw pause/resume is logged and toolbar/sidebar regressions are documented

## Migration Plan

### Phase 1: Stabilize Canvas Through a Render Manager

No behavior change intended.

- Create `ScrawlRenderManager`.
- Move Canvas redraw, live stroke preview, eraser repaint, undo/redo repaint, and clear-all repaint behind it.
- Keep `render_backend=canvas` as default.
- Add transaction/state tests.

### Phase 2: Isolate Boox Raw/EPD Calls

- Wrap `TouchHelper` in `BooxTouchHelperController`.
- Wrap direct `EpdController` calls in `BooxEpdDirectController`.
- Move `pauseAndRefresh` into backend policy.
- Remove direct TouchHelper manipulation from tool code.

### Phase 3: Reintroduce AppPen as an Experiment

- Implement MotionEvent forwarding only inside `AppPenBackendController`.
- Keep model commit identical to Canvas.
- Compare latency, eraser, sidebar, and toolbar behavior on device.
- Keep Canvas as fallback.

### Phase 4: Structured Tool Overlays

- Route tag/wiki/header/screenshot/lasso previews through `OverlayRenderer`.
- Make overlays independent from raw pen.
- Add dirty-region tests for overlay movement and commit.

### Phase 5: Optional Raw SF Path

- Keep behind a debug setting.
- Use only if it can satisfy toolbar/sidebar and eraser requirements.
- Treat failure as acceptable; do not let it shape core architecture.

## Non-Goals

- Do not clone Boox Notes' private notecore stack.
- Do not depend on hidden Notes classes.
- Do not make raw TouchHelper the source of truth.
- Do not require root/system privileges.
- Do not optimize latency before correctness.
- Do not build tool behavior around untestable compositor assumptions.

## Practical Rule

Every feature should work correctly in Canvas mode first. A faster backend may improve live preview latency, but it must be removable without changing the document model or tool semantics.
