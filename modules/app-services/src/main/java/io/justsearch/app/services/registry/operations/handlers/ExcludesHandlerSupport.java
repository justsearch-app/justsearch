/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.operations.handlers;

import io.justsearch.core.context.EngineContext;

import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.ExcludesService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;

/**
 * Shared run-and-map helper for {@link PreviewExcludesHandler} and
 * {@link ApplyExcludesHandler}. The two only differ in the dryRun flag; this
 * helper resolves the supplier, invokes {@link ExcludesService#applyExcludes},
 * and translates {@link ExcludesService.ExcludesResult} into the
 * {@code structuredData} map that mirrors the pre-existing
 * {@code POST /api/indexing/excludes/apply} response shape.
 */
final class ExcludesHandlerSupport {

  private ExcludesHandlerSupport() {}

  static OperationResult run(
      Supplier<ExcludesService> supplier, boolean dryRun, Logger log, String handlerLabel, EngineContext engineContext) {
    ExcludesService excludes;
    try {
      excludes = supplier.get();
    } catch (RuntimeException e) {
      log.warn("{}: excludes supplier threw", handlerLabel, e);
      return OperationResult.failure("Excludes service unavailable: " + e.getMessage());
    }
    if (excludes == null) {
      return OperationResult.failure("Excludes service unavailable");
    }

    ExcludesService.ExcludesResult result;
    try {
      result = excludes.applyExcludes(dryRun, engineContext);
    } catch (Exception e) {
      log.error("{}: applyExcludes threw (dryRun={})", handlerLabel, dryRun, e);
      return OperationResult.failure(
          (dryRun ? "Preview excludes failed: " : "Apply excludes failed: ")
              + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
    }

    return OperationResult.success(buildMessage(result), toStructuredData(result));
  }

  private static String buildMessage(ExcludesService.ExcludesResult r) {
    if (r.message() != null) {
      return r.message();
    }
    if (r.dryRun()) {
      return "Preview: matched "
          + r.matchedFiles()
          + " files across "
          + r.rootsProcessed()
          + " roots ("
          + r.patterns()
          + " patterns)";
    }
    return "Applied excludes: "
        + r.deletedById()
        + " files + "
        + r.deletedByPathJobs()
        + " path-prefix jobs deleted across "
        + r.rootsProcessed()
        + " roots";
  }

  private static Map<String, Object> toStructuredData(ExcludesService.ExcludesResult r) {
    List<Map<String, Object>> perPattern = new ArrayList<>(r.perPattern().size());
    for (ExcludesService.ExcludesResult.PatternMatch pm : r.perPattern()) {
      perPattern.add(Map.of("pattern", pm.pattern(), "matches", pm.matches()));
    }
    LinkedHashMap<String, Object> out = new LinkedHashMap<>();
    out.put("dryRun", r.dryRun());
    out.put("patterns", r.patterns());
    out.put("rootsProcessed", r.rootsProcessed());
    out.put("deletedByPathJobs", r.deletedByPathJobs());
    out.put("deletedById", r.deletedById());
    out.put("matchedFiles", r.matchedFiles());
    out.put("capped", r.capped());
    out.put("perPattern", perPattern);
    return out;
  }
}
