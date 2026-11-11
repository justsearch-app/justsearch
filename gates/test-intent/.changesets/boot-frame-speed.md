---
schema: test-intent.v1
task: t-20261111-boot-frame-speed
author-role: builder
author-session: boot-frame-speed-builder-20261111-b
---

# Boot-frame window-control legibility: forced states and reused pages

Only the twelve cases `SW5 X1 window controls are legible over every surface with <palette> <mode>,
and in forced colours` (six shipped palettes × dark and light) change. The other 123 cases and every
helper they use are byte-identical. Timings, the value-by-value comparison with the baseline and the
planted-defect runs are in `gates/test-intent/evidence/boot-frame-speed-timings.json`.

```json
{
  "entries": [
    {
      "items": ["modules/ui-web/src/boot/boot-frame-lockdown.test.ts"],
      "class": "Adaptation",
      "moved": ["instrumentation"],
      "whatMoved": "Changed cases: the twelve 'SW5 X1 window controls are legible over every surface with <palette> <mode>, and in forced colours' cases; the other 123 cases are unchanged. Each of the twelve still measures, for its palette and mode, all twelve surfaces (running app; start-up frame with styles blocked, with styles, without a saved copy; failure page with styles blocked, with styles, without a saved copy; hand-off; hand-off without a saved copy; connecting; engine recovery; factory reset) x the three controls x the six states (icon, focus ring, hover, pressed, focus ring + hover, focus ring + pressed) x normal and forced colours, with the identical in-page measurement (fill, surface beneath composited over Canvas, icon and ring ratios, focus-visible ring presence) and the identical thresholds: icon at least 4.5:1, ring at least 4.5:1 alone and at least 3:1 against both fill and surface with a fill, and a missing ring is a failure. Unchanged assertions: three labelled controls on every surface; no start-up frame on the running app; the saved frame copy equals the applied palette's --text-primary; both hand-offs still show the frame with the palette stylesheet applied; the case's failure list is empty. Timeout (90 s) unchanged; no sleeps added. What moved: (1) States are entered with Chromium's DevTools protocol CSS.forcePseudoState instead of pointer travel: focus ring = focus + focus-visible, hover = hover, pressed = hover + active, and the combined states add them together. All three controls take a state at once (their rules depend on no other element's state) and one page.evaluate measures all three, after one pointer move away from the controls per surface, as the old icon state did. (2) Real input is still evidence: across the twelve cases every surface is also driven once by the old keyboard and pointer sequences (Shift then focus for the ring, real mouse move/down/up released away from the control), on one control (case k: surface k, control k mod 3), in all six states in normal and forced colours, and those real-input measurements must deep-equal the forced-state ones (realInputMeasures, called from measureSurface in these cases). (3) Pages: the running app, the hand-off without a saved copy and engine recovery take their colours from the app's tokens, so each is booted once per mode and every case applies its palette to it through frameProbe.applyAppearance, the app's own appearance writer the running app already used. The start-up frame and failure page variants and the hand-off paint the saved frame copy that the early HTML script reads once at boot, and connecting and factory reset paint before the saved palette is restored, so these still boot per palette with that palette's storage, as before. Evidence: a temporary dump in shared clones of every measured value at the baseline (real input, fresh pages) and the candidate gave the same 5184 keys (12 cases x 12 surfaces x 3 controls x 6 states x 2) with 0 differing values. Planting a low-contrast pressed ink in normal colours failed all twelve cases on exactly 12 surfaces x 3 controls x the two pressed states; planting a Canvas focus ring in forced colours failed all twelve cases on exactly 12 surfaces x 3 controls x the three ring states.",
      "evidence": "gates/test-intent/evidence/boot-frame-speed.json"
    }
  ]
}
```

## Checks at handback

- Full file, this machine (other sessions kept a load average of about 3 to 7 on 20 CPUs): baseline
  220.7 s, slowest case 13.2 s; candidate 101.9 s, slowest case 3.7 s. Under the same load created
  by this session (suite pinned to 4 CPUs, two of them saturated): baseline 286.4 s, slowest 15.5 s;
  candidate 155.5 s, slowest 6.7 s.
- Five consecutive candidate runs passed 135/135: 106.7, 106.1, 101.6, 109.2, 144.0 s.
- `npm run typecheck`, `npm run lint`, `git diff --check`: passed. `.agents/preflight` is absent.

## Acceptance records

Pending independent review. The builder has not recorded acceptance.
