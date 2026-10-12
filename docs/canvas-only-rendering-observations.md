# Canvas-Only Rendering Observations

Date: 2026-05-05

This note records the current stable baseline and the debugging observations that led us to remove the Boox EPD/raw drawing path from Scrawl's runtime renderer.

It is intended for future agents or contributors who want to reintroduce selected render-manager/raw-pen ideas without confusing the layers.

## Current Stable Baseline

Commit:

```text
e25edda Remove EPD raw drawing path
```

Runtime rendering is Canvas-only:

- Stylus events enter `IMECanvasView.onTouchEvent`.
- Strokes are previewed in `IMECanvasView.onDraw`.
- Committed strokes live in Scrawl's model (`strokes`) and are redrawn from that model.
- Eraser mutates the same model and asks the render manager for a normal Android redraw.
- Toolbars and sidebars are normal Android views.
- Old `render_backend` prefs are treated as compatibility data; the runtime backend resolves to Canvas.

The intended runtime path is:

```text
MotionEvent
  -> IMECanvasView.handleStylusEvent
  -> startStroke/addPoint/finishStroke OR eraseAt
  -> Scrawl model
  -> ScrawlRenderManager.redrawModel / View.invalidate
  -> IMECanvasView.onDraw(Canvas)
```

The current baseline intentionally does not use:

- `TouchHelper`
- `RawInputCallback`
- raw drawing regions
- `EpdController.moveTo/quadTo`
- EPD turbo
- `HAND_WRITING_REPAINT_MODE`
- `EpdController.invalidate` / `refreshScreenRegion`
- `FEATURE_APP_PEN_TOUCH_RENDER`
- `FEATURE_SF_TOUCH_RENDER`

The remaining Onyx-related code in the project is handwriting recognition integration, not rendering.

## What We Observed

### 1. Canvas Model Erasure Was Working

Logs repeatedly showed the Canvas eraser entering the correct mode:

```text
stylus DOWN backend=Canvas eraserMode=true useEraser=true rawActive=false rawConfirmed=false appPenPreview=false
canvas ERASE removedStrokes=...
```

This meant toolbar mode and model mutation were not the main failure once the correct Canvas path was active.

### 2. Android Screenshots Did Not Always Match The Physical Panel

After erase, `adb exec-out screencap -p` often showed the erased strokes gone while the physical Boox panel still showed handwritten residue.

Interpretation:

- The stale handwritten pixels were not in Scrawl's model.
- They were not in `IMECanvasView.onDraw` output as captured by Android screenshot.
- They were not a normal SurfaceFlinger-visible app layer.
- The likely remaining source was a Boox display/handwriting update path below or adjacent to normal Android composition.

This was a major reason to remove all EPD/raw display hooks from the Canvas baseline.

### 3. `initEpdFallback()` Was A Liability For Canvas

The old fallback setup called Boox EPD APIs such as:

```text
setViewDefaultUpdateMode(HAND_WRITING_REPAINT_MODE)
enablePost(view, 1)
```

Even when `TouchHelper` was disabled, this could still place the Canvas view under Boox handwriting-oriented display behavior. That made debugging ambiguous: the code looked Canvas-only, but the display path was not necessarily plain Android View composition.

Canvas mode should not call EPD fallback setup.

### 4. Raw Region Setup Was Also A Liability

The previous IME service computed a writing rectangle around the toolbars and called `configureRawDrawingRegion(...)`.

That made sense for `TouchHelper`, but it was wrong for a clean Canvas baseline:

- It created misleading `rawDrawingRegion=...` logs.
- It kept raw-region concepts alive even when Canvas was supposed to be the only renderer.
- It encouraged future changes to solve toolbar interactions through raw-region shaping instead of normal Android hit testing.

Canvas mode should not configure raw regions at all.

### 5. AppPen Was Fast But Broke Toolbar/Sidebar Expectations

The `app_pen` path could produce faster visible ink, and logs confirmed TouchHelper/AppPen activation when the pref was written to the correct file (`scrawl_settings.xml`).

Observed tradeoff:

- Pen latency improved.
- Sidebar/tool interactions became unreliable.
- Eraser semantics were harder to reason about because visual ink and model ink could diverge.

This path may still be useful later as a live preview accelerator, but it must not own model correctness or toolbar state.

