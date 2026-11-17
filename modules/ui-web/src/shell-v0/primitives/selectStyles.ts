// SPDX-License-Identifier: Apache-2.0
/**
 * A native `<select>` drawn by the page in the theme's colours, closed and open, never by the platform's
 * own (light) control. The page's `select { color-scheme }` rule (tokens.css) does not reach into a shadow
 * root, and the desktop app's WebKit view draws a plain select white whatever the scheme; so every
 * component with a native select composes this fragment into its static styles.
 *
 * The recipe (verified in the packaged app on the pause-duration select): no platform appearance,
 * explicit colours for the field and its options, and the page's own chevron (drawn here with two
 * gradients in the secondary text colour, so the select's markup, label and layout stay as they are).
 * `:not([multiple])` outranks a component's plain `select` or class rule, so a component's
 * `background` shorthand cannot drop the chevron; size, borders, radius, focus ring and the disabled
 * look stay the component's own. In forced colours the platform draws the control in system colours.
 */
import { css } from 'lit';

export const selectStyles = css`
  select:not([multiple]) {
    appearance: none;
    background-color: var(--surface-1);
    color: var(--text-primary);
    background-image:
      linear-gradient(45deg, transparent 50%, var(--text-secondary) 50%),
      linear-gradient(135deg, var(--text-secondary) 50%, transparent 50%);
    background-position: calc(100% - 13px) 50%, calc(100% - 8px) 50%;
    background-size: 5px 5px, 5px 5px;
    background-repeat: no-repeat;
    padding-inline-end: 1.6rem;
  }
  select:not([multiple]) option,
  select:not([multiple]) optgroup {
    background-color: var(--surface-1);
    color: var(--text-primary);
  }
  @media (forced-colors: active) {
    select:not([multiple]) {
      appearance: auto;
      background-image: none;
    }
  }
`;
