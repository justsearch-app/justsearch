/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.knowledge;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.app.api.RetrieveContextParams;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SearchRestrictionsTest {
  @Test
  void exactMaximumNullAndZeroSemantics() {
    var value = SearchRestrictions.fromJson("{\"filters\":{\"pathPrefix\":\"/space ü/%_\",\"modifiedAt\":{\"fromMs\":1,\"toMs\":253402300799999},\"sizeBytes\":{\"fromBytes\":0,\"toBytes\":9007199254740991}}}");
    assertEquals("/space ü/%_", value.pathPrefix());
    assertEquals(253_402_300_799_999L, value.modifiedAt().toMs());
    assertEquals(0L, value.sizeBytes().fromBytes());
    assertEquals(9_007_199_254_740_991L, value.sizeBytes().toBytes());
    assertTrue(RetrieveContextParams.of("q", 5, 100).withRestrictions(value).hasRestrictions());
    var unset = SearchRestrictions.fromJson("{\"filters\":{\"pathPrefix\":null,\"modifiedAt\":{\"fromMs\":0,\"toMs\":null},\"sizeBytes\":{\"fromBytes\":null}}}");
    assertFalse(SearchRestrictions.active(unset));
    assertNull(unset.modifiedAt());
    assertNull(unset.sizeBytes());
    assertFalse(RetrieveContextParams.of("q", 5, 100).withRestrictions(unset).hasRestrictions());
    var zero = SearchRestrictions.parse(Map.of("sizeBytes", Map.of("toBytes", 0)));
    assertTrue(SearchRestrictions.active(zero));
    assertNotNull(KnowledgeSearchResponse.AppliedFilters.of(zero, null));
  }

  @Test
  void decimalParsingNeverRoundsAnInvalidBoundIntoTheDomain() {
    for (String raw : new String[] {"9007199254740990.5", "9007199254740991.0000001", "9007199254740992", "1e100"}) {
      var error = assertThrows(IllegalArgumentException.class, () -> SearchRestrictions.fromJson(
          "{\"filters\":{\"sizeBytes\":{\"toBytes\":" + raw + "}}}"));
      assertTrue(error.getMessage().contains("sizeBytes.toBytes"));
    }
    var exact = SearchRestrictions.fromJson("{\"filters\":{\"sizeBytes\":{\"toBytes\":9.007199254740991e15}}}");
    assertEquals(SearchRestrictions.MAX_SIZE_BYTES, exact.sizeBytes().toBytes());
    assertEquals(4096, SearchRestrictions.parse(Map.of("pathPrefix", "/" + "a".repeat(4095))).pathPrefix().length());
  }
}
