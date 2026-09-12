/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.packimport;

import io.justsearch.app.api.AiPackImportService;
import io.justsearch.app.api.PackImportService;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Production implementation of {@link PackImportService}, extracted from
 * {@code AiPackController} as part of tempdoc 519 §9 Block B3 / Step 3. The HTTP-handler
 * methods stay in {@code AiPackController}; this class owns the service-interface body.
 *
 * <p>Composes the {@link AiPackImportService} helper interface (defined in {@code app-api}
 * by B2; impl lives in {@code modules/ui/.../ai/pack/AiPackImportService.java}). The helper
 * is constructed by {@code LocalApiServer} and passed to this impl at construction time.
 */
public final class PackImportServiceImpl implements PackImportService {

  private static final ObjectMapper MAPPER =
      JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

  private final AiPackImportService helper;

  public PackImportServiceImpl(AiPackImportService helper) {
    this.helper = Objects.requireNonNull(helper, "helper");
  }

  @Override
  public Map<String, Object> preflight(String path) throws Exception {
    if (path == null || path.isBlank()) {
      throw new IllegalArgumentException("Missing pack path");
    }
    return convertToMap(helper.preflight(Path.of(path)));
  }

  @Override
  public AiPackImportService.Attempt startImport(String path, boolean allowDowngrade) throws Exception {
    if (path == null || path.isBlank()) {
      throw new IllegalArgumentException("Missing pack path");
    }
    return helper.startImport(Path.of(path), allowDowngrade);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> convertToMap(Object value) {
    return MAPPER.convertValue(value, Map.class);
  }
}
