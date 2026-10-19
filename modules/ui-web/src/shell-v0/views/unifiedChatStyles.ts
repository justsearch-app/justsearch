// SPDX-License-Identifier: Apache-2.0
import { css } from 'lit';
import { shortViewportMedia } from '../primitives/compositionLayout.js';

/**
 * Tempdoc 621 Phase 1 — the chat window's body styles, extracted verbatim from UnifiedChatView.ts.
 * Behaviour-neutral move: the host's `static styles` array still composes this alongside the imported
 * style modules and the in-host `composeGridStyles(CONVERSATION_ZONES)` call (the composition-surfaces
 * seam stays in the host file).
 */
export const unifiedChatBodyStyles = css`
    :host {
      display: flex;
      flex-direction: column;
      width: 100%;
      height: 100%;
      padding: 1rem;
      /* Tempdoc 814 §D1 — the surface's own inter-band gap is chrome, and chrome yields to the
         conversation. Both this gap and the answer-plane's below drop 0.75rem → 0.5rem; every pixel
         freed lands in .conversation-zone (the only flex:1 child of either column), which is what the
         registered content-share floor (governance/ui-proportion-baseline.v1.json, step chat-bands,
         .conversation-zone minShareOfSelector 0.55) asserts. */
      gap: 0.5rem;
      box-sizing: border-box;
      color: var(--text-primary);
      font-family: system-ui, -apple-system, sans-serif;
    }
    /* Tempdoc 585 §D Phase 1 (C1) — run-replay scrubber bar. */
    .replay-bar {
      display: flex;
      align-items: center;
      gap: 0.5rem;
      padding: 0.5rem 0.75rem;
      margin-bottom: 0.5rem;
      background: var(--surface-2);
      border: 1px solid var(--border-subtle);
      border-radius: 0.5rem;
      font-size: var(--font-size-sm);
    }
    /* Search Thread Round-2 R1a — the degradation banner (596 §11.4): the expanded multi-bullet
       form and the collapsed one-line form share this row/list layout. */
    .degradation-banner .notice-row {
      display: flex;
      align-items: flex-start;
      gap: 0.4rem;
      /* Tempdoc 816 §2 — the banner is FULL-BLEED CHROME: the <jf-system-notice> host keeps its
         edge-to-edge background/accent, while the content box inside it is content-sized at the
         \`notice\` role's measure (governance/ui-proportion-baseline.v1.json roles.notice, rendered
         from the same number via --measure-notice). Measured 1804px (209ch) at 1920 before this. */
      max-inline-size: var(--measure-notice);
      margin-inline: auto;
    }
    .degradation-banner .notice-row-collapsed {
      align-items: center;
    }
    .degradation-summary {
      flex: 1 1 auto;
      min-width: 0;
    }
    /* Tempdoc 697 — the one-line pill needs less vertical padding than the expanded multi-cause
       banner; this outer rule overrides jf-system-notice's :host padding on the collapsed host.
       Tempdoc 814 §D2 — 738 measured the pill at ~76px and recorded the oversized-button artifact
       without finishing it; the remaining honest size for one line of text is ~36px, so the block
       padding drops again here and on the pill's remedy control below. The registered ceiling
       (governance/ui-proportion-baseline.v1.json, \`chat-proportion\` = 42px) is a shrink-only
       ratchet — going under it is free and nothing here raises it. */
    .degradation-banner-collapsed {
      padding-block: 0.2rem;
    }
    /* The pill is ONE line by construction (the summary ellipsizes), so its row can use a tight
       line box; the expanded banner's multi-line causes keep the inherited leading. */
    .degradation-banner-collapsed .notice-row-collapsed {
      line-height: 1.3;
    }
    .degradation-banner-collapsed .degradation-summary {
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    .degradation-banner .notice-causes {
      margin: 0.35rem 0 0 1.35rem;
      padding: 0;
    }
    .degradation-banner .notice-remedy {
      display: inline-flex;
      flex: 0 0 auto;
      margin-top: 0.35rem;
      /* Tempdoc 697 — a slim notice renders the remedy as a COMPACT action, not a full-size button.
         The op-button's ActionButton reads --justsearch-shell-action-button-padding (default 0.5rem
         1rem) + a --justsearch-shell-form-control-spacing host margin; overriding them here (custom
         props cross the shadow boundary) shrinks it. The navigate jf-button skins via ::part(control).
         Without this the ~54px button inflated the one-line collapsed pill to ~76px. */
      --justsearch-shell-action-button-padding: 0.1rem 0.5rem;
      --justsearch-shell-form-control-spacing: 0;
    }
    .degradation-banner .notice-remedy jf-button::part(control) {
      padding: 0.15rem 0.55rem;
      min-height: 0;
    }
    .degradation-banner-collapsed .notice-remedy {
      margin-top: 0;
      /* Tempdoc 814 §D2 — the remedy control is the TALLEST thing in the pill's row, so it is what
         the pill's height is measured against. 697 already shrank it to a compact action
         (0.1rem/0.15rem block padding, ~20px) and it is NOT shrunk further here: that is already
         under the 24px WCAG 2.2 target-size floor, and buying the last two pixels of chrome with a
         smaller click target is the wrong trade. The pill reaches its one-line size through the
         host's own block padding above instead. */
    }
    /* Tempdoc 727 F-8 — the failed-unified-thread-refresh notice: shares the icon+text row layout
       with the degradation banner above, but is its own class (a different, usually transient cause
       — a request failure, not an AI-readiness degradation — must not read as the same banner). */
    .thread-refresh-failed-notice .notice-row {
      display: flex;
      align-items: flex-start;
      gap: 0.4rem;
    }
    .degradation-expand,
    .degradation-collapse {
      all: unset;
      flex: 0 0 auto;
      cursor: pointer;
      color: var(--text-secondary);
      padding: 0.1rem 0.35rem;
      border-radius: 0.25rem;
      line-height: 1;
    }
    .degradation-expand:hover,
    .degradation-collapse:hover,
    .degradation-expand:focus-visible,
    .degradation-collapse:focus-visible {
      color: var(--text-primary);
      background: var(--surface-hover);
      outline: 2px solid transparent;
    }
    .replay-label { flex: 0 0 auto; color: var(--text-secondary); }
    .replay-slider { flex: 1 1 auto; }
    .replay-btn, .replay-exit, .replay-fork {
      flex: 0 0 auto;
      cursor: pointer;
      color: inherit;
      background: var(--surface-hover);
      border: 1px solid var(--border-subtle);
      border-radius: 0.375rem;
      padding: 0.125rem 0.5rem;
      font-size: var(--font-size-sm);
    }
    .replay-btn[disabled] { opacity: 0.4; cursor: default; }
    /* Tempdoc 585 §D Phase 3 (C2) — the inline fork editor. */
    .fork-editor {
      display: flex;
      gap: 0.5rem;
      align-items: flex-end;
      margin-bottom: 0.5rem;
    }
    .fork-input {
      flex: 1 1 auto;
      min-height: 2.5rem;
      resize: vertical;
      font: inherit;
      color: inherit;
      background: var(--surface-1);
      border: 1px solid var(--border-subtle);
      border-radius: 0.375rem;
      padding: 0.375rem 0.5rem;
    }
    .fork-run {
      flex: 0 0 auto;
      cursor: pointer;
      color: inherit;
      background: var(--accent-primary-weak);
      border: 1px solid var(--accent);
      border-radius: 0.375rem;
      padding: 0.375rem 0.75rem;
      font-size: var(--font-size-sm);
    }
    .header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      font-size: var(--font-size-sm);
      color: var(--text-secondary);
      padding-bottom: 0.5rem;
      border-bottom: 1px solid var(--border-subtle);
    }
    .shape-indicator {
      font-size: var(--font-size-xs);
      padding: 0.15rem 0.4rem;
      border-radius: 0.25rem;
      background: var(--surface-2);
      border: 1px solid var(--border-subtle);
      color: var(--text-secondary);
    }
    /* Tempdoc 561 P-B3 (Tier-1 fix): both planes are always present and the inactive one is
       [hidden]; the [hidden] rule below must win over the planes' own display:flex, hence the
       !important. [hidden] also removes the plane (and its descendants) from the tab order and
       a11y tree, so focus and landmarks stay correct without extra wiring. */
    [hidden] {
      display: none !important;
    }
    /* 798 round 8 — the SURFACE box every wide-layout decision in this shadow root is evaluated
       against (\`@container chat-surface\` below and in the generated grid frame). The conversation grid
       cannot query itself, so the container is its nearest wrapping ancestor; this plane's CONTENT
       width — the viewport minus the Shell rail minus the host's 1rem padding — is exactly the width
       .conversation-zone is laid out in, which is the width the declared track minimums are a budget
       on. responsiveState.reportLayoutWidth reports that same box, so the TS mount gates and these
       queries decide on one number.

       Deliberately NOT on :host. \`container-type\` implies LAYOUT containment, which makes the element
       a containing block for fixed-position descendants — and <jf-citation-hover-card> is appended
       straight to this shadow root and positioned with viewport coordinates from getBoundingClientRect.
       On :host it would have been re-anchored to the surface and mispositioned by the rail's width.
       This plane wraps the zone and the composer (both query it) but not that card. */
    .answer-plane {
      flex: 1;
      min-height: 0;
      display: flex;
      flex-direction: column;
      /* Tempdoc 814 §D1 — see the :host gap note above: the band separations are chrome and yield. */
      gap: 0.5rem;
      container-type: inline-size;
      container-name: chat-surface;
    }
    /* Tempdoc 565 §12.3.D — the three-zone composition. Single column by default (narrow / non-agent);
       at a wide viewport it becomes [conversation | evidence-rail], the group centred so the answer
       stops sprawling full-bleed (Probe R: ~76% empty at 2400px) and the right margin is consumed by
       the persistent grounding rail rather than dead gutter. */
    .conversation-zone {
      flex: 1;
      min-height: 0;
      /* §13 Pillar B — the grid FRAME (display:grid + grid-template-columns + gap + per-zone
         placement) is GENERATED from CONVERSATION_ZONES by composeGridStyles (appended to the styles
         array below). This block keeps only the zone's flex sizing (container role, not the frame). */
    }
    /* Tempdoc 565 §12.3.D/F — the left run-spine: a persistent vertical status minimap of the latest run
       in the conversation's left margin (the answer the terminal node), so the run reads as one ordered,
       status-scannable timeline at a glance. Hidden below the wide breakpoint; positioned just-left of
       the centred conversation+rail group (≈61rem) so it sits beside the column, not in the dead margin.
       Fixed (the zone does not scroll; only .conversation does) — a minimap, not an inline duplicate. */
    .run-spine {
      display: none;
    }
    @container chat-surface (min-width: 64rem) {
      .run-spine {
        /* Tempdoc 565 §19.4 — a POSITION-PROPORTIONAL minimap: a full-height relative track within the
           generated grid zone. Each node is absolutely placed at its conversation item's scroll
           fraction (§19.4 computeSpinePositions), so the spine MAPS the conversation (scroll position
           ↔ node position) rather than top-packing a flex stack. The zone does not scroll (only
           .conversation does); decorative + aria-hidden — a minimap, not a duplicate. */
        /* grid-column is GENERATED (composeGridStyles over CONVERSATION_ZONES). */
        justify-self: center;
        align-self: stretch;
        position: relative; /* anchor for ::before + the absolutely-placed nodes */
        display: block;
        width: 0.9rem;
      }
      .run-spine::before {
        content: '';
        position: absolute;
        top: 0.3rem;
        bottom: 0.3rem;
        left: 50%;
        /* §16.3 — a legible connector: the spine must read as one continuous thread, not scattered dots. */
        width: 2px;
        transform: translateX(-50%);
        background: var(--border-subtle);
      }
      /* §21 AFFORDANCE — the viewport indicator is an OPERABLE scrollbar thumb (role="scrollbar"): drag it
         to scroll the reading column (the minimap IS the scrollbar). A comfortable grab width + grab cursor;
         it sits BEHIND the operable jump-nodes (lower z-index) so a dot click still hits the dot and an
         empty-track grab hits the thumb. The accent band still reads as the on-screen "you are here" slice. */
      .run-spine-viewport {
        position: absolute;
        left: 50%;
        width: 0.9rem;
        min-height: 0.6rem;
        transform: translateX(-50%);
        border-radius: 0.45rem;
        background: var(--accent-tint);
        opacity: 0.16;
        cursor: grab;
        touch-action: none;
        z-index: 0;
        transition:
          top var(--duration-instant) linear,
          height var(--duration-instant) linear;
      }
      .run-spine-viewport:hover {
        opacity: 0.26;
      }
      .run-spine-viewport:active {
        cursor: grabbing;
        opacity: 0.34;
      }
      .run-spine-viewport:focus-visible {
        outline: 2px solid var(--accent-tint);
        outline-offset: 2px;
        opacity: 0.34;
      }
    }
    .run-spine-node {
      /* §19.4 — absolutely placed at the conversation scroll fraction; the top + the box --node-size
         (the §19.3 declared PROMINENCE_SCALE) + opacity are set inline. The translate centres the
         node on (fraction, connector). */
      position: absolute;
      left: 50%;
      top: 0;
      /* §21 — the jump-nodes layer ABOVE the scrollbar thumb (z-index:0) so a dot click hits the dot
         (click-jump) while an empty-track grab hits the thumb (drag-scroll). */
      z-index: 1;
      width: var(--node-size, 0.55rem);
      height: var(--node-size, 0.55rem);
      transform: translate(-50%, -50%);
      border-radius: 50%;
      box-sizing: border-box;
      /* §17 — the button is the sized DISC wrapper; <jf-run-node> renders the glyph+tone visual inside.
         A surface disc + subtle ring covers the connector behind each node so it reads as segments. */
      background: var(--surface-1);
      border: 1px solid var(--border-subtle);
      display: inline-flex;
      align-items: center;
      justify-content: center;
      overflow: hidden;
      /* §13 Pillar A — the node is an operable jump control (a <button>); reset the chrome. */
      appearance: none;
      padding: 0;
      margin: 0;
      cursor: pointer;
    }
    .run-spine-node:focus-visible {
      outline: 2px solid var(--accent-tint);
      outline-offset: 2px;
    }
    /* §13 Pillar A — the scroll-spy active node (the current reading position) gets a tint ring. */
    .run-spine-node.active {
      box-shadow: 0 0 0 3px var(--accent-tint);
    }
    /* Tempdoc 814 §D4 — the CLUSTER badge: the aggregated form of markers too close to draw apart, so
       spine density stays bounded by the run's structure rather than its event count. A counted disc,
       operable like every other marker (it jumps to the group's first step). */
    .run-spine-cluster {
      position: absolute;
      left: 50%;
      top: 0;
      z-index: 1;
      transform: translate(-50%, -50%);
      min-width: 1.05rem;
      height: 1.05rem;
      padding: 0 0.15rem;
      border-radius: 0.55rem;
      box-sizing: border-box;
      background: var(--surface-1);
      border: 1px solid var(--border-default);
      color: var(--text-secondary);
      font-size: var(--font-size-xs);
      font-variant-numeric: tabular-nums;
      line-height: 1;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      appearance: none;
      margin: 0;
      cursor: pointer;
    }
    .run-spine-cluster:hover {
      color: var(--text-primary);
      border-color: var(--border-strong);
    }
    .run-spine-cluster:focus-visible {
      outline: 2px solid var(--accent-tint);
      outline-offset: 2px;
    }
    .run-spine-cluster.active {
      box-shadow: 0 0 0 3px var(--accent-tint);
    }
    /* A cluster carrying a failed step keeps the run-health cue the single markers have. */
    .run-spine-cluster.has-error {
      border-color: var(--accent-danger);
      color: var(--text-danger);
    }
    /* §13 Pillar B / §13.9 — the wide grid-template-columns, the per-zone placements AND the
       bounded-group centring (width:100% + justify-content:center) are all GENERATED from
       CONVERSATION_ZONES by composeGridStyles. The centring pair used to be hand-authored here;
       tempdoc 816 moved it into the generator because the docked composer now lays out on the SAME
       frame, and a hand-authored half of the frame is a second authority on where the column falls. */
    .conversation {
      flex: 1;
      min-width: 0;
      min-height: 0;
      overflow-y: auto;
      display: flex;
      flex-direction: column;
      gap: 0.75rem;
      /* §13.9 — these scroll regions live in the shadow root, so the global ::-webkit-scrollbar styling
         in tokens.css can't reach them and they fell back to a chunky 15px native bar mid-column. Use the
         standard thin + subtle scrollbar (the app's convention) so the affordance is unobtrusive. */
      scrollbar-width: thin;
      scrollbar-color: var(--border-subtle) transparent;
    }
    /* §21 AFFORDANCE — when the run-spine is mounted (agent + wide) it IS the scroll control, so hide the
       reading column's native scrollbar; scrollbar-gutter:stable reserves the gutter so hiding it
       causes no reflow (the Spike A 10px shift). The thin native bar stays the fallback otherwise. */
    .conversation.spine-scrolled {
      scrollbar-width: none;
      scrollbar-gutter: stable;
    }
    /* The webkit/Chromium half of "hide the native bar" is the ambient \`.jf-scrollbar-none\` utility
       (574 §16 — ::-webkit-scrollbar is a Class-B shadow-scoped pseudo, owned by ambientStyles); the
       template adds that class alongside \`spine-scrolled\`. */
    /* Tempdoc 814 §D3 — ONE scroll region per surface: \`.conversation\` is it. The rail was a second,
       independent \`overflow-y: auto\` region in the same grid row (finding 13's nested-scroller stack);
       it is now a BOUNDED INDEX — top-N cards + an "Open all · N" row into the sanctioned drawer — so
       it has nothing to scroll. \`hidden\` (not \`auto\`) makes that structural, not a hope. */
    .evidence-rail {
      display: none;
      min-width: 0;
      min-height: 0;
      overflow: hidden;
    }
    @container chat-surface (min-width: 64rem) {
      .evidence-rail {
        /* grid-column:4 is GENERATED (composeGridStyles over CONVERSATION_ZONES). §13.9 — the rail's
           track is fit-content(20rem), which sizes to the element's content and COLLAPSES to 0 when the
           rail is unmounted (Documents/RAG mode / no sources). These bounds give it a 15-20rem width WHEN
           present so it doesn't shrink below a legible rail. */
        display: block;
        min-width: 15rem;
        max-width: 20rem;
      }
    }
    /* Search Thread S6 — the reading pane (\`<jf-document-pane>\`, \`.document-pane\` zone col 5). The pane
       is mounted in this grid ONLY while the surface container is wide (687 R5b: below that the SAME
       component presents through Shell's OverlayHost right-drawer slot, the one sanctioned overlay seam,
       rather than auto-placing into an implicit stacked row that collided with the composer). So the
       base rule below is the shared floor, not a narrow layout. A free-floating \`position: fixed\` pane
       was considered and rejected: outside the OverlayHost it trips the layout-purity gate. */
    .document-pane {
      min-width: 0;
      min-height: 24rem;
      overflow: hidden;
    }
    /* Tempdoc 814 §D6 — the F5 close (734 round 8: clearing results with the preview open clipped the
       composer below the viewport). The pane, the composer and the header cannot all fit a 768-tall
       window while this floor is 24rem, and the composer — bottom of the flex column — is what paid.
       Below the ONE block-axis breakpoint (primitives/compositionLayout.ts) the SECONDARY surface
       yields instead: the floor survives at normal heights, and this rule precedes the wide
       \`@container\` block below, which still overrides it with \`min-height: 0\` when the pane is a
       grid column rather than a stacked row. */
    ${shortViewportMedia} {
      .document-pane {
        min-height: 14rem;
      }
    }
    @container chat-surface (min-width: 64rem) {
      .document-pane {
        /* grid-column:5 is GENERATED (composeGridStyles over CONVERSATION_ZONES). */
        min-width: 24rem;
        max-width: 28rem;
        min-height: 0;
        height: 100%;
      }
    }
    /* Tempdoc 561 P-B3: the action plane fills the one window's body; the hosted agent view
       provides its own scroll + composer below the shared affordance bar. */
    .agent-plane {
      flex: 1;
      min-height: 0;
      display: flex;
      flex-direction: column;
    }
    .agent-plane jf-agent-view {
      flex: 1;
      min-height: 0;
      display: flex;
      flex-direction: column;
    }
    .message {
      max-width: 85%;
      padding: 0.5rem 0.75rem;
      border-radius: 0.5rem;
      font-size: var(--font-size-sm);
      line-height: 1.5;
      /* Tempdoc 697 — pre-wrap lives on the raw-text leaf (.message-body), NOT this container, so the
         Lit template's whitespace between the wrapper and the leaf no longer renders as phantom blank
         lines (the user bubble measured ~75px for one 17px line). User newlines are still preserved. */
      white-space: normal;
    }
    .message-body {
      white-space: pre-wrap;
    }
    .message.user {
      align-self: flex-end;
      background: var(--accent-tint);
      color: var(--accent-on-tint);
    }
    /* Tempdoc 577 §2.14 Root I (#19) — the ambient turn-boundary timestamp under the user turn.
       814 review pass: it is INSIDE .message.user, which paints --accent-tint, so the
       surface-calibrated --text-secondary was the wrong token — measured 1.27:1 (axe
       color-contrast, faded secondary text over the accent tint) the moment a capture first
       rendered a record-path user turn. The bubble's own foreground token is the one
       calibrated against this background; recession comes from size alone, not from fading
       the text into the tint. */
    .turn-time {
      display: block;
      margin-top: 0.2rem;
      font-size: var(--font-size-xs);
      color: var(--accent-on-tint);
      text-align: right;
    }
    /* Tempdoc 577 §2.14 Root I (#19) — the run/session boundary seam between restored history and the
       live run: a faint full-width rule with a centred label, so a resumed thread reads as two runs. */
    .run-seam {
      display: flex;
      align-items: center;
      gap: 0.5rem;
      margin: 0.6rem 0;
      color: var(--text-secondary);
      font-size: var(--font-size-xs);
    }
    .run-seam::before,
    .run-seam::after {
      content: '';
      flex: 1;
      border-top: 1px dashed var(--border-subtle);
    }
    .run-seam-label {
      text-transform: uppercase;
      letter-spacing: 0.06em;
    }
    .message.assistant {
      align-self: flex-start;
      background: var(--surface-2);
      border: 1px solid var(--border-subtle);
      max-width: 95%;
      /* Tempdoc 565 §12.3.B / 697 — assistant messages hold components (markdown block, tool-call
         card) that manage their own whitespace. pre-wrap now lives on the .message-body leaf (697),
         not this .message container, so the template whitespace no longer inherits across the shadow
         boundary into the tool-call card as phantom vertical space. This reset is now belt-and-braces. */
      white-space: normal;
    }
    /* Tempdoc 565 §3.C — a reading measure: prose answers don't run edge-to-edge across the full
       window. Caps the line length of the canonical answer block; code/tables inside markdown can
       still overflow-scroll on their own. Applies to every mode (agent + chat/RAG). */
    .message.assistant jf-markdown-block {
      display: block;
      /* Tempdoc 816 §6 — the NUMBER survives, its AUTHORITY moves: this is the \`prose\` role's
         instance on this surface, so the literal 88ch is now the role token (the register holds the
         value; the gate asserts token == register). */
      max-inline-size: var(--measure-prose);
    }
    /* Tempdoc 565 §15.C (fix) — the explicit workflow run affordance (replaces auto-run-on-mount). */
    .workflow-trigger {
      display: flex;
      align-items: center;
      gap: 0.75rem;
      padding: 0.5rem 0;
      color: var(--text-secondary);
      font-size: var(--font-size-sm);
    }
    /* Tempdoc 565 §26.C — the workflow picker (replaces the single-id label). */
    .workflow-picker-label {
      font-weight: 600;
    }
    .workflow-picker {
      font: inherit;
      color: var(--text-primary);
      background: var(--surface-2);
      border: 1px solid var(--border-subtle);
      border-radius: 0.3rem;
      padding: 0.2rem 0.4rem;
    }
    .message-shape-tag {
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
      margin-bottom: 0.2rem;
      text-transform: uppercase;
      letter-spacing: 0.04em;
    }
    /* Tempdoc 565 §12.3.C/F — the run trace: a collapsible, status-coloured spine that subordinates
       the run so the answer leads. Closed = one summary line; open = a vertical spine of status nodes. */
    .run-trace {
      align-self: stretch;
    }
    /* Tempdoc 565 §26.B — a workflow NODE rendered as a labelled segment: a header naming the node
       over its steps. The node structure §15.C flattened, made visible without nesting the item model. */
    .run-segment {
      align-self: stretch;
      border-left: 2px solid var(--border-subtle);
      padding-left: 0.6rem;
      margin: 0.2rem 0;
    }
    .run-segment-header {
      display: inline-flex;
      align-items: center;
      gap: 0.5ch;
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
      margin-bottom: 0.2rem;
    }
    .run-segment-name {
      font-weight: 600;
    }
    .run-segment-kind {
      font-size: var(--font-size-xs);
      color: var(--text-tertiary);
      border: 1px solid var(--border-subtle);
      border-radius: 0.3rem;
      padding: 0 0.35ch;
      text-transform: lowercase;
    }
    /* Tempdoc 814 (finding 7) — the background-run POINTER: the same chip geometry as the kind chip
       it replaces, but operable, because the run's record lives in the drawer's Background-runs tab
       and this segment is a reference to it, not a second copy of it. */
    .run-segment-ref {
      background: transparent;
      font: inherit;
      font-size: var(--font-size-xs);
      cursor: pointer;
    }
    .run-segment-ref:hover {
      color: var(--text-primary);
      border-color: var(--accent-tint);
    }
    /* §26.D — a background run reads as a dashed-edge segment (it ran "while you were away"). */
    .run-segment.origin-background {
      border-left-style: dashed;
    }
    /* Tempdoc 565 §26.B — a spine node that opens a workflow node segment: a small node-boundary tick. */
    .run-spine-node.node-boundary::after {
      content: '';
      position: absolute;
      left: -0.35rem;
      top: 50%;
      width: 0.3rem;
      height: 1px;
      background: var(--text-tertiary);
      transform: translateY(-50%);
    }
    /* Tempdoc 565 §30 — a human STEERING directive is a distinct human-origin spine landmark.
       814 §D4 / 809 finding 15 — the ring was drawn in \`--accent-command\`, the same purple family as
       the user bubble ~a column away, so two different meanings shared one colour. The cue is now
       SHAPE, not hue: a rotated square (diamond) in the neutral text token. Same token system, one
       less colour collision. */
    .run-spine-node.steer-landmark::before {
      content: '';
      position: absolute;
      inset: -0.22rem;
      border-radius: 0.1rem;
      border: 1px solid var(--text-primary);
      transform: rotate(45deg);
    }
    /* Tempdoc 565 §30 — the "Your direction" body chip for an acknowledged steer (human-origin, accent). */
    .steer-directive {
      display: inline-flex;
      align-items: baseline;
      gap: 0.5ch;
      padding: 0.2rem 0.55rem;
      border-radius: 0.4rem;
      background: var(--accent-tint);
      color: var(--accent-on-tint);
      font-size: var(--font-size-xs);
    }
    .steer-directive__label {
      font-weight: 600;
      letter-spacing: 0.04ch;
    }
    /* Tempdoc 565 §30 — the live-run steer input row (the DIRECTION authority's interject affordance). */
    .run-steer {
      display: flex;
      gap: 0.5ch;
      align-items: center;
      margin-bottom: 0.4rem;
    }
    .run-steer__input {
      flex: 1;
      padding: 0.35rem 0.6rem;
      border-radius: 0.4rem;
      border: 1px solid var(--border-subtle);
      background: var(--surface-hover);
      color: var(--text-primary);
      font-size: var(--font-size-xs);
    }
    /* Tempdoc 565 §29 Tier-2 — per-segment elapsed time (dim, right of the node name). */
    .run-segment-elapsed {
      margin-left: auto;
      color: var(--text-tertiary);
      font-size: var(--font-size-xs);
      font-variant-numeric: tabular-nums;
    }
    /* Tempdoc 565 §29 Tier-2 — a run-health tick: an error step reads in the danger tone on the spine. */
    .run-spine-node.has-error::before {
      content: '';
      position: absolute;
      inset: -0.18rem;
      border-radius: 50%;
      border: 1px solid var(--accent-danger);
    }
    .run-trace-summary {
      list-style: none;
      cursor: pointer;
      display: inline-flex;
      align-items: center;
      gap: 0.45ch;
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
      padding: 0.15rem 0.4rem;
      border-radius: 0.3rem;
      user-select: none;
    }
    .run-trace-summary::-webkit-details-marker {
      display: none;
    }
    .run-trace-summary:hover {
      background: var(--surface-2);
      color: var(--text-primary);
    }
    .run-trace-caret {
      font-size: var(--font-size-xs);
      opacity: 0.8;
    }
    .trace-spine {
      margin: 0.3rem 0 0.3rem 0.5rem;
      padding-left: 0.85rem;
      border-left: 1px solid var(--border-subtle);
      display: flex;
      flex-direction: column;
      gap: 0.4rem;
    }
    .trace-row {
      position: relative;
    }
    /* §17 — the trace node is the sized DISC wrapper for <jf-run-node> (glyph + tone), sitting on the
       trace connector (.trace-spine border-left). Bigger than the spine node so the glyph reads. */
    .trace-node {
      position: absolute;
      left: -1.22rem;
      top: 0.2em;
      width: 0.72rem;
      height: 0.72rem;
      border-radius: 50%;
      background: var(--surface-1);
      border: 1px solid var(--border-subtle);
      box-sizing: border-box;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      overflow: hidden;
    }
    /* §16.4/§17 — the run-step label: the backend's already-human message, NOT CSS-uppercased. */
    .trace-label {
      font-size: var(--font-size-sm);
      color: var(--text-secondary);
      line-height: 1.4;
    }
    /* Tempdoc 565 §12.3.C — declared prominence: secondary recedes (dense), ambient is faint. */
    .trace-row.prominence-secondary {
      font-size: var(--font-size-md);
    }
    .trace-row.prominence-ambient {
      font-size: var(--font-size-sm);
      opacity: 0.72;
    }
    .trace-row.prominence-ambient .message-shape-tag {
      margin-bottom: 0;
    }
    /* Tempdoc 565 §12.3.E — the source-chip row: compact grounding chips under the answer, the third
       ambient-grounding surface (inline [n] · chips · rail), cross-highlighted via the selectedSource store. */
    /* §13.8 P3 — the "Sources · N" disclosure (mirrors CitationsPanel's "N sources" toggle). */
    /* Tempdoc 565 §14 ④/⑤ — the grounding-honesty badge (readiness + N-of-M coverage), beside the answer. */
    /* Tempdoc 577 Move 4 — the grounding badge is a native <details> disclosure (the "Why uncited?"
       tier): the summary is the inline "Grounded · N of M" chip; expanding reveals the breakdown +
       why the uncited sentences carry no mark. Keyboard/AT-accessible by construction. */
    .grounding-badge {
      margin-top: 0.4rem;
      font-size: var(--font-size-xs);
      letter-spacing: 0.02em;
      color: var(--text-success);
    }
    .grounding-badge-summary {
      display: inline-flex;
      align-items: center;
      gap: 0.35rem;
      cursor: pointer;
      list-style: none;
    }
    .grounding-badge-summary::-webkit-details-marker {
      display: none;
    }
    .grounding-why {
      margin-top: 0.3rem;
      color: var(--text-secondary);
      font-style: italic;
      max-width: 60ch;
    }
    /* Tempdoc 577 §2.12 Move 3 — the epistemic answer FRAME line (generalizes the round-1
       uncited-note): a one-line trust note, so an ungrounded model answer cannot pose as
       index-grounded. Ambient by default; the ungrounded arm is marked a touch more present (it
       is the trust-critical case). Search Thread S7 (tempdoc decision 6) — this is now also THE
       quiet per-turn receipt line (grounding verdict + duration + model, see .answer-receipt below):
       margin moved from -bottom to -top since the non-transform frames now render UNDER the answer
       block (the transform frame keeps its own pre-content position — see .answer-frame-transform). */
    .answer-frame {
      margin-top: 0.4rem;
      font-size: var(--font-size-xs);
      font-style: italic;
      color: var(--text-secondary);
    }
    .answer-frame-ungrounded {
      color: var(--text-warning);
      font-style: normal;
    }
    /* Search Thread S7 (tempdoc decision 6) — a fully grounded answer carries no warning text, only
       the quiet receipt tail (duration + model); ambient, non-italic (never reads as a caveat). */
    .answer-frame-grounded {
      color: var(--text-tertiary);
      font-style: normal;
    }
    /* Tempdoc 603 D-4 — the SOURCED frame: the answer drew on retrieved documents but per-sentence
       grounding was not verified (document-level retrieval). Informational, NOT a warning — a calm
       secondary note (provenance is real), distinct from the italic default and the warning ungrounded arm. */
    .answer-frame-sourced {
      color: var(--text-secondary);
      font-style: normal;
    }
    /* Search Thread S7 (tempdoc decision 6) — the receipt tail (duration + model) is always plain
       tokens, never italicized by the surrounding frame's own styling (e.g. the base italic default,
       or the grounding-why disclosure it can sit beside). */
    .answer-receipt {
      font-style: normal;
    }
    /* Tempdoc 836 S2S3-A.2 — the coverage line ("what was actually examined"). Upright like the
       receipt beside it because it is a FACT about the run, not the frame's editorial voice; it
       renders only when the run reported that verification did not fully happen. */
    .answer-coverage {
      font-style: normal;
    }
    /* Tempdoc 603 C3 — an extraction (transform) is the model's own structuring, not retrieved data.
       Clean JSON reads as authoritative, so the marker is UNMISSABLE (a tinted, bordered strip that
       abuts the result below), not the subtle italic header — a user skimming the values can't miss it. */
    .answer-frame-transform {
      color: var(--text-warning);
      font-style: normal;
      font-weight: 500;
      padding: 0.3rem 0.5rem;
      border-left: 3px solid var(--accent-warning);
      background: var(--surface-2);
      border-radius: 0.25rem 0.25rem 0 0;
      margin-bottom: 0;
    }
    /* Tempdoc 603 C2 — the decontextualized standalone question a follow-up's retrieval ran on. */
    .rewrite-note {
      margin-bottom: 0.4rem;
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
    }
    .grounding-dot {
      width: 0.5rem;
      height: 0.5rem;
      border-radius: 50%;
      background: var(--accent-success);
    }
    .source-disclosure {
      margin-top: 0.5rem;
    }
    .source-disclosure-summary {
      display: inline-flex;
      align-items: center;
      gap: 0.35rem;
      padding: 0.1rem 0;
      font-family: inherit;
      font-size: var(--font-size-xs);
      letter-spacing: 0.03em;
      color: var(--text-secondary);
      background: none;
      border: none;
      cursor: pointer;
    }
    .source-disclosure-summary:hover,
    .source-disclosure-summary:focus-visible {
      color: var(--text-primary);
      outline: 2px solid transparent;
    }
    .source-disclosure-summary:focus-visible {
      text-decoration: underline;
    }
    .source-disclosure .disclosure-chevron {
      display: inline-block;
      transition: transform var(--duration-fast) var(--ease-standard);
    }
    @media (prefers-reduced-motion: reduce) {
      .source-disclosure .disclosure-chevron { transition: none; }
    }
    .source-disclosure .disclosure-chevron.open {
      transform: rotate(90deg);
    }
    .source-chips {
      display: flex;
      flex-wrap: wrap;
      gap: 0.4rem;
      margin-top: 0.35rem;
    }
    /* Tempdoc 610 §J.3 — the source chip + its hide/restore toggle, as one unit. */
    .source-chip-wrap {
      display: inline-flex;
      align-items: center;
    }
    .source-chip-wrap.hidden-source .source-chip {
      opacity: 0.45;
      text-decoration: line-through;
    }
    .source-exclude {
      all: unset;
      cursor: pointer;
      margin-left: 0.15rem;
      padding: 0 0.3rem;
      font-size: var(--font-size-xs);
      line-height: 1;
      color: var(--text-muted);
      border-radius: 0.75rem;
    }
    .source-exclude:hover,
    .source-exclude:focus-visible {
      color: var(--text-primary);
      background: var(--surface-hover);
      outline: 2px solid transparent;
    }
    .source-chip {
      display: inline-flex;
      align-items: center;
      gap: 0.45ch;
      max-width: 24ch;
      padding: 0.15rem 0.55rem;
      font-size: var(--font-size-xs);
      font-family: inherit;
      border: 1px solid var(--border-subtle);
      border-radius: 1rem;
      background: var(--surface-2);
      color: var(--text-secondary);
      cursor: pointer;
    }
    .source-chip:hover,
    .source-chip:focus-visible {
      background: var(--surface-hover);
      color: var(--text-primary);
      border-color: var(--accent-command);
      outline: 2px solid transparent;
    }
    .source-chip.selected {
      background: var(--surface-hover);
      border-color: var(--accent-command);
      color: var(--text-primary);
    }
    /* Forced colors (Windows High Contrast) repaint every chip edge CanvasText and erase the fill, so
       the selected chip's edge is restated as Highlight, a system colour forced colors keep. */
    @media (forced-colors: active) {
      .source-chip.selected {
        border-color: Highlight;
      }
    }
    .source-chip-n {
      font-weight: 600;
      color: var(--text-tint);
    }
    .source-chip-name {
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    .extract-output {
      background: var(--surface-1);
      border: 1px solid var(--border-subtle);
      border-radius: 0.375rem;
      padding: 0.75rem;
      font-family: ui-monospace, 'Cascadia Code', 'Source Code Pro', Menlo, monospace;
      font-size: var(--font-size-sm);
      overflow-x: auto;
      white-space: pre-wrap;
      word-break: break-word;
    }
    /* Tempdoc 561 C-2: the rail summary names the approval posture (graded by the dial). */
    .activity-rail > summary {
      color: var(--text-secondary);
    }
    /* Search Thread S5b — the affordance TAB ROW (.affordance-bar/.affordance-btn) is retired
       (the row died with the standalone tabs — the retrieve tier, the route chip, the results
       card's Ask AI, and the landing escalation strip are the crossing controls now). The two
       surviving controls that used to reuse .affordance-btn — History (moved to the header,
       styled via the shared .new-chat-btn) and the agent-only toolbar below — get their own
       modest classes instead. */
    .agent-toolbar {
      display: flex;
      align-items: center;
      gap: 0.5rem;
      padding-bottom: 0.35rem;
    }
    .agent-tool-btn {
      padding: 0.2rem 0.5rem;
      font-size: var(--font-size-xs);
      border-radius: 0.25rem;
      border: 1px solid var(--border-subtle);
      background: transparent;
      color: var(--text-secondary);
      cursor: pointer;
      transition: all var(--duration-fast) var(--ease-standard);
    }
    .agent-tool-btn:hover {
      border-color: var(--accent-tint);
      color: var(--text-primary);
    }
    .agent-tool-btn[aria-pressed='true'] {
      background: var(--accent-tint);
      color: var(--accent-on-tint);
      border-color: var(--accent-tint);
    }
    /* Tempdoc 565 §12.3.E — at the wide breakpoint the persistent evidence rail replaces the toggle
       drawer, so the "Sources · N" affordance (which opens that drawer) is redundant.
       Tempdoc 814 §D5 — the hide is no longer a CSS rule here: the affordance is gated on
       evidenceRailMounted() at RENDER (UnifiedChatView.renderAgentToolbar), so the redundant count
       leaves the DOM instead of merely leaving the screen — one authority structurally, which is what
       the status-fact singleton probe and assistive tech both read. */
    /* Tempdoc 561 P-A/P-B (Slice 3): the secondary Activity rail — demoted agent chrome (budget),
       collapsible so the conversation stays primary. */
    .activity-rail {
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
      border: 1px solid var(--border-subtle);
      border-radius: 0.25rem;
      padding: 0.2rem 0.4rem;
      background: var(--surface-2);
    }
    .activity-rail summary {
      cursor: pointer;
      user-select: none;
    }
    .activity-lifecycle {
      padding-top: 0.25rem;
      color: var(--text-secondary);
    }
    .activity-lifecycle .lifecycle-state {
      color: var(--text-primary);
      font-weight: 600;
    }
    .activity-budget {
      display: flex;
      align-items: center;
      gap: 0.5rem;
      padding-top: 0.25rem;
    }
    /* Tempdoc 814 §D4 — a meter is a COMPACT gauge, not a page-wide rule. \`flex: 1\` made both bars
       eat every pixel the row did not otherwise claim, so a 4px track read as a full-bleed horizontal
       line across the surface (and, pegged at 100%, as a status bar rather than a reading). Fixed
       width, allowed to shrink on a cramped row but never to grow; the unit + ceiling wording beside
       it (577's own requirement) is untouched — the words carry the number, the bar carries the
       proportion. */
    .activity-budget .budget-bar,
    .context-meter .budget-bar {
      flex: 0 1 10rem;
      width: 10rem;
      max-width: 100%;
      height: 4px;
      border-radius: 2px;
      background: var(--surface-1);
      overflow: hidden;
    }
    .activity-budget .budget-bar-fill,
    .context-meter .budget-bar-fill {
      height: 100%;
      background: var(--accent-tint);
    }
    /* Tempdoc 577 §2.14 Root II (#14) — the context-headroom meter's status-graded fill (these are
       BACKGROUND fills, not text, so the accent-* status tokens are the right authority here). The
       chat-surface context-budget meter (610 §E.4) shares this one fullness→colour authority. */
    .activity-context .budget-bar-fill.context-fill-green,
    .context-meter .budget-bar-fill.context-fill-green {
      background: var(--accent-success);
    }
    .activity-context .budget-bar-fill.context-fill-yellow,
    .context-meter .budget-bar-fill.context-fill-yellow {
      background: var(--accent-warning);
    }
    .activity-context .budget-bar-fill.context-fill-red,
    .context-meter .budget-bar-fill.context-fill-red {
      background: var(--accent-danger);
    }
    /* Tempdoc 610 §E.4 — the chat context-budget meter row, sits just above the composer. */
    .context-meter {
      display: flex;
      align-items: center;
      flex-wrap: wrap;
      gap: 0.5rem;
      padding: 0.15rem 0.25rem 0.35rem;
    }
    .context-meter-label {
      color: var(--text-muted);
      font-size: var(--font-size-xs);
      white-space: nowrap;
    }
    /* Tempdoc 610 §K — the meter label doubles as the inspector trigger (opens "what the assistant sees"). */
    .context-meter-trigger {
      all: unset;
      cursor: pointer;
      color: var(--text-muted);
      font-size: var(--font-size-xs);
      white-space: nowrap;
      border-radius: 0.2rem;
    }
    .context-meter-trigger:hover,
    .context-meter-trigger:focus-visible {
      color: var(--text-primary);
      text-decoration: underline;
      outline: 2px solid transparent;
    }
    /* Tempdoc 610 §I.2 — the per-phase attribution, revealed on hover/focus (compact by default). */
    .context-meter-breakdown {
      flex-basis: 100%;
      display: none;
      color: var(--text-muted);
      font-size: var(--font-size-xs);
    }
    .context-meter:hover .context-meter-breakdown,
    .context-meter:focus-within .context-meter-breakdown {
      display: block;
    }
    .context-meter-breakdown .cmb-est {
      color: var(--text-tint);
      font-style: italic;
    }
    /* Tempdoc 610 §I.2 — the "N turns hidden · Include all" aggregate row. */
    .excluded-summary {
      display: flex;
      align-items: center;
      gap: 0.5rem;
      padding: 0.1rem 0.25rem 0.35rem;
    }
    .excluded-summary-label {
      color: var(--text-muted);
      font-size: var(--font-size-xs);
    }
    .excluded-summary-action {
      all: unset;
      cursor: pointer;
      color: var(--text-primary);
      font-size: var(--font-size-xs);
      text-decoration: underline;
      padding: 0.1rem 0.3rem;
      border-radius: 0.2rem;
    }
    .excluded-summary-action:hover,
    .excluded-summary-action:focus-visible {
      background: var(--surface-2);
    }
    .activity-rail .over-budget {
      color: var(--text-danger);
      font-weight: 600;
    }
    /* Round-14 finding 12(b) — the SAME figure on a run that already reported DONE: a settled fact,
     * not an alarm. Same words, ordinary text tone (the danger colour + bold is reserved for a state
     * the user can still act on, which is exactly when the remedies render). */
    .activity-rail .budget-settled {
      color: var(--text-secondary);
    }
    /* Tempdoc 577 Ext III — posture POLICY text is grammatically + typographically distinct from
     * live status (italic secondary, never the bold status treatment). */
    .activity-rail .posture-policy {
      font-style: italic;
      font-weight: 400;
    }
    /* Tempdoc 577 Ext III — the over-budget remedies (halt / raise) beside the figure. */
    .budget-actions {
      display: inline-flex;
      gap: 0.375rem;
    }
    .budget-action {
      padding: 0.1rem 0.5rem;
      background: var(--surface-tertiary);
      color: var(--text-primary);
      border: 1px solid var(--border-subtle);
      border-radius: 0.25rem;
      font: inherit;
      font-size: var(--font-size-xs);
      cursor: pointer;
    }
    /*
     * Override imported composerStyles for UnifiedChatView's outer wrapper:
     * the composer is a vertical stack (affordance bar / preview / schema
     * input / inner composer-row) rather than the row layout used by the
     * four simple chat views. The inner .composer-row IS the row-flex.
     */
    .composer {
      display: flex;
      flex-direction: column;
      gap: 0.35rem;
    }
    .composer-row {
      display: flex;
      gap: 0.5rem;
      align-items: stretch;
    }
    /*
     * Search Thread D2/D3 (stage S2, item 5) — jf-composer renders its own div.composer into LIGHT
     * DOM (Composer.ts createRenderRoot returns "this"), so that inner div lands in THIS shadow root
     * too, directly under jf-composer, and inherits the .composer{flex-direction:column} override
     * above meant for the OUTER wrapper — stretching the submit button to the wrapper's full width.
     * Restore composerStyles' own row layout specifically for the nested instance (the docked
     * .composer > jf-composer case only — the landing composer wraps in .landing-composer instead,
     * so it never nests under a .composer-classed ancestor and needs no override).
     */
    .composer > jf-composer > .composer {
      flex-direction: row;
      align-items: stretch;
      gap: 0.5rem;
    }
    .composer > jf-composer > .composer button {
      flex: 0 0 auto;
    }
    /* Search Thread D2/D3 (stage S2, item 4) — the per-turn route indicator row, right-aligned above
       the composer, shown only in the retrieve affordance. Search Thread S4-final — also hosts the
       "⌄ recent" query-trail dropdown alongside the route chip. */
    .route-row {
      display: flex;
      align-items: center;
      justify-content: flex-end;
      gap: 0.5rem;
      font-size: var(--font-size-xs);
    }
    /* Search Thread S4-final — the recent-query trail dropdown (item 5): mirrors RecentsMenu's
       native-button + role="menu" convention. */
    .query-trail {
      position: relative;
      display: inline-flex;
    }
    .query-trail-toggle {
      display: inline-flex;
      align-items: center;
      gap: 0.2rem;
      background: transparent;
      border: none;
      padding: 0.125rem 0.35rem;
      font: inherit;
      font-size: var(--font-size-xs);
      color: var(--text-tertiary);
      cursor: pointer;
      border-radius: 0.25rem;
    }
    .query-trail-toggle:hover,
    .query-trail-toggle:focus-visible {
      color: var(--text-primary);
      background: var(--surface-hover);
      outline: 2px solid transparent;
    }
    .query-trail-menu {
      position: absolute;
      top: calc(100% + 0.25rem);
      right: 0;
      z-index: var(--z-overlay-float);
      min-width: 12rem;
      max-width: 20rem;
      padding: 0.25rem;
      background: var(--surface-1);
      border: 1px solid var(--border-subtle);
      border-radius: 0.5rem;
      box-shadow: var(--shadow-float);
    }
    .query-trail-item {
      display: block;
      width: 100%;
      text-align: left;
      background: transparent;
      border: none;
      color: var(--text-secondary);
      font: inherit;
      font-size: var(--font-size-sm);
      padding: 0.35rem 0.5rem;
      border-radius: 0.25rem;
      cursor: pointer;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    .query-trail-item:hover,
    .query-trail-item:focus-visible {
      color: var(--text-primary);
      background: var(--surface-hover);
      outline: 2px solid transparent;
    }
    /* Search Thread D5 (stage S3) — the pinned scope-chip row, shown above the composer (and above
       .route-row when present) in every affordance — a scope constrains both search and ask. */
    .scope-row {
      display: flex;
      justify-content: flex-start;
    }
    /* Search Thread D5 (stage S3) — the quiet "Ask about these N results" affordance shown above the
       retrieve card when >1 rows are selected. */
    .scope-selection-btn {
      align-self: flex-start;
      background: none;
      border: none;
      padding: 0;
      font: inherit;
      font-size: var(--font-size-sm);
      color: var(--accent);
      text-decoration: underline;
      cursor: pointer;
    }
    /* Search Thread D2/D3 (tempdoc decision 8, stage S2, item 7; stable-slot rev) — the bare-landing
       intro (title + corpus) sits at the BOTTOM of the conversation column, directly above the
       CSS-centered composer; the composer itself never re-parents (a re-parented textarea drops
       keystrokes racing the first render — live-validation finding). */
    .landing {
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 0.4rem;
      text-align: center;
      margin-bottom: 0.75rem;
    }
    /* The stable bottom slot in landing mode: bounded, centered, lifted off the floor.
       Tempdoc 816 §5 — the BOX no longer carries the bound; the composer container is now laid out on
       the generated zone frame in every state (alignToZoneStyles, UnifiedChatView.styles), so landing
       centring moved one level in, onto the container's children (below). Same 42rem width as before,
       now centred on the READING COLUMN's centre instead of the surface's — the ~12px landing/column
       drift the 816 derisk pass measured (landing centre 709 vs column centre 697 at 1366). */
    .composer.landing-dock {
      /* 687 R5a — real flex centering in the freed space, replacing the vh-margin approximation whose
         bands interleaved with the intro at short viewports (the audit-measured overlap). The space is
         freed by \`.landing-collapsed\` below, which the host applies only when the zone is EMPTY. */
      margin-top: auto;
      margin-bottom: auto;
      border-top: none;
    }
    .answer-plane > .composer.landing-dock > * {
      justify-self: center;
      inline-size: min(42rem, 100%);
    }
    /* 687 R5a — trade the zone's \`flex: 1; min-height: 0\` for content-sizing so the composer can centre
       in what is left. 798 round 8: this is sound ONLY while the landing zone is empty. The host gates
       the class on that (UnifiedChatView.renderAnswerPlane) because clearing the query does NOT unmount
       a reading pane the user opened — and content-sizing the zone around a \`height: 100%\` pane leaves
       that height with no definite basis, so the pane's scroll region stops being bounded, the whole
       document lays out at full height, and the composer + escalation strip are pushed past the fold. */
    .conversation-zone.landing-collapsed {
      flex: 0 0 auto;
    }
    .composer.landing-dock .escalation-strip {
      margin-top: 0.75rem;
    }
    .landing-title {
      font-size: var(--font-size-lg);
      font-weight: 600;
      color: var(--text-primary);
    }
    .landing-corpus {
      font-size: var(--font-size-sm);
      color: var(--text-secondary);
    }
    .landing-add-folders {
      background: none;
      border: none;
      padding: 0;
      font: inherit;
      font-size: var(--font-size-sm);
      color: var(--accent);
      text-decoration: underline;
      cursor: pointer;
    }
    .escalation-strip {
      display: flex;
      flex-wrap: wrap;
      justify-content: center;
      gap: 1rem;
      font-size: var(--font-size-xs);
      color: var(--text-tertiary);
    }
    /* 807 B.2 — the DOCKED variant of the strip (rendered once a search has run, where the rungs used
       to disappear entirely). It rides under the bar with the row's own left alignment and the quiet
       gap of the other bar-secondary rows; the landing rules above are untouched. */
    .escalation-strip-docked {
      justify-content: flex-start;
      gap: 0.5rem;
      margin-top: 0.5rem;
      /* Tempdoc 816 §3 — \`control-row\`: a row of controls has no reason to stretch to fill; its max is
         the INTRINSIC width of its members. The generated frame already bounds it by the reading
         column (it is a child of the composer container); this stops it claiming even that much when
         its chips need less. Measured 1836px full-bleed at 1920 before this. */
      inline-size: fit-content;
      max-inline-size: 100%;
    }
    /* Tempdoc 816 §3 — the \`text-entry\` role's instance on this surface: what you type you immediately
       re-read, so the entry field takes the reading measure. \`ch\` resolves against the TEXTAREA's own
       13px type (not the 16px surface type), which is the point of a content-unit bound — the same
       token means the same number of characters, not the same pixels. Measured 1760px (251ch) at 1920
       before this. The landing composer (584px = 83ch) is already inside the bound and is unchanged. */
    .composer jf-composer textarea {
      /* The bound is on the RENDERED box, so the gate and the stylesheet judge the same number: a
         textarea is content-box by default, and with composerStyles' 0.5rem padding + 1px border the
         measured rect came out 635px against an 88ch (617px) cap — an 18px disagreement between what
         CSS was told to bound and what the camera sees. */
      box-sizing: border-box;
      max-inline-size: var(--measure-text-entry);
      /* border-box moves the padding INSIDE composerStyles' \`min-height: 3rem\` too, which would have
         shortened the input from 66px to 52px — an unintended BLOCK-axis change from an inline-axis
         fix (816 §2b: the axes couple, and the coupling is not allowed to be silent). Restate the
         same intent — 3rem of CONTENT — with the 0.5rem padding pair + 1px border pair added back. */
      min-block-size: calc(3rem + 1.125rem);
    }
    /* Schema input textarea: not migrated to jf-composer (no submit). */
    .composer textarea.mono {
      font-family: ui-monospace, 'Cascadia Code', 'Source Code Pro', Menlo, monospace;
      font-size: var(--font-size-sm);
    }
    .schema-label {
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
      text-transform: uppercase;
      letter-spacing: 0.04em;
      display: flex;
      align-items: center;
      gap: 0.5rem;
    }
    /* S5b pin-parity — the landing's pinned-search strip + the bar's pin toggle. */
    .pinned-row {
      display: flex;
      flex-wrap: wrap;
      justify-content: center;
      gap: 0.5rem;
      margin-top: 0.25rem;
    }
    .pinned-search-btn {
      background: none;
      border: 1px solid var(--border-subtle);
      border-radius: 0.375rem;
      padding: 0.1rem 0.45rem;
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
      cursor: pointer;
    }
    .pinned-search-btn:hover {
      color: var(--text-primary);
      border-color: var(--border-strong);
    }
    /*
     * Search Thread Round-2 R4 — the composer bar's SECONDARY affordances (pin, schema
     * attach/detach, the landing's agent-delegate entry) conform to the jf-control atom, skinned
     * as ONE quiet tier via ::part(control) (the RouteChip precedent) instead of four bespoke
     * button classes each hand-rolling the same bordered-pill look. jf-route-chip stays the row's
     * one visually PRIMARY element (its own, unrelated style, untouched here). Orphaned by this
     * conformance (deleted, not left for a later sweep): the old bespoke .pin-toggle /
     * .schema-attach / .schema-detach / .escalation-delegate button rules, which also lost the
     * accent-cascade fight against .composer button (Composer.ts's composerStyles, imported
     * ahead of this stylesheet) — .composer button is (0,1,1) specificity, a single bespoke class
     * is (0,1,0), so the imported accent-tint fill silently WON despite each class's own
     * background: none override. Composing jf-control sidesteps the fight entirely: its button
     * renders inside jf-control's OWN shadow root, which .composer button (scoped to THIS
     * shadow root) cannot reach at all — conformance, not a specificity war.
     */
    jf-control.pin-toggle::part(control),
    jf-control.schema-attach::part(control),
    jf-control.schema-detach::part(control),
    jf-control.escalation-delegate::part(control),
    .escalation-strip-docked jf-control::part(control) {
      display: inline-flex;
      align-items: center;
      gap: 0.3rem;
      padding: 0.1rem 0.5rem;
      border: 1px solid var(--border-subtle);
      border-radius: 0.375rem;
      background: none;
      color: var(--text-secondary);
      font-size: var(--font-size-xs);
      transition: color var(--duration-fast) var(--ease-standard),
        border-color var(--duration-fast) var(--ease-standard);
    }
    jf-control.pin-toggle::part(control):hover,
    jf-control.schema-attach::part(control):hover,
    jf-control.schema-detach::part(control):hover,
    jf-control.escalation-delegate::part(control):hover,
    .escalation-strip-docked jf-control::part(control):hover {
      color: var(--text-primary);
      border-color: var(--border-strong);
    }
    /* 807 B.2 — the rung you are ON. Without it the docked strip would offer the current tier as an
       escalation target and the click would look like a no-op. Deliberately NOT the pin-toggle's
       accent departure (an active tier is the normal state, not an exception worth an accent). */
    .escalation-strip-docked jf-control[data-pressed]::part(control) {
      color: var(--text-primary);
      border-color: var(--border-strong);
      background: var(--surface-hover);
    }
    /* The pinned state is the one deliberate departure from the shared quiet look (the accent
       marks the exception being pinned, not a fourth bespoke class). */
    jf-control.pin-toggle[data-pressed]::part(control) {
      color: var(--accent);
      border-color: var(--accent);
    }
    /* Spec A step 9 — chat unloaded for another GPU user: calm, not an error. */
    .chat-residency-notice {
      display: flex;
      align-items: center;
      gap: 0.75rem;
      padding: 0.5rem 0.75rem;
      border: 1px solid var(--border-subtle);
      border-radius: 0.375rem;
      color: var(--text-secondary);
      font-size: var(--font-size-sm);
    }
    .chat-residency-notice p {
      margin: 0;
      flex: 1;
    }
    .error {
      padding: 0.5rem 0.75rem;
      background: var(--accent-danger-08);
      border: 1px solid var(--accent-danger-30);
      border-radius: 0.375rem;
      color: var(--text-danger);
      font-size: var(--font-size-sm);
    }
    .preamble {
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
      padding: 0.35rem 0.5rem;
      background: var(--surface-2);
      border-radius: 0.25rem;
      font-style: italic;
    }
    .resume-prompt {
      padding: 0.75rem;
      background: var(--surface-2);
      border: 1px solid var(--border-subtle);
      border-radius: 0.375rem;
      font-size: var(--font-size-sm);
      color: var(--text-secondary);
      display: flex;
      flex-direction: column;
      gap: 0.35rem;
    }
    /* Tempdoc 577 §2.13 #17 — the authority-space panel container (toggled by the Abilities affordance). */
    .abilities-panel {
      padding: 0.75rem;
      margin-bottom: 0.5rem;
      background: var(--surface-2);
      border: 1px solid var(--border-subtle);
      border-radius: 0.375rem;
    }
    .resume-prompt em {
      color: var(--text-primary);
      font-style: normal;
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }
    .resume-actions {
      display: flex;
      gap: 0.5rem;
      margin-top: 0.25rem;
    }
    .resume-btn {
      all: unset;
      font-size: var(--font-size-xs);
      padding: 0.2rem 0.6rem;
      border-radius: 0.25rem;
      background: var(--accent-tint);
      color: var(--accent-on-tint);
      cursor: pointer;
    }
    .dismiss-btn {
      all: unset;
      font-size: var(--font-size-xs);
      padding: 0.2rem 0.6rem;
      color: var(--text-muted);
      cursor: pointer;
    }
    .dismiss-btn:hover {
      color: var(--text-primary);
    }
    /* Two forms of this control ship side by side: native <button class="new-chat-btn"> (Activity,
       Export) and the composed <jf-control class="new-chat-btn"> (New chat, 596 typed availability).
       The all:unset reset strips UA button chrome and belongs to the NATIVE form only. The
       jf-control's inner button already unsets inside the primitive, and repeating it through
       ::part(control) would also reset the primitive's focus outline — an outer-tree ::part
       declaration beats the inner-tree rule regardless of specificity — blanking the focus ring on
       exactly the state typed availability makes focusable (aria-disabled is reachable, unlike
       native [disabled]). The host is kept out of the box styling for the same reason of not
       styling one button twice: border and padding render on the button, not on it AND its host. */
    button.new-chat-btn {
      all: unset;
    }
    button.new-chat-btn,
    jf-control.new-chat-btn {
      margin-left: 0.5rem;
    }
    button.new-chat-btn,
    jf-control.new-chat-btn::part(control) {
      font-size: var(--font-size-xs);
      color: var(--text-muted);
      cursor: pointer;
      padding: 0.15rem 0.4rem;
      border: 1px solid var(--border-subtle);
      border-radius: 0.25rem;
    }
    button.new-chat-btn:hover:not(:disabled),
    jf-control.new-chat-btn::part(control):hover:not([aria-disabled='true']) {
      color: var(--text-primary);
      border-color: var(--accent-tint);
    }
    /* The composed jf-control's unavailable state is aria-disabled, not native [disabled]
       (596 typed availability) — dim both forms. */
    button.new-chat-btn:disabled,
    jf-control.new-chat-btn::part(control)[aria-disabled='true'] {
      opacity: 0.35;
      cursor: default;
    }
    .affordance-preview {
      font-size: var(--font-size-xs);
      color: var(--text-muted);
      padding: 0.15rem 0;
    }
    /* Tempdoc 610 §D.2 — the ONE per-turn action bar (ChatGPT/Claude grammar):
       a hover-revealed icon row BELOW the message. Assistant: inside the message
       box (bottom). User: a right-aligned sibling row beneath the bubble. */
    .turn-actions {
      display: flex;
      align-items: center;
      gap: 0.1rem;
      margin-top: 0.2rem;
      opacity: 0;
      transition: opacity var(--duration-fast);
    }
    .turn-actions.user-actions {
      align-self: flex-end;
    }
    .turn-actions.assistant-actions {
      align-self: flex-start;
    }
    /* Reveal: assistant bar lives inside the message; user bar is a sibling. The
       bar stays visible while the pointer is on it (so the ⋯ is clickable). */
    .message.assistant:hover .turn-actions,
    .message.user:hover + .turn-actions,
    .turn-actions:hover {
      opacity: 1;
    }
    .turn-act-btn {
      all: unset;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      cursor: pointer;
      padding: 0.2rem;
      border-radius: 0.25rem;
      color: var(--text-muted);
      line-height: 0;
    }
    .turn-act-btn:hover {
      background: var(--surface-2);
      color: var(--text-primary);
    }
    /* Tempdoc 610 §D.2 — edit morphs the bubble IN PLACE: keep the user turn's
       right-aligned position + bubble max-width (no full-width stretch); only
       drop the bubble fill so the textarea reads as an edit field. */
    .message.user.editing {
      background: transparent;
      color: var(--text-primary);
      padding: 0.25rem 0;
    }
    .msg-edit {
      width: 100%;
      min-height: 3rem;
      box-sizing: border-box;
      font: inherit;
      font-size: var(--font-size-sm);
      color: var(--text-primary);
      background: var(--surface-1);
      border: 1px solid var(--border-subtle);
      border-radius: 0.3rem;
      padding: 0.4rem 0.5rem;
      resize: vertical;
    }
    .msg-edit:focus {
      outline: 2px solid transparent;
      border-color: var(--accent-tint);
    }
    .msg-edit-actions {
      display: flex;
      gap: 0.5rem;
      margin-top: 0.4rem;
      justify-content: flex-end;
    }
    .msg-edit-save,
    .msg-edit-cancel {
      all: unset;
      font-size: var(--font-size-xs);
      cursor: pointer;
      padding: 0.2rem 0.6rem;
      border-radius: 0.25rem;
    }
    .msg-edit-save {
      background: var(--accent-tint);
      color: var(--accent-on-tint);
    }
    .msg-edit-cancel {
      color: var(--text-secondary);
      border: 1px solid var(--border-subtle);
    }
    /* Tempdoc 610 Phase B — inline version pager (‹ n/m ›). */
    .version-pager {
      display: inline-flex;
      align-items: center;
      gap: 0.2rem;
      margin-left: 0.5rem;
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
      vertical-align: middle;
    }
    .message.user .version-pager {
      color: var(--accent-on-tint);
    }
    .ver-nav {
      all: unset;
      cursor: pointer;
      display: inline-flex;
      align-items: center;
      border-radius: 0.2rem;
      padding: 0.05rem;
      color: inherit;
    }
    .ver-nav:hover:not(:disabled) {
      background: var(--surface-2);
      color: var(--text-primary);
    }
    .ver-nav:disabled {
      opacity: 0.35;
      cursor: default;
    }
    .ver-count {
      font-variant-numeric: tabular-nums;
    }
    /* Tempdoc 610 Phase C — context-floor divider + out-of-context band.
       Distinct from the ↪ inherited-prefix language: this means "the assistant
       no longer sees this", not "this came from a parent". */
    .context-floor-divider {
      align-self: stretch;
      display: flex;
      align-items: center;
      gap: 0.5rem;
      margin: 0.6rem 0;
      padding: 0.2rem 0.5rem;
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
      border-top: 1px solid var(--border-strong);
    }
    .context-floor-divider .cfd-label {
      flex: 1;
    }
    .cfd-restore {
      all: unset;
      cursor: pointer;
      color: var(--text-primary);
      text-decoration: underline;
      padding: 0.1rem 0.4rem;
      border-radius: 0.2rem;
    }
    .cfd-restore:hover {
      background: var(--surface-2);
    }
    .message.out-of-context {
      opacity: 0.45;
      filter: grayscale(0.4);
    }
    /* Tempdoc 610 §E.3 — a per-message exclusion: dimmed like the out-of-context band but marked
       with a dashed rail so "I hid this turn" reads distinct from "this is below the floor". */
    .message.excluded {
      opacity: 0.5;
      filter: grayscale(0.5);
      border-left: 2px dashed var(--border-strong);
      padding-left: 0.4rem;
    }
    .cfd-summary {
      align-self: stretch;
      margin: 0 0 0.6rem 0;
      padding: 0.4rem 0.6rem;
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
      background: var(--surface-2);
      border-left: 2px solid var(--border-strong);
      border-radius: 0.25rem;
      white-space: pre-wrap;
    }
    /* Tempdoc 610 §E.2 — in-place edit of the compaction summary. */
    .cfd-summary-editing {
      display: flex;
      flex-direction: column;
      gap: 0.4rem;
    }
    .cfd-summary-input {
      width: 100%;
      min-height: 4rem;
      box-sizing: border-box;
      resize: vertical;
      font: inherit;
      font-size: var(--font-size-xs);
      color: var(--text-primary);
      background: var(--surface-1);
      border: 1px solid var(--border-strong);
      border-radius: 0.25rem;
      padding: 0.4rem 0.6rem;
    }
    .cfd-summary-actions {
      display: flex;
      gap: 0.5rem;
    }
    /* Slice 513 — branch indicator above inherited prefix and per-message dim */
    .branch-indicator {
      font-size: var(--font-size-xs);
      color: var(--text-muted);
      padding: 0.25rem 0.5rem;
      margin-bottom: 0.5rem;
      border-left: 2px solid var(--accent-tint);
      background: var(--surface-2);
      font-style: italic;
    }
    .message.inherited {
      opacity: 0.7;
    }
    .message.inherited::before {
      content: '↪';
      color: var(--text-tint);
      margin-right: 0.4rem;
      font-style: normal;
    }
    .thinking-timer {
      font-size: var(--font-size-sm);
      color: var(--text-secondary);
      padding: 0.5rem 0;
      font-variant-numeric: tabular-nums;
    }

    /* Tempdoc 577 Goal 3 (§3.2) — the retrieve base tier: the ephemeral hit-list. */
    .retrieve-tier {
      display: flex;
      flex-direction: column;
      gap: 0.5rem;
      padding: 0.25rem 0;
    }
    .retrieve-empty {
      color: var(--text-secondary);
      font-size: var(--font-size-sm);
      line-height: 1.5;
      padding: 1rem 0.25rem;
    }
    .retrieve-empty strong {
      color: var(--text-primary);
    }
    .retrieve-meta {
      font-size: var(--font-size-xs);
      color: var(--text-tertiary);
      padding: 0 0.25rem;
    }
    .retrieve-results {
      display: flex;
      flex-direction: column;
      gap: 0.25rem;
    }
    .retrieve-row {
      display: flex;
      flex-direction: column;
      padding: 0.5rem 0.625rem;
      border: 1px solid var(--border-subtle);
      border-radius: 0.375rem;
      background: var(--surface-1);
    }
    .retrieve-row:hover {
      background: var(--surface-hover);
    }
    .retrieve-row-open {
      display: flex;
      flex-direction: column;
      gap: 0.15rem;
      width: 100%;
      text-align: left;
      padding: 0;
      border: none;
      background: none;
      color: var(--text-primary);
      cursor: pointer;
      font: inherit;
    }
    .retrieve-row-open:focus-visible {
      outline: 2px solid var(--focus-ring-color);
      outline-offset: 1px;
    }
    .retrieve-row-title {
      display: flex;
      align-items: center;
      gap: 0.375rem;
      font-size: var(--font-size-sm);
    }
    .retrieve-row-title .kind-icon {
      display: inline-flex;
      color: var(--text-tertiary);
    }
    .retrieve-row-title .title-text {
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    .retrieve-row-title .line-anchor {
      font-size: var(--font-size-xs);
      color: var(--text-tertiary);
      font-variant-numeric: tabular-nums;
    }
    .retrieve-row-path {
      font-size: var(--font-size-xs);
      color: var(--text-tertiary);
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    .retrieve-row-snippet {
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
      line-height: 1.4;
      display: -webkit-box;
      -webkit-line-clamp: 2;
      -webkit-box-orient: vertical;
      overflow: hidden;
    }
`;
