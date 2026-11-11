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
      "whatMoved": "Changed cases: the twelve 'SW5 X1 window controls are legible over every surface with <palette> <mode>, and in forced colours' cases; the other 123 cases are unchanged. Each of the twelve still measures, for its palette and mode, all twelve surfaces (running app; start-up frame with styles blocked, with styles, without a saved copy; failure page with styles blocked, with styles, without a saved copy; hand-off; hand-off without a saved copy; connecting; engine recovery; factory reset) x the three controls x the six states (icon, focus ring, hover, pressed, focus ring + hover, focus ring + pressed) x normal and forced colours, with the identical in-page measurement (fill, surface beneath composited over Canvas, icon and ring ratios, focus-visible ring presence) and the identical thresholds: icon at least 4.5:1, ring at least 4.5:1 alone and at least 3:1 against both fill and surface with a fill, and a missing ring is a failure. Unchanged assertions: three labelled controls on every surface; no start-up frame on the running app; the saved frame copy equals the applied palette's --text-primary; both hand-offs still show the frame with the palette stylesheet applied; the case's failure list is empty. Timeout (90 s) unchanged; no sleeps added. Added wait: engine recovery is measured once the saved palette's stylesheet (style[data-theme-id=<palette>], the condition the hand-offs already waited on) is attached. What moved: (1) States are entered with Chromium's DevTools protocol CSS.forcePseudoState instead of pointer travel: focus ring = focus + focus-visible, hover = hover, pressed = hover + active, and the combined states add them together. All three controls take a state at once (their rules depend on no other element's state) and one page.evaluate measures all three, after one pointer move away from the controls per surface, as the old icon state did. (2) Real input is still evidence: across the twelve cases every surface is also driven once by the old keyboard and pointer sequences (Shift then focus for the ring, real mouse move/down/up released away from the control), on one control (case k: surface k, control k mod 3), in all six states in normal and forced colours, and those real-input measurements must deep-equal the forced-state ones (realInputMeasures, called from measureSurface in these cases). (3) Pages: the running app and the hand-off without a saved copy take their colours from the app's tokens, so each is booted once per mode and every case applies its palette to it through frameProbe.applyAppearance, the app's own appearance writer the running app already used. The start-up frame and failure page variants and the hand-off paint the saved frame copy that the early HTML script reads once at boot, connecting and factory reset paint before the saved palette is restored, and engine recovery restores it while the app boots behind it, so these still boot per palette with that palette's storage, as before. (4) Engine recovery waits for the restored palette: it mounts with the default tokens and restoreAppearanceOnBoot injects the saved palette 31-119 ms later. The baseline did not wait and raced that restore (its real input usually, not always, measured after it): in two instrumented baseline runs 2 and 4 engine-recovery measurements came before the palette, for example core.registrars-ledger dark Minimize icon measured 42 ms after the mount with the palette at 68 ms, icon 10.837 on the default tokens where the settled paint is 9.322. No animations or transitions ran and no hover or focus was left from a previous state on any measured row. Evidence: a temporary dump in shared clones of every measured value, with the baseline given the same one wait on engine recovery (diagnostic only), gave the same 5184 keys (12 cases x 12 surfaces x 3 controls x 6 states x 2) with 0 differing values; against the unwaited baseline the only differences are its own unsettled engine-recovery measurements (3 values in that run). Planting a low-contrast pressed ink in normal colours failed all twelve cases on exactly 12 surfaces x 3 controls x the two pressed states; planting a Canvas focus ring in forced colours failed all twelve cases on exactly 12 surfaces x 3 controls x the three ring states.",
      "evidence": "gates/test-intent/evidence/boot-frame-speed.json"
    }
  ]
}
```

## Checks at handback

- Full file, this machine (other sessions kept a load average of about 5 to 14 on 20 CPUs): baseline
  234.4 s, slowest case 15.2 s; candidate 136.9, 113.9 and 114.7 s in three consecutive runs, all
  135/135, slowest case 5.5 s. Under load created by this session (each suite pinned to 4 CPUs, two
  of them saturated, both suites at once on disjoint CPUs): baseline 309.5 s, slowest 17.0 s;
  candidate 186.2 s, slowest 7.4 s; both 135/135.
- The unchanged case `SA6 shell shortcuts neither run nor queue beneath the frame, then normal
  shortcuts work` passed 10/10 on each tree with 4 CPUs (2 saturated) and 10/10 on each tree with 2
  saturated CPUs; it runs before any changed code. The single failure reported under load was not
  reproduced on either tree.
- Planted defects (low-contrast pressed ink; Canvas ring in forced colours) still fail all twelve
  cases with 72 and 108 failures each.
- `npm run typecheck`, `npm run lint`, `git diff --check`: passed. `.agents/preflight` is absent.

## Acceptance records

Pending independent review. The builder has not recorded acceptance.

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "boot-frame-round3-verifier-20261111",
  "verdict": "accept",
  "digest": "sha256:f9ab2d288b990553857ad6b6b726ca4303430784bb9f7a4e3eb749537da75910",
  "note": "Independent round 3: all 5184 settled measurements match; the 15 raw differences exactly coincide with early baseline engine-recovery samples. Real Tab and pointer input matches CDP styles and compositor pixels on all 12 surfaces. Both planted faults rejected by all 12 cases on both trees. Full suites, controlled load, and ten loaded SA6 repetitions pass; reverse palette order preserves all measurements."
}
```