### 6. Progressive Bake Caused Disappearing Strokes

One disappearing-stroke bug came from progressive bake interacting with Path live preview. Later strokes could disappear until pen-up because only the tail was being previewed after part of the stroke had been baked.

The stable Canvas path disables progressive bake for Path live preview (`usePathLivePreview = true` keeps `pendingBake` inactive).

If progressive bake is reintroduced, it needs tests or instrumentation that verifies full-stroke visibility during long strokes.

## Layer Model To Preserve

Use this layer model when evaluating future changes.

### Model Layer

Source of truth:

- strokes
- annotations
- erasures
- undo/redo state
- screenshot/tag/wiki/header regions

Correctness means this layer is right after every operation.

### Canvas View Layer

Normal Android `View.onDraw(Canvas)` output:

- persisted model drawing
- current live path preview
- annotation overlays
- eraser visual affordances if added later

This layer is captured by Android screenshots.

### Android UI Layer

Normal Android views:

- top toolbar
- bottom toolbar
- sidebar/flyouts
- buttons and popups

This layer should remain interactive without render-backend-specific hacks.

### Vendor/EPD/Raw Layer

Anything created by Boox raw pen APIs or EPD handwriting APIs.

In the current stable baseline, this layer should not be touched by Scrawl rendering code.

If this layer is reintroduced later, it must be treated as a temporary preview layer only. It must not be the source of truth.

## Safe Reintroduction Strategy

Future work should pull pieces back slowly and behind explicit boundaries.

### Step 1: Keep Canvas As The Correctness Oracle

Do not change model mutation, eraser semantics, undo/redo, annotations, or toolbar behavior when experimenting with fast paths.

Any fast path must pass this rule:

```text
If the fast path is disabled mid-stroke or after pen-up, a Canvas redraw restores the correct document.
```

### Step 2: Reintroduce One Vendor API At A Time

Avoid bringing back `TouchHelper`, raw regions, EPD fallback, and EPD refresh calls together.

Suggested order:

1. Add instrumentation only: log SurfaceFlinger layer state, screenshots, and physical observations.
2. Add a dormant backend enum without runtime activation.
3. Add a live-preview-only AppPen experiment.
4. Add explicit teardown/reset for preview state.
5. Only then consider raw-region exclusion or EPD refresh calls.

### Step 3: Keep Toolbars Out Of Backend Policy

Toolbar buttons should update Canvas state directly:

```text
toggle eraser -> isEraserMode -> Canvas erase behavior
```

They should not pause/resume raw drawing, set pen states, or reconfigure native renderers.

If a future backend needs special handling, route it through a render manager API that takes semantic intent, not through toolbar code.

### Step 4: Require Layer Evidence

For each backend experiment, collect:

- Scrawl logs for mode/backend/tool state.
- `adb exec-out screencap -p` after draw/erase.
- Physical observation of the Boox panel.
- SurfaceFlinger layer list before and after first stroke.

Interpretation rule:

- If screenshot shows the stroke, it is in Android composition.
- If physical panel shows a stroke but screenshot does not, it is outside the normal Android screenshot path.
- If model count changes but screenshot does not, Canvas redraw/model logic is suspect.
- If screenshot is correct but panel is wrong, display refresh/vendor layer behavior is suspect.

## Useful Commit Points

Stable and experimental snapshots:

- `e25edda` - Canvas-only stable baseline with EPD/raw drawing removed.
- `86f51ea` - Experimental EPD refresh state checkpoint.
- `79ebfd3` - Render manager and Canvas stabilization baseline before the final raw removal.
- `06d0a12` - Earlier pure Canvas plus EPD turbo direction.
- `e1ac1b9` - AppPen direct EPD pen rendering experiment.
- `43b1098` - Tagged stable pre-native-HWR release (`v0.1.14`).

Use these as comparison points when peeling functionality back in.

## Current Recommendation

Keep `e25edda` as the known-good runtime baseline.

Do not reintroduce EPD fallback or raw regions just to improve refresh. Those were the most confusing parts because they made the code look Canvas-only while involving Boox-specific display behavior.

If a fast path returns, treat it as a preview accelerator owned by a narrow render-manager interface. Canvas model redraw remains the authority.
