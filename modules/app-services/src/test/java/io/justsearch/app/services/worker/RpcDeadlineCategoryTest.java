package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.justsearch.app.services.worker.KnowledgeClient.RpcDeadlineCategory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for RpcDeadlineCategory enum.
 *
 * <p>Verifies that deadline multipliers are correctly applied to base deadlines.
 * Each category has a specific multiplier used to scale the base deadline for
 * different types of gRPC operations.
 */
@DisplayName("RpcDeadlineCategory")
class RpcDeadlineCategoryTest {

  private static final long BASE_DEADLINE_MS = 5000L; // 5 seconds

  @Nested
  @DisplayName("apply()")
  class ApplyTests {

    @Test
    @DisplayName("STANDARD applies 1.0x multiplier")
    void standard_appliesOneXMultiplier() {
      long result = RpcDeadlineCategory.STANDARD.apply(BASE_DEADLINE_MS);
      assertEquals(5000L, result);
    }

    @Test
    @DisplayName("CONTENT_FETCH applies 2.0x multiplier")
    void contentFetch_appliesTwoXMultiplier() {
      long result = RpcDeadlineCategory.CONTENT_FETCH.apply(BASE_DEADLINE_MS);
      assertEquals(10000L, result);
    }

    @Test
    @DisplayName("VDU_OPERATION applies 2.0x multiplier")
    void vduOperation_appliesTwoXMultiplier() {
      long result = RpcDeadlineCategory.VDU_OPERATION.apply(BASE_DEADLINE_MS);
      assertEquals(10000L, result);
    }

    @Test
    @DisplayName("RERANK applies 12.0x multiplier")
    void rerank_appliesTwelveXMultiplier() {
      long result = RpcDeadlineCategory.RERANK.apply(BASE_DEADLINE_MS);
      assertEquals(60000L, result);
    }

    @Test
    @DisplayName("INDEX_GC applies 6.0x multiplier")
    void indexGc_appliesSixXMultiplier() {
      long result = RpcDeadlineCategory.INDEX_GC.apply(BASE_DEADLINE_MS);
      assertEquals(30000L, result);
    }

    @Test
    @DisplayName("LONG_RUNNING applies 60.0x multiplier")
    void longRunning_appliesSixtyXMultiplier() {
      long result = RpcDeadlineCategory.LONG_RUNNING.apply(BASE_DEADLINE_MS);
      assertEquals(300000L, result);
    }

    @Test
    @DisplayName("apply with zero base returns zero")
    void apply_withZeroBase_returnsZero() {
      assertEquals(0L, RpcDeadlineCategory.STANDARD.apply(0L));
      assertEquals(0L, RpcDeadlineCategory.CONTENT_FETCH.apply(0L));
      assertEquals(0L, RpcDeadlineCategory.VDU_OPERATION.apply(0L));
      assertEquals(0L, RpcDeadlineCategory.RERANK.apply(0L));
      assertEquals(0L, RpcDeadlineCategory.INDEX_GC.apply(0L));
      assertEquals(0L, RpcDeadlineCategory.LONG_RUNNING.apply(0L));
    }

    @Test
    @DisplayName("apply with different base deadline")
    void apply_withDifferentBaseDeadline() {
      long baseTenSeconds = 10000L;

      assertEquals(10000L, RpcDeadlineCategory.STANDARD.apply(baseTenSeconds));
      assertEquals(20000L, RpcDeadlineCategory.CONTENT_FETCH.apply(baseTenSeconds));
      assertEquals(60000L, RpcDeadlineCategory.INDEX_GC.apply(baseTenSeconds));
      assertEquals(600000L, RpcDeadlineCategory.LONG_RUNNING.apply(baseTenSeconds));
    }
  }

  @Nested
  @DisplayName("enum values")
  class EnumValuesTests {

    @Test
    @DisplayName("all expected categories exist")
    void allExpectedCategoriesExist() {
      RpcDeadlineCategory[] values = RpcDeadlineCategory.values();
      assertEquals(6, values.length);
      assertEquals(RpcDeadlineCategory.STANDARD, RpcDeadlineCategory.valueOf("STANDARD"));
      assertEquals(RpcDeadlineCategory.CONTENT_FETCH, RpcDeadlineCategory.valueOf("CONTENT_FETCH"));
      assertEquals(RpcDeadlineCategory.VDU_OPERATION, RpcDeadlineCategory.valueOf("VDU_OPERATION"));
      assertEquals(RpcDeadlineCategory.RERANK, RpcDeadlineCategory.valueOf("RERANK"));
      assertEquals(RpcDeadlineCategory.INDEX_GC, RpcDeadlineCategory.valueOf("INDEX_GC"));
      assertEquals(RpcDeadlineCategory.LONG_RUNNING, RpcDeadlineCategory.valueOf("LONG_RUNNING"));
    }
  }
}
