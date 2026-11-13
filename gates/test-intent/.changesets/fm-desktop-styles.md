---
schema: test-intent.v1
task: t-20261113-fmimpl
author-role: builder
author-session: a7ab93d3-dd3c-4b69-acee-c8d1dcfbc2c2
---

# Test-intent entries

Per-element styles and runtime stylesheets moved to mechanisms the desktop window's content policy allows; these tests observe the same outcomes through the new mechanism.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/boot/boot-frame-lockdown.test.ts",
        "modules/ui-web/src/shell-v0/plugin-api/HostApiTheme.test.ts",
        "modules/ui-web/src/shell-v0/state/bootAppearanceRestore.test.ts",
        "modules/ui-web/src/shell-v0/state/presentationState.test.ts",
        "modules/ui-web/src/shell-v0/state/themeState.test.ts",
        "modules/ui-web/src/shell-v0/themes/builtinPaletteLoading.test.ts",
        "modules/ui-web/src/shell-v0/themes/tokenIntrospection.test.ts",
        "modules/ui-web/src/shell-v0/views/LibrarySurface.themeContrast.test.ts",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.ownActions-lockdown.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "Palette themes, token previews and role foregrounds are now constructed stylesheets in document.adoptedStyleSheets instead of <style> elements in <head> (the desktop window's policy refuses <style> made at runtime). The observation moved from the attached element's text (getElementById(...).textContent, a style[data-theme-id] selector, or element presence) to the current text of each sheet in document.adoptedStyleSheets: a per-test wrapper of CSSStyleSheet replaceSync/replace records each sheet's latest text (happy-dom drops @layer rules from cssRules), and assertions read only adopted sheets, so a later overwrite, a detached sheet or a second palette sheet is seen. A sheet counts as a palette (or role-foreground) sheet from its first write with that @layer on, whatever its later text, so a retained sheet is never counted as absent: exactly-one-element assertions became exactly-one-adopted-palette-sheet, removed-element became no adopted palette sheet. Edits through the sheet's CSSOM rules (insertRule, rule.style.setProperty) are not observed; the base observation did not see them either (a <style> element's textContent does not follow CSSOM edits), and the product writes these sheets only with replaceSync. Inputs, preconditions, expected CSS content, sanitisation, validate-before-write, clearing, palette identity and the contrast and storage assertions are unchanged. bootAppearanceRestore changes only its cleanup."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/renderers/controls/OptionButtonGroupRenderer.test.ts",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.highContrast.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "The rendered style is now assigned through the element's style object (a .style property binding) instead of a style attribute string, so the browser serialises it (spacing after ':' and ';'). The expected declarations and values are unchanged; only the whitespace of the read-back string differs."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/PresentationGallerySurface.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "Each swatch's colour is now set as the custom property --swatch-tint through the element's style object (the .swatch rule reads background: var(--swatch-tint)) instead of a background in a style attribute. The test reads the final value with swatch.style.getPropertyValue('--swatch-tint') per card (happy-dom keeps custom property values verbatim; it rewrote oklch backgrounds). The expected colours and their per-card association are unchanged."
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a1d5d8-f223-71e0-8642-9495621e1624",
  "verdict": "reject",
  "digest": "sha256:9343c84161c187821b09342ca82ef58990c0570ed9edf460eb628f02e82ef912",
  "note": "Entry 1 weakens themeState.test.ts loader attachment: replaceSync input no longer proves adoption. Entry 3 reads the first swatch style write instead of the final state and misses later incorrect overwrites."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verifier-85a33e0467-intent-20261113",
  "verdict": "reject",
  "digest": "sha256:7d3c5f4e5c413b4d6eb4ad30e25331ea17637c5c765a387635b243f4db5021ab",
  "note": "Entry 1 weakens two oracles: presentationState.test.ts checks the first palette replaceSync write, so a later incorrect overwrite of that adopted sheet passes; themeState.test.ts replaces the exact-one stylesheet assertion with equality to the previous adopted list, so two retained palette sheets pass. Entries 2 and 3 preserve their expected values."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a1d5e5-5b03-7873-8d90-9a8c9ba2a220",
  "verdict": "reject",
  "digest": "sha256:f528129397610f91419ce553ff6fc18065c6ddd6a25dc83e04df646acd2998b9",
  "note": "Entries 1 and 3 weaken final-state oracles: presentationState, themeState and LibrarySurface.themeContrast can accept earlier layer writes after a later overwrite; builtinPaletteLoading checks adoption and expected CSS on different sheets; PresentationGallerySurface misses later CSSOM background writes. Entry 2 preserves its oracle."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "test-intent-c4743e6bf6-verifier-slot4",
  "verdict": "reject",
  "digest": "sha256:757a6e1406af014834d957ed9c36430462a7b80521f0092ba6fa297f3bf8519f",
  "note": "Entry 1 weakens the oracle: sheetText records replace/replaceSync input, not current CSSOM text; paletteCss/roleCss exclude retained adopted sheets once their text loses the layer prefix, so clear/no-write checks can pass with CSS still adopted. Native Chromium probe reproduced stale colour after CSSOM setProperty and false absence for an adopted non-layer rule. Entries 2 and 3 preserve their assertions. See oracle-probe.mjs and oracle-probe.txt."
}
```
