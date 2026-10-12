# Raw Input IME Architecture Plan

> Current architecture direction: see `docs/scrawl-render-manager-architecture.md`.
> This document is still useful as research history, but some implementation notes here
> predate the Canvas-first render backend and the Boox Notes reverse-engineering pass.

This document describes what a ground-up rewrite should preserve from the current Scrawl writing workflow, and how to restructure the app so low-latency raw ink and flexible tool previews stop fighting each other.

Radial menu behavior is intentionally treated as optional. The parity target here is the core writing, annotation, eraser, toolbar, and commit workflow.

## Feature Parity Target

The current product is not just a low-latency canvas. The rewrite should preserve these workflows.

### Writing

- Stylus opens in write mode by default for normal text fields.
- Finger users can still interact with UI without accidentally writing.
- Strokes are accumulated for HWR and rendered with pressure-sensitive Jetpack Ink in the model/backing bitmap.
- Pen choice and width settings are preserved.
- Pressure sensitivity remains configurable.
- Undo/redo works for strokes and annotation regions.
- Strokes survive IME switches and process death through persistence.
- HWR commit inserts text through `InputConnection` and handles line collapsing/newline heuristics.

History pointers:

- `b636afd` optimized the original Canvas path for latency.
- `b66711b` added TouchHelper raw EPD drawing with Canvas fallback.
- `55047e9` simplified the mode model and defaults to pen-active write mode.
- `6874b3e` / `eda6aa0` made stroke persistence robust.
- `038ba62` added redo and guarded against Notes-app freezes during commit.

Code pointers:

- `IMECanvasView.startStroke`, `addPoint`, `finishStroke`
- `IMECanvasView.createRawInputCallback`, `addRawPoint`, `finishRawStroke`
- `InkRendering.kt` for pressure curve and Jetpack Ink rendering
- `ScrawlIMEService.commitCanvas`

### Annotation

Scrawl annotations are semantic regions, not literal text drawn on the canvas. The user draws a region over handwriting, then commit-time HWR wraps the recognized words.

Parity requirements:

- `[[wikilink]]` annotation button.
- `#tag` annotation button.
- Header button cycling H1-H4.
- Screenshot region mode.
- Annotation regions are undoable and erasable.
- Tag and wikilink wrapping preserves the current text processing behavior:
  - wikilinks keep spaces
  - tags concatenate words
  - leading punctuation/space stays outside the markup
  - headers prepend Markdown hashes
- Commit-time annotation mapping still uses full-text vs text-without-annotations diffing, with fallback per-annotation HWR when needed.

History pointers:

- `3c39219` added wikilink/tag annotations, visual overlays, eraser removal, and diff-based HWR wrapping.
- `d2287b7` tightened tag/wikilink prefix behavior.
- `eb1291a` added header annotations.

Code pointers:

- `AnnotationTextProcessor.kt`
- `AnnotationTextProcessorTest.kt`
- `IMECanvasView.AnnotationRegion`
- `IMECanvasView.drawAnnotationOverlay`
- `ScrawlIMEService.commitCanvas`

### Toolbar

The core toolbar workflow should remain even if radial menu support is dropped.

Parity requirements:

- Top bar for primary actions/mode.
- Bottom/write toolbar for undo, eraser, annotation buttons, screenshot, menu.
- Select toolbar for undo/backspace/space/enter/menu.
- Toolbars can be dragged to any edge and persist edge plus offset.
- Toolbar orientation changes with edge.
- Hamburger flyout preserves pen width, pressure sensitivity, settings, IME picker, recording, and related controls.
- Toolbar regions must be excluded from the raw writing area without per-tap raw pause/resume.
- Onboarding/tooltips must be allowed to complete before raw writing starts intercepting stylus input.

History pointers:

- `732f5e5` added movable sidebar bars and persisted offsets.
- `609e5b4` added edge-snapping draggable toolbars.
- `83bb5ec` split bottom tools by write/select mode.
- `7a042ba` added pen width and pressure sliders.
- `72417f4` added onboarding and first-open tooltips.

Code pointers:

