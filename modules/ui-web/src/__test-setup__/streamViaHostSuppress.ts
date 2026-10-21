// SPDX-License-Identifier: Apache-2.0
/**
 * Tempdoc 521 §22 Phase E — vitest setup file that opts the test
 * suite into the streamViaHost fallback path. Production code throws
 * when `host_` is missing (a missing-host_ mount is a real
 * regression); test contexts here deliberately mock fetch /
 * consumeShapeStream and expect the fallback.
 *
 * Production code never sets this flag.
 */
import { afterEach, vi } from 'vitest';

(globalThis as { __STREAM_VIA_HOST_ALLOW_FALLBACK__?: boolean })
  .__STREAM_VIA_HOST_ALLOW_FALLBACK__ = true;

// A synchronous caller may deliberately launch a lazy import (for example the
// selection capability projection). Finish that work before the test's module
// environment is disposed, including any imports it starts in turn.
afterEach(() => vi.dynamicImportSettled());
