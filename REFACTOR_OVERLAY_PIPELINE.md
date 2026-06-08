# Overlay / capture pipeline refactor

Splits "draw a translation control on screen" from "accessibility service", and folds
the now-triplicated still-image overlay (accessibility + assistant) and live-screen
overlay (MediaProjection) onto one shared control surface.

## Capabilities and the permissions they actually need

There are exactly three ways to obtain another app's pixels on stock Android:

| Mechanism | Needs | Cost | Fit |
|---|---|---|---|
| `AccessibilityService.takeScreenshot()` | a11y enabled (`canTakeScreenshot`) | no consent dialog, no recording indicator, rate-limited (~1/s) | **still** (manual refresh), unusable for continuous |
| MediaProjection | consent dialog + foreground service + casting notification | heavyweight | **live** (continuous) |
| Assist API (`SHOW_WITH_SCREENSHOT` → `onHandleScreenshot`) | app is default assistant, invoked via assist gesture | free screenshot, one-shot | **still** without a11y/MediaProjection |

There is no API to bind a custom overlay button press to a system screenshot delivered
to the app. `PixelCopy` only captures windows the app itself owns. So:

| Capability | Minimum permission | Why |
|---|---|---|
| **Live** (continuous) | *draw over other apps* + MediaProjection consent | both grabbed by `ScreenCaptureRequestActivity`; no a11y, no assistant |
| **Still** (one-shot OCR) | **a11y OR assistant role** | only screenshot sources that aren't MediaProjection |

`draw over other apps` is the permission the live *overlay window* needs, not a button
feature. A standalone overlay-permission-only button could only start live, which a
Quick Settings tile / in-app button does better — so we do not build one. The
magic-sparkle accessibility-tree text path and the assistant `AssistStructure` text
path are both deleted; still = screenshot OCR from either source.

## Triggers

- **a11y floating button** (idle launcher dot, `TYPE_ACCESSIBILITY_OVERLAY`, no
  draw-over-apps needed) → *translate once* (a11y screenshot) / *go live*.
- **assistant gesture** → setting *action on invocation* picks *still* (free
  `onHandleScreenshot` bitmap) or *live* (fire `ScreenCaptureRequestActivity`, hide session).
- **live**: Quick Settings tile + in-app button → `ScreenCaptureRequestActivity`. No intent filter.

## One collapsible control widget

Expanded = a single flat toolbar row (lang pills always first, ✕ always last);
collapsed = the `FloatingBubble` dot. Mode only swaps the tail and what ✕ tears down.

```
a11y-still:       [EN▾][ES▾]  [↕ order]  [↻ refresh] [⇄ go-live]  [✕]
assistant-still:  [EN▾][ES▾]  [↕ order]              [⇄ go-live]  [✕]
live:             [EN▾][ES▾]  [⤢ region] [↕ order]  [⏸ pause]     [✕]
```

| State | Surface | ✕ does |
|---|---|---|
| a11y idle | launcher dot → {translate once, go live} | — |
| a11y-still active | toolbar (no collapse) | clear blocks → idle dot |
| assistant-still active | toolbar (no collapse) | finish session |
| live running | toolbar, collapsible to dot (keeps running) | stop projection + service |

- **`refresh` (a11y) ≡ `pause` (live)** are the same control over different sources:
  a11y native cadence is manual frame-advance (takeScreenshot is rate-limited); live
  cadence is continuous and pause freezes the current frame (a frozen frame is a still).
- **Region** and **collapse-while-active** are **live-only**. Region was a crutch for
  weak OCR; full-screenshot OCR is good enough now. The shared in-memory crop region is
  therefore a live-only concept shared across the live entry points.
- Minimize = done (still has no collapse-to-read state); the dot is only ever the a11y
  idle launcher or live-minimized.

Icons are Material: go-live = videocam, region = existing, pause = material pause.

## Phases

0. **Deletions** — `AssistStructureParser`/`Logger` + structure path; a11y-tree sparkle
   (`handleTranslateVisible`/`collectVisibleStyledFragments`/`extractStyledFragmentsAtPoint`);
   wand button from `OverlayChromeFactory` + callers; drop `canRetrieveWindowContent`.
1. **Capture source** — `StillCaptureSource` (`A11yScreenshot` refreshable /
   `AssistScreenshot` one-shot); host-agnostic still translate controller; drop region from still.
2. **Unified toolbar** — `OverlayChromeFactory` widget = expanded chrome + collapsed
   `FloatingBubble`, `ToolbarMode` tail, ✕ injected by host.
3. **Hosts** — `OverlayHost` per window type; rewire `OverlayUI` / session /
   `ScreenTranslateService`; unify block rendering on `OverlayRenderer`.
4. **Live entry points** — `LiveScreenTileService` + in-app button → `ScreenCaptureRequestActivity`.
5. **Settings** — `AssistantAction{StillImage,LiveScreen}`; single-source the live crop region.
6. **Verify** — `:app:compileDebugKotlin`, `./run_phone.sh`.
