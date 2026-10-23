/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.configuration.FieldCatalogDef.FieldDef;
import java.util.*;

final class VduRevisionTestSupport {
  private VduRevisionTestSupport() {}
  static FieldCatalogDef catalog(int dimensions) {
    var fields = new ArrayList<>(FieldCatalogDef.forChunkTestingWithVduRetryCount(dimensions).fields());
    fields.add(new FieldDef("source_sha256","keyword",true,false,List.of(),null,null,false));
    fields.add(new FieldDef("ner_status","keyword",true,true,List.of("filter"),null,null,false));
    fields.add(new FieldDef("ner_retry_count","long",true,true,List.of(),null,null,false));
    fields.add(new FieldDef("entity_persons_raw","keyword",true,true,List.of("filter"),null,null,true));
    return new FieldCatalogDef("vdu-revision",fields);
  }
}