- `ScrawlIMEService.createTopBar`
- `ScrawlIMEService.createBottomBar`
- `ScrawlIMEService.positionToolbarOnEdge`
- `ScrawlIMEService.updateRawDrawingRegion`
- `ScrawlSettings`

### Eraser

There are two useful eraser behaviors:

- Model eraser: remove strokes/annotations from Scrawl's source-of-truth model.
- Native preview eraser: show a fast dotted/dashed raw erase trace while the pen moves.

The current best native behavior comes from matching the Notate/Boox pattern: keep raw drawing alive and switch the driver into its eraser/dashed channel. Pausing or closing raw drawing around eraser toggles caused the raw layer to blank on Note Air 5C.

History pointers:

- `3f20bd2` introduced native EPD eraser mode and batched redraws.
- Current working-tree experiments refined this toward `Device.currentDevice().setEraserRawDrawingEnabled(true)`, `STROKE_STYLE_DASH`, and `EpdPenManager.PEN_ERASING`.

Code pointers:

- `IMECanvasView.syncTouchHelperForToolMode`
- `IMECanvasView.eraseAtBatched`
- `IMECanvasView.refreshAfterRawLayerChange`
- `ScrawlIMEService.toggleEraser`

## Lessons Learned

### TouchHelper Is Excellent for Ink, Poor as a General UI Renderer

TouchHelper raw rendering gives the best perceived latency, but it should be treated as a special-purpose ink layer. It does not compose like a normal Android `Canvas`.

Observed behavior:

- Raw ink can visually sit above normal Android canvas content.
- Canvas overlays may be drawn by `onDraw` but not appear over the raw layer immediately.
- Forcing broad refreshes can make raw ink disappear or expose stale model state.
- Toggling `setRawDrawingEnabled(false/true)` around toolbar taps can clear or corrupt the visible raw layer.

Current code examples:

- `IMECanvasView.onDraw` skips live Canvas preview while raw drawing is confirmed.
- `finishRawStroke` bakes model strokes after raw ink has already appeared.
- `refreshAfterRawLayerChange` avoids `restartRawDrawing` because it blanked the visible raw layer on Note Air 5C.

### Rectangle Exclusion Is Safer Than Dynamic Pause/Resume

Trying to pause raw drawing when the pen hits a toolbar let toolbar taps through, but it also caused raw layer clearing. The safer approach is to keep a stable writing rectangle that avoids toolbar rails.

Current code example:

- `ScrawlIMEService.updateRawDrawingRegion` computes one writing `Rect`.
- `IMECanvasView.configureRawDrawingRegion` applies it through `TouchHelper.setLimitRect`.

Gotcha: do this after toolbar layout and onboarding/tooltips. Starting raw input too early lets strokes leak into setup UI.

### Native Eraser Preview and Model Erasure Must Be Decoupled

The dotted eraser trace is a native preview. The actual erasure must still happen in Scrawl's model, then repaint the backing bitmap. If the native trace and model repaint are treated as one operation, switching modes can look like the whole canvas was cleared.

Current code example:

- Raw eraser callbacks call `eraseAtBatched`.
- On pen-up, Scrawl persists/redraws once.
- Raw drawing is kept active rather than restarted.

### Notes App Shape Support Is Not a Public TouchHelper Rectangle API

Boox Notes has shape/rectangle behavior, but decompilation points to a private notecore architecture:

- `UniversalShapeHandler.canPenRawRender()` returns `false`.
- Raw points feed `UniversalShapeEditor`.
- `RenderUniversalShapesAction` registers a Canvas renderer with `InkRenderManager`.
- Notes has `PauseRawPenAction`, `ResumeRawPenAction`, and `InvalidateScreenAction` to coordinate raw pen state around invalidation.

This is not equivalent to a public TouchHelper `drawRect` primitive. It is a larger editor/render-manager stack that Scrawl does not have.

Code references outside Scrawl:

