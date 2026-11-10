/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.ipc.CoreStatus;
import io.justsearch.ipc.StatusResponse;
import org.junit.jupiter.api.Test;

final class WorkerStatusMapperOwnCountTest {
  @Test
  void mapperAndStatusJsonCarryOwnCount() throws Exception {
    var core = CoreStatus.newBuilder().setDocCount(8).setSearchableDocCount(5);
    var field = core.getDescriptorForType().findFieldByName("own_searchable_doc_count");
    assertNotNull(field, "IPC carries the worker's own count");
    core.setField(field, 3L);
    var mapped = WorkerStatusMapper.toUiStatusMap(StatusResponse.newBuilder().setCore(core).build(), null).core();
    var json = new tools.jackson.databind.ObjectMapper().valueToTree(mapped);
    assertEquals(3L, json.path("ownSearchableDocuments").asLong());
    assertEquals(5L, mapped.searchableDocuments());
  }

  @Test
  void olderWorkerAndUnknownIndexMeanUnknownOwnCount() {
    for (boolean unknown : new boolean[] {false, true}) {
      var core = CoreStatus.newBuilder().setDocCount(5).setSearchableDocCount(5).setDocCountUnknown(unknown);
      var mapped = WorkerStatusMapper.toUiStatusMap(StatusResponse.newBuilder().setCore(core).build(), null).core();
      var json = new tools.jackson.databind.ObjectMapper().valueToTree(mapped);
      assertTrue(json.has("ownSearchableDocuments"), "JSON declares own count, including unknown");
      assertTrue(json.path("ownSearchableDocuments").isNull(), "absence is unknown, not all documents or zero");
    }
  }
}
