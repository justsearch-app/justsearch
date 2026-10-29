// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest';

import { knowledgeSearchResponseSchema } from '../generated/schema-types/knowledge-search-response';
import type { SearchFilters } from './search';

describe('folder date and byte restriction wire echo', () => {
  it('parses all bounds including zero through the generated response contract', () => {
    const filters: SearchFilters = {
      pathPrefix: '/space ü/%_',
      modifiedAt: { fromMs: 1, toMs: 253402300799999 },
      sizeBytes: { fromBytes: 0, toBytes: 9007199254740991 },
    };
    const parsed = knowledgeSearchResponseSchema.safeParse({
      totalHits: 0,
      tookMs: 1,
      results: [],
      appliedFilters: { filters },
    });
    expect(parsed.success).toBe(true);
    if (parsed.success) expect(parsed.data.appliedFilters?.filters).toEqual(filters);
  });
});
