// SPDX-License-Identifier: Apache-2.0
/**
 * sv3Shared — the stylesheet every Search v3 component adopts (tempdoc 822 slice 1).
 *
 * Derived from a third-party design system (MIT) — see THIRD-PARTY-NOTICES.md in this directory.
 *
 * Holds the constructs that a host-scoped token declaration cannot deliver, because they do not
 * inherit: keyframes are resolved per shadow root, so an `animation-name` referencing a keyframe
 * declared outside the root fails SILENTLY. Tokens stay in `sv3Tokens`; structure lives here.
 *
 * The looping indicators are duty-cycled: they hold at each extreme for ~40% of the cycle and ramp
 * in `steps()`, so the compositor draws a handful of discrete frames per cycle instead of one per
 * vsync — the difference between ~14 and ~288 updates on a 120Hz display, for indicators that a
 * local-first desktop app leaves on screen for hours.
 */
import { css } from 'lit';

export const sv3Shared = css`
  /* Every geometry token names a TOTAL: --sidebar-width is the whole 256px region, with the 8px
     panel inset and the 10px row inset living inside it. Under the default content-box, a host's padding and border would
     be added OUTSIDE that number and every token would silently mean "at least" — measured 273px
     and 53px. Border-box makes the token the total it claims to be. */
  :host,
  * {
    box-sizing: border-box;
  }

  @keyframes skeleton {
    /* Transform-only so the sweep stays on the compositor, then a long hold with the band parked
       off-screen instead of a constant shimmer. */
    0% {
      transform: translateX(-100%);
    }
    60%,
    100% {
      transform: translateX(100%);
    }
  }

  @keyframes ghost-pulse {
    0%,
    42% {
      opacity: 1;
      animation-timing-function: steps(4);
    }
    50%,
    92% {
      opacity: 0.55;
      animation-timing-function: steps(4);
    }
    100% {
      opacity: 1;
    }
  }

  @keyframes status-pulse {
    0%,
    40% {
      opacity: 1;
      animation-timing-function: steps(6);
    }
    50%,
    90% {
      opacity: 0.5;
      animation-timing-function: steps(6);
    }
    100% {
      opacity: 1;
    }
  }

  @keyframes status-ping {
    /* Burst first (immediate feedback), then hold invisible for the rest of the cycle. */
    0% {
      opacity: 0.9;
      scale: 0.75;
      animation-timing-function: steps(8);
    }
    40%,
    100% {
      opacity: 0;
      scale: 2;
    }
  }

  .sv3-anim-skeleton {
    animation: skeleton 2s infinite linear;
  }
  .sv3-anim-ghost-pulse {
    animation: ghost-pulse 2.4s infinite;
  }
  .sv3-anim-status-pulse {
    animation: status-pulse 2s infinite;
  }
  .sv3-anim-status-ping {
    animation: status-ping 2s infinite;
  }

  /* The scroller mixin. scrollbar-color is an inherited standard property, so the window's
     --app-scrollbar-* tokens reach a scroller in any nested shadow root through it; the
     non-inherited scrollbar-width half already arrives via the app's adopted ambient sheet. */
  .sv3-scroller {
    overflow-y: auto;
    overscroll-behavior: contain;
    scrollbar-color: var(--app-scrollbar-thumb) transparent;
  }

  @media (prefers-reduced-motion: reduce) {
    .sv3-anim-skeleton,
    .sv3-anim-ghost-pulse,
    .sv3-anim-status-pulse,
    .sv3-anim-status-ping {
      animation: none;
    }
  }
`;
