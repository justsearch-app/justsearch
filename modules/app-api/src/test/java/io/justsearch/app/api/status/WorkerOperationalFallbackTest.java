package io.justsearch.app.api.status;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class WorkerOperationalFallbackTest {
  @Test
  void fallbackOwnCountStaysUnknownInStatusJson() {
    var fallback = WorkerOperationalView.fallback("SERVING");

    assertNull(fallback.core().ownSearchableDocuments(),
        "fallback status cannot establish how many own documents are searchable");
    var json = JsonMapper.builder().build().valueToTree(fallback);
    assertTrue(json.path("core").path("ownSearchableDocuments").isNull(),
        "status JSON must preserve the unknown fallback own count");
  }

  @Test
  void fallbackDocumentCountsAreUnknownInStatusJson() {
    var json = JsonMapper.builder().build().valueToTree(WorkerOperationalView.fallback("UNAVAILABLE"));

    assertTrue(json.path("core").path("indexedDocumentsUnknown").asBoolean(false),
        "a status without a Worker reading must not claim its zero document counts are known");
  }
}
