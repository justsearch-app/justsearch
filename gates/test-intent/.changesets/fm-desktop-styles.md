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
      "whatMoved": "Palette themes, token previews and role foregrounds are now constructed stylesheets in document.adoptedStyleSheets instead of <style> elements in <head> (the desktop window's policy refuses <style> made at runtime). The observation moved from document.getElementById('jf-active-theme' / preview id).textContent, or a style[data-theme-id] selector, to the adopted sheet and its CSS (replaceSync input or cssRules). Inputs, preconditions, expected CSS content, sanitisation, validate-before-write, clearing, palette identity and the contrast and storage assertions are unchanged. bootAppearanceRestore changes only its cleanup."
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
      "whatMoved": "Swatch colours are now assigned through the element's style object instead of a style attribute. The test reads the value passed to the style setter instead of the raw attribute, because happy-dom's CSS parser rewrites colour values; the expected colours are unchanged."
    }
  ]
}
```

## Acceptance records
