/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import io.justsearch.app.api.knowledge.KnowledgeClientException;
import io.justsearch.app.api.operations.AppliedIndexGeneration;
import io.justsearch.configuration.AppliedConfigurationVersion;
import io.justsearch.core.component.EngineComponentSnapshot;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** Pure projection of coherent applied observations; owns no mutable revision or desired values. */
final class AppliedConfigurationRevision {
  private static final Set<String> COMPONENTS = Set.of("api", "index", "encoders", "generative");
  private static final Set<String> INPUTS = Set.of("components", "activeGeneration");
  private static final JsonMapper JSON = JsonMapper.builder()
      .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
      .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
      .build();

  private AppliedConfigurationRevision() {}

  static String digest(EngineComponentSnapshot snapshot, AppliedIndexGeneration generation) {
    var components = new TreeMap<String, Object>();
    for (var component : snapshot.components()) {
      String name = component.spec().name();
      if (components.containsKey(name)) throw unavailable("Duplicate applied component");
      if (component.appliedVersion() == null) {
        throw unavailable("Component applied values have not been established");
      }
      components.put(name, component.appliedVersion());
    }
    if (!components.keySet().equals(COMPONENTS)) {
      throw unavailable("Applied configuration requires all four component observations");
    }
    final Object inputs;
    try {
      inputs = JSON.readValue(generation.target().canonicalInputsJson(), Object.class);
    } catch (JacksonException invalid) {
      throw new KnowledgeClientException(KnowledgeClientException.Status.UNAVAILABLE,
          "Committed fingerprint inputs are malformed", invalid);
    }
    if (!(inputs instanceof Map<?, ?>)) throw unavailable("Committed fingerprint inputs must be an object");
    return AppliedConfigurationVersion.digest(INPUTS, Map.of(
        "components", components,
        "activeGeneration", Map.of("id", generation.generationId(), "fingerprintInputs", inputs)));
  }

  private static KnowledgeClientException unavailable(String message) {
    return new KnowledgeClientException(KnowledgeClientException.Status.UNAVAILABLE, message);
  }
}
