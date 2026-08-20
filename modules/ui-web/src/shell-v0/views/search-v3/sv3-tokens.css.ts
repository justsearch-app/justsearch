// SPDX-License-Identifier: Apache-2.0
/**
 * sv3Tokens — the Search v3 window's token sheet (tempdoc 822 slice 1).
 *
 * Derived from a third-party design system (MIT) — see THIRD-PARTY-NOTICES.md in this directory.
 *
 * The whole sheet is scoped to the WINDOW HOST, never `:root`: custom properties inherit down
 * through every nested shadow root, so a host-scoped declaration reaches the window's whole tree
 * while the shipped app's `:root` palette stays untouched. Dark is the default set (`:host`);
 * the light set sits behind `:host([theme='light'])`, and the attribute is WRITTEN by the window
 * host from the app's own appearance authority (`SearchV3View.theme`, 852 S4) — the seam this sheet
 * carried unwired from slice 1 until then.
 *
 * Three tiers, flowing one way: T0 primitives → T1 semantic roles → T2 geometry/material.
 * A component reads T1/T2 only. A theme change is a T1 redefinition, never a component edit.
 */
import { css } from 'lit';

export const sv3Tokens = css`
  :host {
    color-scheme: dark;

    /* ── T0 primitives ─────────────────────────────────────────────────────
       The design spec's palette resolves through Tailwind v4's built-in scale, which is not part
       of its source tree; these are the Tailwind v4 default values, pinned here as literals. */
    --color-white: oklch(100% 0 0);
    --color-zinc-25: oklch(99.2% 0 0);
    --color-zinc-50: oklch(98.5% 0 0);
    --color-zinc-100: oklch(96.7% 0.001 286.375);
    --color-zinc-200: oklch(92% 0.004 286.32);
    --color-zinc-300: oklch(87.1% 0.006 286.286);
    --color-zinc-500: oklch(55.2% 0.016 285.938);
    --color-zinc-800: oklch(27.4% 0.006 286.033);
    --color-zinc-900: oklch(21% 0.006 285.885);
    --color-neutral-100: oklch(97% 0 0);
    --color-neutral-500: oklch(55.6% 0 0);
    --color-neutral-950: oklch(14.5% 0 0);
    --color-red-400: oklch(70.4% 0.191 22.216);
    --color-red-500: oklch(63.7% 0.237 25.331);
    --color-red-700: oklch(50.5% 0.213 27.518);
    --color-blue-400: oklch(70.7% 0.165 254.624);
    --color-blue-500: oklch(62.3% 0.214 259.815);
    --color-blue-700: oklch(48.8% 0.243 264.376);
    --color-emerald-400: oklch(76.5% 0.177 163.223);
    --color-emerald-500: oklch(69.6% 0.17 162.48);
    --color-emerald-700: oklch(50.8% 0.118 165.612);
    --color-amber-400: oklch(82.8% 0.189 84.429);
    --color-amber-500: oklch(76.9% 0.188 70.08);
    --color-amber-700: oklch(55.5% 0.163 48.998);
    /* The reference brand hue is not copied; the primary is JustSearch's own teal accent
       (h 180), in the app's dark/light contrast pairing. */
    --color-teal-accent: oklch(75% 0.15 180);
    --color-teal-accent-ink: oklch(22% 0.06 180);

    /* ── T1 semantic roles — dark (the window's default) ───────────────────
       Dark surfaces are white-at-low-alpha over a near-black base, so every one composites
       correctly over whatever sits behind it; elevation is a color-mix ladder, not a shadow
       ladder. Light (below) inverts that: opaque named grays, separated by shadow. */
    --background: var(--color-neutral-950);
    --app-chrome-background: var(--background);
    --foreground: var(--color-neutral-100);
    --card: color-mix(in srgb, var(--background) 97%, var(--color-white));
    --card-foreground: var(--color-neutral-100);
    --popover: color-mix(in srgb, var(--background) 94%, var(--color-white));
    --popover-foreground: var(--color-neutral-100);
    --secondary: color-mix(in srgb, var(--color-white) 4%, transparent);
    --secondary-foreground: var(--color-neutral-100);
    --surface-raised: var(--secondary);
    --muted: color-mix(in srgb, var(--color-white) 4%, transparent);
    --muted-foreground: color-mix(in srgb, var(--color-neutral-500) 90%, var(--color-white));
    /* NAME, not just value: the shipped app's GLOBAL sheet defines '--accent' as a COLOUR
       (styles/tokens.css:124 — 'var(--accent-tint)') and shipped components read it as one
       (components/chat/ToolCallCard.ts:245 paints a link with it). A window-scoped sheet that
       re-uses that name for a 4%-white FILL silently rewrites every nested shipped component that
       reads it — the link would paint at 4% opacity. The window's hover material therefore carries
       a name the shipped vocabulary does not use (tempdoc 822 Phase F9, audit DEFECT-6). */
    --accent-surface: color-mix(in srgb, var(--color-white) 4%, transparent);
    --accent-surface-foreground: var(--color-neutral-100);
    /* Selection material (tempdoc 822 citation-mark presentation §5.1). The design spec’s command-palette idiom
       (the design spec: 6% selected / 9% highlighted, and never two competing fills). The
       MARK takes the upper rung and the large sentence region the lower: the spec spends 6% on a
       full-width row, and a ~13x14px glyph is a fraction of that area — an alpha wash loses
       legibility as the area it covers shrinks. Law 11 improve-don't-copy, deviation stated rather
       than drifted into. Keyed to --foreground, so the same three declarations catch light in dark
       and cast shadow in light (the spec’s elevation-inversion rule, by construction) — which is
       repeats them verbatim rather than inverting them, exactly as --dialog-border does. */
    --sv3-selected: color-mix(in srgb, var(--foreground) 9%, transparent);
    --sv3-selected-region: color-mix(in srgb, var(--foreground) 5%, transparent);
    --sv3-selected-edge: color-mix(in srgb, var(--foreground) 14%, transparent);
    /* The CARD rung. A source card already carries a fill, and a fill sits BEHIND TEXT:
       raising it to 9% measured three NEW serious axe color-contrast failures on the card body
       (3.63:1, axe A/B against the live window), while 5% was invisible. A border carries no text,
       so the card spends its selection signal on the EDGE instead of the fill. */
    --sv3-selected-edge-strong: color-mix(in srgb, var(--foreground) 34%, transparent);
    /* The two SUBDUED citation tiers, lifted so they survive the 9% wash painted behind a selected
       mark. The design named this remedy in advance (§7.5 — "the weak tier's colour moves, not the
       wash") and the first cut shipped the wash without it: the grey tier measured 5.08 resting and
       4.22 SELECTED, under the AA floor at the exact moment the reader clicks to check. The move is
       toward --foreground, the same anchor the wash itself is keyed to, so the lift tracks the theme
       instead of being a second hand-picked grey; and it is the SMALLEST step that clears the floor
       with margin, so a weak mark still reads as weak (4.93:1 selected, against 6.24:1 for a normal
       blue mark on the same wash). The amber tier needs no move in dark — it already computes
       9.55:1 on the wash — so it is named at its resting value rather than nudged for symmetry. */
    --sv3-cite-weak: color-mix(in srgb, var(--muted-foreground) 90%, var(--foreground));
    --sv3-cite-ungrounded: var(--warning-foreground);
    --primary: var(--color-teal-accent);
    --primary-foreground: var(--color-teal-accent-ink);
    /* Three named intents on one value: a future divergence is a one-line change, not a grep. */
    --placeholder: var(--muted-foreground);
    --secondary-label: var(--muted-foreground);
    --icon-muted: var(--muted-foreground);
    --border: color-mix(in srgb, var(--color-white) 6%, transparent);
    --input: color-mix(in srgb, var(--color-white) 8%, transparent);
    --ring: var(--primary);
    --toolbar-background: var(--app-chrome-background);
    --toolbar-foreground: var(--foreground);
    --toolbar-border: var(--border);
    --toolbar-control: var(--popover);
    --toolbar-control-foreground: var(--foreground);
    --toolbar-control-hover: var(--accent-surface);
    /* Status is a fixed five, each with a foreground and three with a surface tint. The tints are
       roughly double their light-mode strength: a low-alpha tint over near-black barely reads. */
    --error: color-mix(in srgb, var(--color-red-500) 90%, var(--color-white));
    --error-foreground: var(--color-red-400);
    --error-surface: color-mix(in srgb, var(--error) 16%, transparent);
    --destructive: var(--error);
    --destructive-foreground: var(--error-foreground);
    --info: var(--color-blue-500);
    --info-foreground: var(--color-blue-400);
    --success: var(--color-emerald-500);
    --success-foreground: var(--color-emerald-400);
    --warning: var(--color-amber-500);
    --warning-foreground: var(--color-amber-400);
    --warning-surface: color-mix(in srgb, var(--warning) 16%, transparent);
    --update: var(--primary);
    --update-foreground: var(--primary);
    --update-surface: color-mix(in srgb, var(--update) 18%, transparent);
    --sidebar: var(--card);
    --sidebar-foreground: var(--foreground);
    --sidebar-muted-foreground: var(--muted-foreground);
    --sidebar-control-surface: var(--muted);
    --sidebar-row-hover: var(--accent-surface);
    --sidebar-row-active: var(--accent-surface);
    --sidebar-row-selected: var(--muted);
    --sidebar-border: var(--border);
    /* Dialog material (slice 4). The palette is the window's one dialog surface, and its glass is a
       DIFFERENT recipe from the composer's: a dialog is the densest tint in the system and, unlike
       every other dark surface, keeps its drop shadow — it has to separate from a live window behind
       it, so it catches light on the top edge AND casts. The backdrop is one formula in both modes. */
    --dialog-backdrop: color-mix(in srgb, var(--background) 60%, transparent);
    --dialog-backdrop-blur: 4px;
    --dialog-border: color-mix(in srgb, var(--color-white) 8%, transparent);
    --dialog-shadow:
      inset 0 1px rgb(255 255 255 / 4%), 0 24px 72px -20px rgb(0 0 0 / 90%);
    /* Dropdown material (Phase F10 — the composer's control menu). The design spec's THIRD glass
       recipe: elevated glass needs a denser tint than an ambient surface,
       so the user's opacity setting is nested INSIDE an 18% popover tint — which remaps the range
       (40% → 51%, 80% → 84%, 100% → 100%) so a menu stays legible over busy content even at its most
       transparent. The technique and its reason are the spec's; the comment is kept because the
       number 18 is meaningless without it. No saturate() here, unlike the composer's own glass. */
       Tempdoc 859 §B (D2): the nested opacity is derived from '--glass-blur-scale' for the same
       reason the composer's glass is — one multiplier drives blur AND translucency, so a menu can
       never end up see-through with nothing blurred behind it. Scale 1 keeps 80%; scale 0 gives
       100%, and the outer 18% tint over an opaque base is then simply opaque. */
    --dropdown-surface: color-mix(
      in srgb,
      var(--popover) 18%,
      color-mix(
        in srgb,
        var(--popover) calc(100% - (100% - var(--glass-opacity)) * var(--glass-blur-scale)),
        transparent
      )
    );
    --dropdown-border: color-mix(in srgb, var(--foreground) 10%, transparent);
    --dropdown-shadow: 0 16px 40px -18px rgb(0 0 0 / 55%);
    /* The empty-state tile's edge is the elevation inversion at its smallest: a hairline BELOW the
       tile in light, ABOVE it in dark. */
    --empty-tile-shadow: none;
    --empty-tile-edge: 0 -1px rgb(255 255 255 / 6%);

    /* Composer material. The design spec expresses dark mode as dark-class RULES on the component; a
       selector inside a shadow root cannot see a class on the document element, so the whole
       inversion is carried as tokens instead (the spec's own recommendation). Dark catches light —
       a 1px inset top highlight and NO drop shadow; light casts one down. */
    --composer-glass-surface: color-mix(in srgb, var(--background) 96%, var(--color-white));
    --composer-outline: color-mix(in srgb, var(--color-white) 5%, transparent);
    --composer-shadow: none;
    --composer-highlight: inset 0 1px rgb(255 255 255 / 3%);
    /* The USER message's fill ('bg-message' / 'text-message-foreground'). Both are pure
       indirections off tokens the light block already redefines, so the light theme inherits the
       inversion without a second declaration — a light copy of an identical value would be a fork
       waiting to drift. The response block deliberately has NO surface of its own: the spec gives
       the assistant plain content on the panel, which is what makes the user's turn the only thing
       with a fill and therefore readable as the punctuation of the transcript. */
    --message-surface: var(--accent-surface);
    --message-foreground: var(--foreground);
    /* The primary action's material is one indirection off --primary, so a future accent change
       reaches the send button without touching it. */
    --message-action: var(--primary);
    --message-action-foreground: var(--primary-foreground);
    --message-action-hover: color-mix(in srgb, var(--primary) 90%, var(--background));
    /* A filled control's press physics: a top highlight at rest that FLIPS dark while pressed, with
       the drop shadow dropped at the same time, so the control presses INTO the surface. */
    --control-inset-highlight: inset 0 1px rgb(255 255 255 / 16%);
    --control-inset-pressed: inset 0 1px rgb(0 0 0 / 8%);

    /* ── T2 geometry / material ────────────────────────────────────────────
       Semantic on purpose: sidebar, palette, tooltip and toolbar controls cannot quietly drift
       apart, because they read the same names. Enforced by sv3-tokens.test.ts. */
    --control-radius: 0.5rem;
    --sidebar-width: 16rem;
    /* The collapsed ICON rail (the spec's icon sidebar width — 3rem). Its own token
       rather than a literal because the grip's position reads it too, and a boundary drawn somewhere
       other than where the panel ends is the one drift this file exists to prevent. */
    --sidebar-width-icon: 3rem;
    /* The citation pane's width (tempdoc 822 Phase F8; the spec's preview panel — 540px). Its
       own token for the sidebar's reason: the pane's grip is positioned from the same number the
       region is sized by, and a boundary drawn somewhere other than where the region ends is the one
       drift this file exists to prevent. The window writes a chosen width over it INLINE. */
    --pane-width: 33.75rem;
    --sidebar-content-inset: 0.5rem;
    --sidebar-control-gap: 0.5rem;
    --sidebar-row-content-inset: 0.625rem;
    --sidebar-icon-color: color-mix(in srgb, var(--sidebar-muted-foreground) 60%, var(--sidebar));
    --command-shell-inset: 0.5rem;
    --command-content-inset: 1rem;
    /* The palette popup's box (max-h-105 / max-w-xl). */
    --command-popup-max-height: 26.25rem;
    --command-popup-max-width: 36rem;
    /* The spec's scroll-fade band is 2.5rem over a chat timeline and cut to 1.5rem for its one DENSE
       list sitting directly under its own chrome, "so the fade stays while the controls start near
       the chrome" — which is exactly the palette list. */
    --command-scroll-fade-height: 1.5rem;
    /* The composer field's two floors. The spec ships ONE editor at 70px and swaps the whole block
       out for a single truncating line when the composer is compact; the window keeps a typable field
       in both forms, so the compact form is expressed as a one-LINE floor instead. The ceiling is
       shared, which is what keeps a docked draft growing. */
    --composer-field-min-hero: 4.375rem;
    --composer-field-min-docked: 1lh;
    --composer-field-max: 12.5rem;
    --floating-content-inset: 0.75rem;
    --workspace-topbar-height: 52px;
    --workspace-controls-top: 0px;
    /* Plain constants until Tauri window metrics feed them; the reference Electron window-controls
       env() values do not port, only this token indirection does. */
    --workspace-controls-left: 0.75rem;
    --workspace-controls-right: 0.75rem;
    --workspace-native-controls-inset: 0px;
    --workspace-titlebar-control-size: 1.75rem;
    --workspace-titlebar-control-gap: 0.75rem;
    --desktop-window-right-resize-inset: 0px;
    /* Dark gets more blur and less saturation: on near-black, blur needs radius to read as
       depth and a saturation boost reads as garish. */
    --glass-blur: 16px;
    --glass-opacity: 80%;
    --glass-saturation: 1.08;
    --app-scrollbar-width: 6px;
    --app-scrollbar-thumb: rgb(255 255 255 / 8%);
    --app-scrollbar-thumb-hover: rgb(255 255 255 / 12%);

    /* ── The occluded band (tempdoc 859 §B) ────────────────────────────────
       What the FLOATING dock (context bar + composer) takes out of the bottom of the transcript
       scroller's client box. The scroller pads and scroll-pads by it, so content stays reachable
       and every browser-driven scroll lands where the reader can see; 'SearchV3View' overwrites it
       with the dock's MEASURED height on every resize, in the docked state only.

       The default is deliberately NON-ZERO and deliberately a static estimate of the docked dock's
       resting height. A '0' default is the trap: on a platform with no 'ResizeObserver' nothing
       would ever write the variable, and the transcript would be clipped by exactly the band this
       slice exists to un-clip — silently, and only there. An estimate that is a little large costs
       a little extra bottom padding; a zero costs the feature. */
    --sv3-composer-occlusion: 7rem;

    /* ── Radius ladder — one knob, additive ────────────────────────────────
       --radius shifts the whole window's roundness while the 4px differences between tiers
       hold. --control-radius above is the SECOND, independent knob: controls are not surfaces. */
    --radius: 0.625rem;
    --radius-sm: calc(var(--radius) - 4px);
    --radius-md: calc(var(--radius) - 2px);
    --radius-lg: var(--radius);
    --radius-xl: calc(var(--radius) + 4px);
    --radius-2xl: calc(var(--radius) + 8px);
    --radius-3xl: calc(var(--radius) + 12px);
    --radius-4xl: calc(var(--radius) + 16px);

    /* ── Reading measure ───────────────────────────────────────────────────
       The design spec's transcript column is 'max-w-3xl'. It reuses the
       SHIPPED concept name (styles/tokens.css:352) rather than minting a second measure vocabulary,
       and it is the COLUMN's property — which is why it is spent on the window's own '.answer' box
       and not inside the shared renderer (tempdoc 822 §2.5). */
    --measure-prose: 48rem;

    /* ── Z-scale (an improvement on the spec, which has none) ──────────────
       Tooltips deliberately sit above dialogs. */
    --z-content: 0;
    --z-sticky: 10;
    --z-overlay: 20;
    --z-dialog: 50;
    --z-tooltip: 70;
    --z-toast: 100;

    /* ── Spacing ladder (an improvement: 4px steps, not inlined calc) ─────── */
    --space-1: 4px;
    --space-2: 8px;
    --space-3: 12px;
    --space-4: 16px;
    --space-5: 20px;
    --space-6: 24px;
    --space-7: 28px;
    --space-8: 32px;
    --space-9: 36px;
    --space-10: 40px;
    --space-11: 44px;
    --space-12: 48px;
    /* The spec's ladder is Tailwind's 0.25rem scale, which carries half-steps; the window's densest
       regions spend all three (palette item / input-row / group-label py-1.5 and footer py-2.5; the
       transcript's response block py-0.5). They are named here rather than inlined so the ladder
       stays the one authority. */
    --space-0-5: 2px;
    --space-1-5: 6px;
    --space-2-5: 10px;
    /* An improvement on the spec: the 1px border is taken out of the padding ONCE, here, instead of being
       re-derived at every control size, so a control's visual inset equals the spacing step. */
    --control-pad-3: calc(0.75rem - 1px);

    /* ── Type ──────────────────────────────────────────────────────────────
       No shipped face: the platform stack, four effective sizes, weights 400/500/600. */
    --font-sans: -apple-system, BlinkMacSystemFont, 'Segoe UI', system-ui, sans-serif;
    --font-mono: ui-monospace, 'SF Mono', 'SFMono-Regular', Menlo, Consolas, 'Liberation Mono',
      monospace;
    /* The BADGE rung, and only that: the design spec's desktop ramp table gives "Badge sm" its own
       10px (sm:text-[.625rem]). It sits below the four-size UI ramp
       the way the display size sits above it, so the ramp itself is untouched. */
    --font-size-sv3-2xs: 0.625rem;
    --font-size-sv3-xs: 0.75rem;
    --font-size-sv3-sm: 0.875rem;
    --font-size-sv3-base: 1rem;
    --font-size-sv3-lg: 1.125rem;
    --font-size-sv3-xl: 1.25rem;
    /* The hero headline is the ONE display size outside the four-size UI ramp, and the one place the
       desktop ramp steps UP rather than down. */
    --font-size-sv3-display: 1.875rem;

    /* ── Motion budget ─────────────────────────────────────────────────────
       Effectively two values (micro / layout), with one reserved for the signature morph.
       Enter eases out, exit eases in, a drag-tied geometric change is linear. */
    --duration-sv3-micro: 150ms;
    --duration-sv3-layout: 200ms;
    --duration-sv3-morph: 180ms;
    --ease-sv3-enter: ease-out;
    --ease-sv3-exit: ease-in;
    --ease-sv3-linear: linear;
    --ease-sv3-morph: cubic-bezier(0.32, 0.72, 0, 1);
  }

  :host([theme='light']) {
    color-scheme: light;

    --background: var(--color-zinc-25);
    --app-chrome-background: var(--background);
    --foreground: var(--color-zinc-800);
    --card: var(--color-white);
    --card-foreground: var(--color-zinc-800);
    --popover: var(--color-white);
    --popover-foreground: var(--color-zinc-800);
    --secondary: var(--color-zinc-50);
    --secondary-foreground: var(--color-zinc-800);
    --surface-raised: color-mix(in srgb, var(--card) 20%, transparent);
    --muted: var(--color-zinc-50);
    --muted-foreground: var(--color-zinc-500);
    --accent-surface: var(--color-zinc-100);
    --accent-surface-foreground: var(--color-zinc-900);
    /* The same three, restated so the light palette is complete at its own selector rather than
       leaning on the dark block (the --dialog-border precedent below). Identical by construction:
       --foreground is what inverts, so the wash inverts with it. */
    --sv3-selected: color-mix(in srgb, var(--foreground) 9%, transparent);
    --sv3-selected-region: color-mix(in srgb, var(--foreground) 5%, transparent);
    --sv3-selected-edge: color-mix(in srgb, var(--foreground) 14%, transparent);
    /* The CARD rung. A source card already carries a fill, and a fill sits BEHIND TEXT:
       raising it to 9% measured three NEW serious axe color-contrast failures on the card body
       (3.63:1, axe A/B against the live window), while 5% was invisible. A border carries no text,
       so the card spends its selection signal on the EDGE instead of the fill. */
    --sv3-selected-edge-strong: color-mix(in srgb, var(--foreground) 34%, transparent);
    /* The tier lift, restated at this selector because the numbers are NOT the dark set's. Light is
       the worse case: both subdued tiers already sat near the floor at rest (grey 4.71, amber 4.90)
       and fell through it under the wash (3.97 / 4.14). The mix runs toward --foreground here too —
       which is a DARK ink in this palette, so the same declaration darkens where the dark set
       brightens — and each percentage is the smallest 5% step clearing 4.8:1 on the composite. */
    --sv3-cite-weak: color-mix(in srgb, var(--muted-foreground) 80%, var(--foreground));
    --sv3-cite-ungrounded: color-mix(in srgb, var(--warning-foreground) 85%, var(--foreground));
    --primary: oklch(45% 0.18 180);
    --primary-foreground: oklch(99% 0.01 180);
    --placeholder: var(--muted-foreground);
    --secondary-label: var(--muted-foreground);
    --icon-muted: var(--muted-foreground);
    --border: var(--color-zinc-200);
    --input: var(--color-zinc-300);
    --ring: var(--primary);
    --toolbar-control: var(--popover);
    --toolbar-control-hover: var(--accent-surface);
    --error: var(--color-red-500);
    --error-foreground: var(--color-red-700);
    --error-surface: color-mix(in srgb, var(--error) 8%, transparent);
    --destructive: var(--error);
    --destructive-foreground: var(--error-foreground);
    --info: var(--color-blue-500);
    --info-foreground: var(--color-blue-700);
    --success: var(--color-emerald-500);
    --success-foreground: var(--color-emerald-700);
    --warning: var(--color-amber-500);
    --warning-foreground: var(--color-amber-700);
    --warning-surface: color-mix(in srgb, var(--warning) 8%, transparent);
    --update: var(--primary);
    --update-foreground: var(--primary);
    --update-surface: color-mix(in srgb, var(--update) 12%, transparent);
    --sidebar: var(--color-zinc-50);
    --sidebar-control-surface: var(--color-zinc-100);
    --sidebar-row-hover: var(--color-zinc-25);
    --sidebar-row-active: var(--color-white);
    --sidebar-row-selected: var(--color-white);
    --composer-glass-surface: var(--card);
    --composer-outline: rgb(0 0 0 / 8%);
    --composer-shadow: 0 12px 28px -18px rgb(0 0 0 / 40%);
    --composer-highlight: none;
    --dialog-border: color-mix(in srgb, var(--foreground) 10%, transparent);
    --dialog-shadow: 0 24px 64px -24px rgb(0 0 0 / 65%);
    --empty-tile-shadow: 0 1px 2px 0 rgb(0 0 0 / 5%);
    --empty-tile-edge: 0 1px rgb(0 0 0 / 4%);

    --glass-blur: 12px;
    --glass-saturation: 1.14;
    --app-scrollbar-thumb: rgb(217 217 217);
    --app-scrollbar-thumb-hover: rgb(191 191 191);
  }
`;
