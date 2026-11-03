// SPDX-License-Identifier: Apache-2.0
/**
 * Happy DOM 21 defect: `ShadowRoot.activeElement` throws a TypeError when focus is inside a
 * DIFFERENT shadow tree — its host walk passes the document and reads `undefined.getRootNode()`.
 * That throw happens exactly when the focused element, retargeted against this root, is not in
 * it, where browsers return null. Return the browser's null for that case and Happy DOM's own
 * answer everywhere else, so a valid state (focus in the status panel while another component
 * reads its own shadow focus) cannot crash a test. Production code is unaffected.
 */
if (typeof ShadowRoot !== 'undefined') {
  const original = Object.getOwnPropertyDescriptor(ShadowRoot.prototype, 'activeElement');
  const read = original?.get;
  if (original && read) {
    Object.defineProperty(ShadowRoot.prototype, 'activeElement', {
      ...original,
      get(this: ShadowRoot): Element | null {
        try {
          return read.call(this) as Element | null;
        } catch (error) {
          if (error instanceof TypeError) return null;
          throw error;
        }
      },
    });
  }
}
