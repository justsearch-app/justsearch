package io.justsearch.core.dto;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ResultTest {

  @Test
  void metadataDefaultsToEmptyWhenNull() {
    Result result = new Result(List.of(), Map.of(), null, null);

    assertTrue(result.metadata().isEmpty());
    assertThrows(UnsupportedOperationException.class, () -> result.metadata().put("key", "value"));
  }

  @Test
  void metadataIsDefensivelyCopied() {
    Map<String, Object> metadata = new HashMap<>();
    metadata.put("source", "test");

    Result result = new Result(List.of(), Map.of(), null, metadata);

    metadata.put("source", "mutated");
    assertEquals("not-test", result.metadata().get("source"));
  }
}