- `/tmp/knote2_src/sources/com/onyx/android/note/note/handler/common/UniversalShapeHandler.java`
- `/tmp/knote2_src/sources/com/onyx/android/note/note/action/universal/RenderUniversalShapesAction.java`
- `/tmp/knote2_src/sources/com/onyx/android/note/note/action/render/InvalidateScreenAction.java`

### Notate Confirms the Split

Notate keeps hardware raw rendering for freehand, lasso selection, and lasso eraser, but uses software overlays/cursors for rectangle select and text tools.

Useful reference:

- `/tmp/notate/app/src/main/java/com/alexdremov/notate/ui/input/PenToolConfigurator.kt`

Pattern observed there:

- Lasso eraser/select: `setRawDrawingRenderEnabled(true)`, `setEraserRawDrawingEnabled(true)`, `STROKE_STYLE_DASH`.
- Rect select/text: `setRawDrawingRenderEnabled(false)`, software cursor/overlay.

## Proposed Architecture

The rewrite should make rendering ownership explicit instead of letting `IMECanvasView` infer behavior from flags.

### Three Layers

1. **Raw Ink Layer**
   - Owned by TouchHelper.
   - Used only for low-latency freehand pen and dotted/dashed native previews.
   - Never responsible for toolbar state, tag boxes, wikilink labels, selection handles, or screenshot overlays.

2. **Model Layer**
   - Source of truth.
   - Stores strokes, annotation regions, erasures, redo/undo history, and screenshot regions.
   - Raw layer is just a temporary display cache.

3. **Overlay/UI Layer**
   - Normal Android Canvas/View rendering.
   - Used for accurate rectangles, labels, toolbar state, selection handles, and screenshot regions.
   - Repainted only at safe points, not during raw freehand movement.

### Tool Controller

Introduce explicit tool objects instead of scattering mode checks across `IMECanvasView` and `ScrawlIMEService`.

Example shape:

```kotlin
interface CanvasTool {
    val renderPath: RenderPath
    fun enter()
    fun exit()
    fun onPenDown(point: PenPoint)
    fun onPenMove(point: PenPoint)
    fun onPenUp(point: PenPoint)
    fun drawOverlay(canvas: Canvas)
}

enum class RenderPath {
    TouchHelperRaw,
    CanvasTurbo,
    NoLivePreview
}
```

Each tool owns its raw/canvas lifecycle:

- `PenTool`: TouchHelper raw render, raw callbacks store model stroke, bake on pen-up.
- `EraserLassoTool`: TouchHelper dashed native preview, model erasure on pen-up, safe repaint.
- `TagTool`: either dashed TouchHelper gesture preview or Canvas turbo rectangle preview, then stores an annotation region.
- `WikiLinkTool`: same as tag.
- `HeaderTool`: same as tag, with mode cycling controlled by service.
- `ScreenshotTool`: Canvas turbo rectangle preview; raw TouchHelper disabled while active.
- `SelectTool`: normal Android interaction, no raw drawing.

### Recommended Tool Behavior

Use raw rendering only where raw rendering is actually better:

| Tool | Live preview | Source of truth | Notes |
| --- | --- | --- | --- |
| Pen | TouchHelper raw ink | Stroke model | Highest latency priority |
| Eraser | TouchHelper dash | Model erasure | Keep raw alive; do not pause/restart per tap |
| Tag | Canvas turbo rectangle or TouchHelper dash | Annotation region | Prefer accurate Canvas if preview correctness matters |
| Wikilink | Canvas turbo rectangle or TouchHelper dash | Annotation region | Same as tag |
| Header | Canvas turbo rectangle or TouchHelper dash | Annotation region | Header style applied at commit |
| Screenshot | Canvas turbo rectangle | Screenshot region | Needs accurate box |
| Toolbar | Android Views | Service state | Finger-first if raw pen is active |

The conservative first rewrite should use:

- TouchHelper raw for pen.
- TouchHelper dash for eraser.
- Canvas turbo for tag/wiki/header/screenshot previews.

That gives exact boxes where boxes matter, while keeping native raw speed for handwriting.

### Raw Session Manager

Centralize all Onyx calls in one class.

Responsibilities:

