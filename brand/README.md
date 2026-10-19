# JustSearch brand assets

**The rule: identity surfaces derive from these files. A surface never invents brand.** If a screen,
installer page, icon slot or document needs the mark, it takes an existing output of
`brand/generate.mjs` — or gets a new output added to it. Hand-drawing "close enough" artwork into a
surface is how a product ends up with five slightly different logos and no way to change any of them.

The mark is **"The Slot"**: a sealed mass with one slot cut through its wall, an answer-line standing
*outside* it, and a superscript footnote square. It draws the product's sentence — nothing leaves the
machine but the answer, and the answer leaves cited. Design record (directions A–D, the comparison
sheet, the self-critique), archived at the `archive/machinery-cleanup-base` tag:
[`docs/ui-explorations/2026-08-06-brand-identity/`](https://github.com/justsearch-app/justsearch/tree/archive/machinery-cleanup-base/docs/ui-explorations/2026-08-06-brand-identity).
Implementation record: [`docs/tempdocs/815-visual-identity-kernel.md`](../docs/tempdocs/815-visual-identity-kernel.md).

## What derives from what

```
mark-{light,dark}.svg ........ master, 64-unit artboard, footnote included
mark-small-{light,dark}.svg .. the dedicated <=24px cut, 16-unit grid
mark-24-{light,dark}.svg ..... the 24px raster frame, HAND-SNAPPED to whole pixels
lockup-dark.svg .............. mark + wordmark as live <text> (for HTML/SVG surfaces)
wordmark-outlines.svg ........ the wordmark as vector outlines (GENERATED, see below)
        |
        |  node brand/generate.mjs
        v
modules/shell/src-tauri/icons/32x32.png, 128x128.png, 128x128@2x.png   app icons (tauri bundle.icon)
modules/shell/src-tauri/icons/icon.ico                                 Windows icon, 5 frames
modules/shell/src-tauri/icons/icon.icns                                macOS icon, PNG payloads
modules/shell/src-tauri/nsis/sidebar.bmp                               NSIS Welcome/Finish, 164x314
modules/shell/src-tauri/nsis/header.bmp                                NSIS interior header, 150x57
modules/ui-web/public/favicon.svg                                      web/webview favicon
```

Everything below the arrow is **derived**: delete it and regenerate, never hand-edit it. Everything
above the arrow is **authored** — except `wordmark-outlines.svg`, which is produced once by
`brand/extract-wordmark.py` from Segoe UI Variable and committed so that `generate.mjs` needs no font
installed and no native image dependency.

## Regenerate

```bash
node brand/generate.mjs          # all derived assets; no dependencies, no npm install
```

Only if the wordmark itself changes (different string, weights, or typeface):

```bash
python brand/extract-wordmark.py   # rewrites wordmark-outlines.svg; needs fontTools + Windows fonts
node brand/generate.mjs
```

`node scripts/ci/check-installer-branding.mjs` is the gate: it fails the build if a wired asset is
missing, empty, the wrong pixel size, not actually a BMP, still the stock Tauri icon, or if the web
favicon has drifted back to Vite's logo.

## The size ladder

| Size | What renders | Source |
|---|---|---|
| **>= 48px** | master, footnote **included** | `mark-{light,dark}.svg` |
| **25–47px** | master, footnote **dropped** | `mark-{light,dark}.svg` minus `#footnote` |
| **<= 24px** | the dedicated cut — footnote gone, slot deepened, corners squared | `mark-small-{light,dark}.svg` |
| **exactly 24px raster** | the hand-snapped frame | `mark-24-{light,dark}.svg` |

The 16-unit cut is pixel-exact at 16px and 32px. At **24px** the 1.5× scale puts every odd coordinate
on a half-pixel, so the `.ico`'s 24px frame is a separately authored, hand-snapped file — three edges
nudged to whole pixels with the silhouette preserved (the full derivation is in the file's own
comment). At 16px and 24px the mark is drawn 1:1 over the full frame and the plate is sized around
it, so the snap survives: those two `.ico` frames carry **zero anti-aliased pixels inside the mark**
— every partial-alpha pixel in them is plate-coloured, i.e. the plate's own rounded corners (12 and
20 pixels respectively), and every opaque pixel is one of ink, accent or plate.

## Colour

| | Ink | Accent |
|---|---|---|
| Light ground | `#16181c` | `#00655B` |
| Dark ground | `#eceef1` | `#00CCB2` |

Mass = ink. Answer-line + footnote = accent. Ground is transparent. In single-colour contexts every
shape is ink.

**Icon-class assets carry their ground; everything else is plateless.** The mark is specified on a
near-white *or* near-black ground — a mid-tone collapses it. Surfaces that own their background (the
installer bitmaps, the app UI, a document page) supply a compliant one, so the mark sits on them
bare. An OS icon or a browser tab cannot: the ground is whatever the environment happens to be. So
`icon.ico`, `icon.icns`, the three app PNGs and `favicon.svg` — and only those — are drawn on a
near-black `#0e0f12` rounded plate (inset ≈ 1/16 of the frame, corner radius ≈ 1/8, both snapped to
whole pixels) with the dark colorway on top. Over light chrome the plate supplies the contrast; over
dark chrome it merges with the background and the mark reads as if plateless. This is packaging the
specified ground, not the glyph-in-rounded-app-square identity formula the mark's own self-critique
refused — the plate is not part of the mark, and `brand/*.svg` never contains it.

The NSIS **header** bitmap takes the *light* colorway (no plate) because MUI's header ground is
already white (`MUI_BGCOLOR` defaults to `FFFFFF`); the **sidebar** takes dark on its own `#0e0f12`
field. Because the plate insets the mark, the size ladder for icon-class assets keys on the
**rendered mark size**, not the frame: a 48px frame holds a 42px mark, which is below the footnote
threshold and correctly drops it.

## Wordmark and lockup

"JustSearch" is **one word**, weight-split — `Just` at ~650, `Search` at ~350 — in the system
grotesque (Segoe UI Variable stack). The mark never contains text. Below 32px the mark stands alone;
mark + wordmark is for installer and hero scale. `lockup-dark.svg` sets it as live `<text>` so web
surfaces get real text; fixed rasters use `wordmark-outlines.svg`, because an installer bitmap has to
look identical on a machine that has never heard of Segoe UI.
