/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import io.justsearch.ipc.UpdateVduResultRequest;
import java.util.HashMap;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

/** Frozen pre-C2 payload fixture; production no longer accepts VDU buffer writes. */
final class LegacyVduBufferFixture {
  private static final ObjectMapper JSON = new ObjectMapper();
  private LegacyVduBufferFixture() {}

  static String switchBufferVduUpdateKey(String normalizedDocId) {
    return "vdu_update:" + normalizedDocId;
  }

  static String updateVduSwitchBufferPayload(UpdateVduResultRequest request, String normalizedId)
      throws Exception {
    Map<String, Object> payloadMap = new HashMap<>();
    payloadMap.put("doc_id", normalizedId);
    payloadMap.put("extracted_content", request.hasExtractedContent() ? request.getExtractedContent() : null);
    payloadMap.put("has_extracted_content", request.hasExtractedContent());
    payloadMap.put("vdu_status", request.getVduStatus());
    payloadMap.put("vdu_enrichment", request.getVduEnrichment());
    payloadMap.put("page_count", request.getPageCount());
    payloadMap.put("outcome", request.getOutcome().getNumber());
    return JSON.writeValueAsString(payloadMap);
  }

}
