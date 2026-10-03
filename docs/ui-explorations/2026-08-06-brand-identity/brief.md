# JustSearch Brand Identity Exploration — Round 1 Brief (2026-08-06)

## What this is

A request for ONE distinct brand-identity direction for JustSearch, produced as a set of
self-contained HTML preview cards. This is exploration, not a redesign: the founder will react
to four parallel directions and pull from them. Your direction's thesis is assigned in your
task prompt — commit to it fully; do not hedge toward the middle.

## The product (facts, use verbatim where copy is needed)

- **Name:** JustSearch. The name carries three readings — *merely* search (modesty),
  *simply* search (ease), *just* as in justice (fits the legal/rights-adjacent audience).
  You may exploit this in the wordmark; you may not change the name.
- **Tagline (approved):** "A private retrieval backend for your AI agents — and a neural
  search engine with a cited, on-device AI assistant."
- **Spine (approved hero copy):** "Your AI agent can search your code. It can't search the
  rest of your work — the thousands of PDFs, emails, scanned records, and notes in every
  language that are your actual knowledge — not without shipping your private files to
  someone's cloud. JustSearch gives your agent cited answers from your own messy files, on
  your machine, in 70+ languages."
- **Proof pillars you may state:** privacy mechanically enforced (loopback-only, no
  telemetry — "provable, not promised"); multilingual by construction (70+ languages, zero
  per-language tuning); cited answers; everything on-device.
- **HARD honesty constraint:** invent NO other claims, numbers, benchmarks, or testimonials.
  The entire brand is "we don't overclaim." A prototype that fakes a stat betrays the brand
  it's prototyping.

## The brand direction already agreed (all four directions live inside this)

**Quiet, precise, instrument-like — the honest-design counterpoint to AI-hype aesthetics.**
The founder is German; the product sells EU data sovereignty; the verbal brand is built on
reproducibility and refusing to overclaim. The shared anti-reference: the generic AI-product
look - purple-to-pink gradients, sparkle/wand iconography, glow, cosmic imagery, overexcited
copy. None of that, in any direction.

Audiences the identity must not alienate: (1) developers wiring JustSearch into AI agents
via MCP — the attention beachhead; (2) confidentiality-constrained professionals (lawyers,
therapists, journalists) — the eventual paying users, who read "hacker terminal" as risk.

## Existing visual DNA (context, not obligation)

The shipped app uses a token system self-titled "Local Data OS": OKLCH palette, near-black
surfaces (#0a0a0c-ish), teal primary accent (oklch 75% 0.15 h180), purple=command,
green=success, amber=warning; glassmorphism (blur 20px translucency); fonts: system-ui stack
+ 'Plus Jakarta Sans' display + 'JetBrains Mono'. The current app icon is a generic
magnifying glass — considered weak; a mark about *containment/on-device* (the differentiator)
is preferred over *searching* (the commodity). Your direction may inherit, evolve, or reject
this DNA as its thesis demands.

## Deliverables — exactly these 5 files, in your assigned output directory

Each file is ONE preview card: a single self-contained HTML file. First line MUST be
`<!-- @dsCard group="<Your direction name>" -->` (exact literal comment, group = your
direction's short name from the task prompt).

1. `01-wordmark.html` — the wordmark/logotype and the mark (symbol). Show the mark alone,
   the lockup, and a one-line rationale under each. The mark must be drawn as inline SVG.
2. `02-palette-type.html` — palette swatches (with hex/oklch values labeled) + typography
   specimen (families, weights, a scale). State light/dark strategy in one sentence.
3. `03-hero.html` — a landing/README hero mock: wordmark, spine copy (verbatim from above),
   one CTA, and whatever supporting visual your thesis calls for. This is the money card.
4. `04-app-chrome.html` — a mock of the actual app window (left rail, top command bar, result
   list, bottom status bar) re-skinned in your direction. Mid-fidelity; fake plausible file
   results; no invented product features.
5. `05-icon-sheet.html` — the mark rendered at 16, 32, 64, 128, 256 px (SVG scaled), on both
   a light and dark backdrop, plus a favicon-in-tab and a Windows-taskbar simulation strip.

## Hard constraints (product-derived, non-negotiable)

- **Fully self-contained HTML.** No network requests of any kind: no CDN, no Google Fonts,
  no external images. System font stacks, inline SVG, data: URIs only. (If your thesis wants
  a distinctive typeface, name it in a comment + specimen note and render its closest system
  fallback.)
- **WCAG AA contrast** for all text in the mock.
- **Never imply cloud/sync.** No cloud icons, no "sign up", no server imagery presented
  positively.
- **Each card sized for a preview pane:** design at ~1100px wide max, no horizontal page
  scroll; the app-chrome and hero cards may be taller.
- Desktop-app reality: Windows-first, frameless window, lives on a desktop — not a webpage.

## What success looks like

A direction with a strong, internally consistent point of view the founder can *react* to —
including react against. Distinctiveness beats safety. A direction that could be mistaken
for a generic SaaS template is a failure even if it is tasteful.