- Initialize TouchHelper once the writing region is valid.
- Apply `setLimitRect` only before opening raw drawing.
- Track whether callbacks are actually confirmed.
- Switch pen/eraser dash modes without closing raw drawing.
- Disable raw render cleanly for structured Canvas tools.
- Resume raw pen after structured tools at a controlled safe point.
- Own all `EpdController` update-mode calls.

This would replace direct TouchHelper calls in `IMECanvasView`.

Suggested API:

```kotlin
class RawInkSession {
    fun configureRegion(limit: Rect?)
    fun enterPen(style: PenStyle)
    fun enterEraserDash()
    fun pauseForStructuredTool()
    fun resumeAfterStructuredTool()
    fun deactivate()
}
```

Important constraint: `pauseForStructuredTool` should not be used for toolbar taps. Toolbar taps are too frequent and clearing-prone. Use stable writing-region margins or finger-first toolbar interaction instead.

### Commit-Time Model Flow

Keep commit logic model-driven:

1. Snapshot strokes and annotations.
2. Run HWR on all relevant strokes.
3. Run HWR without annotation regions or with per-region fallback as today.
4. Use `AnnotationTextProcessor` to wrap text.
5. Commit through `InputConnection`.
6. Only clear local model after successful commit.

The raw layer should never be treated as the committed document.

## Migration Plan

### Phase 1: Stabilize Current Native Path

- Keep raw TouchHelper pen.
- Keep native dashed eraser.
- Keep `updateRawDrawingRegion` as a single stable region.
- Avoid toolbar-pause/resume experiments.
- Add logs that identify the active tool and render path for every pen-down.
- Document that toolbar state refresh may lag while raw layer is active.

### Phase 2: Extract Model and Tool Boundaries

- Move strokes/annotations/undo/redo into a model object.
- Move live stroke accumulation into a tool-specific accumulator.
- Make `annotationMode` changes go through a tool controller.
- Keep existing `AnnotationTextProcessor` tests as parity checks.

### Phase 3: Introduce Raw Session Manager

- Move TouchHelper init, pen style sync, eraser dash sync, EPD fallback, and DU refresh into `RawInkSession`.
- Remove direct TouchHelper manipulation from service code.
- Add a small state machine:
  - `Inactive`
  - `RawPen`
  - `RawEraser`
  - `CanvasStructured`
  - `Select`

### Phase 4: Structured Tool Previews

- Start with tag/wiki/header/screenshot as Canvas turbo previews.
- On pen-up, store an annotation region and return to pen mode or remain in selected annotation mode, depending on UX.
- If Canvas preview latency is acceptable, stop there.
- If not, experiment with TouchHelper dash preview for tag/wiki/header while leaving screenshot as Canvas rectangle.

### Phase 5: Optional Native Shape Research

Only after the architecture is stable:

- Investigate whether any public `com.onyx.android.sdk.scribble.shape` APIs can be used outside Notes' notecore stack.
- Avoid relying on `/tmp/knote2_src` private classes directly.
- Treat Notes' `UniversalShapeHandler` as a design reference, not a dependency.

## Open Risks

- IME windows may always behave differently from Activity windows for raw EPD rendering.
- Raw layer compositing may remain firmware-dependent across Boox models.
- Toolbar visual state may never refresh perfectly without risking raw layer disturbance.
- Canvas turbo structured previews may feel slower than raw ink, but they are likely the only reliable path to accurate boxes.
- Hidden APIs and eac/whitelist paths are not shippable as normal app behavior.

## Near-Term Recommendation

For the next implementation pass:

1. Keep native TouchHelper for pen writing.
2. Keep native dashed eraser.
3. Do not pursue live raw rectangles yet.
4. Implement tag/wiki/header/screenshot as explicit structured tools.
5. Use Canvas turbo for their live rectangle preview.
6. Return to raw pen cleanly after structured tool pen-up.
7. Keep the commit pipeline unchanged and model-driven.

This gives Scrawl a clean internal boundary: raw mode is for low-latency ink, structured mode is for accurate UI. That is the same split implied by both Notate and Boox Notes.
