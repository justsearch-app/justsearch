/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Per-channel resolver mapping logger namespace prefixes onto {@link SubCategory} values.
 *
 * <p>Per slice 448 §0 D2: every emitted Logback event's logger name is matched against
 * {@link #prefixMappings()} (longest-prefix wins) to assign a SubCategory. The
 * {@link #overrides()} list provides per-logger explicit assignments that take precedence
 * over prefix matching — useful when a vendored library ships under a namespace whose
 * prefix would otherwise misclassify it.
 *
 * <p>The {@link #defaultSubCategory()} applies when no prefix or override matches; in
 * practice this is a safety net (the empirical scan in §0 found 100% of logger names
 * matched a prefix in the engine-log channel's default mapping).
 *
 * <p>Wire shape is a structured record so plugin authors registering their own
 * DiagnosticChannel can declare a selector inline without imperative configuration code.
 */
public record LoggerNamespaceSelector(
    Map<String, SubCategory> prefixMappings,
    Map<String, SubCategory> overrides,
    SubCategory defaultSubCategory)
    implements PreciseWire {

  public LoggerNamespaceSelector {
    Objects.requireNonNull(prefixMappings, "prefixMappings");
    Objects.requireNonNull(overrides, "overrides");
    Objects.requireNonNull(defaultSubCategory, "defaultSubCategory");
    prefixMappings = Map.copyOf(prefixMappings);
    overrides = Map.copyOf(overrides);
  }

  /**
   * Convenience factory: build a selector with prefix mappings and no overrides, defaulting
   * unmapped loggers to {@link SubCategory#LIBRARY_TRACE} (off-by-default).
   *
   * <p>Per slice 448 phase-1 review C1 (2026-05-07): the privacy-conservative default is
   * LIBRARY_TRACE. Empirical corpus coverage of CORE_DIAGNOSTIC was one workload — future
   * code paths under different Tika handlers, plugin-contributed channels with smaller
   * prefix sets, and unknown-unknowns can introduce unmapped loggers. Defaulting unmapped
   * loggers to LIBRARY_TRACE means they are off-by-default; default-subscribed CORE
   * visibility requires an explicit prefix mapping. Callers wanting permissive-default
   * behavior pass {@link SubCategory#CORE_DIAGNOSTIC} via the four-arg constructor.
   */
  public static LoggerNamespaceSelector of(Map<String, SubCategory> prefixMappings) {
    return new LoggerNamespaceSelector(prefixMappings, Map.of(), SubCategory.LIBRARY_TRACE);
  }

  /**
   * The default engine-log channel's selector, codifying the empirical scan results in
   * slice 448 §0 finding 5. Plugin DiagnosticChannels may declare a different mapping.
   *
   * <p>Per phase-1 review C1: defaultSubCategory is LIBRARY_TRACE (privacy-conservative).
   * The empirical scan found 100% prefix-match in one corpus; this default catches the
   * unknown-unknown case where a future logger emerges without a declared prefix.
   */
  public static LoggerNamespaceSelector defaultEngineLog() {
    return new LoggerNamespaceSelector(
        Map.ofEntries(
            Map.entry("io.justsearch.", SubCategory.CORE_DIAGNOSTIC),
            Map.entry("org.apache.lucene.", SubCategory.LIBRARY_TRACE),
            Map.entry("io.netty.", SubCategory.LIBRARY_TRACE),
            Map.entry("io.grpc.", SubCategory.LIBRARY_TRACE),
            Map.entry("org.apache.tika.", SubCategory.LIBRARY_TRACE),
            Map.entry("ai.onnxruntime.", SubCategory.LIBRARY_TRACE),
            Map.entry("ch.qos.logback.", SubCategory.BOOT_TRACE)),
        Map.of(),
        SubCategory.LIBRARY_TRACE);
  }

  /**
   * Returns the SubCategory that applies to {@code loggerName} per this selector. Override
   * map wins; otherwise longest matching prefix wins; otherwise {@link #defaultSubCategory()}.
   */
  public SubCategory resolve(String loggerName) {
    Objects.requireNonNull(loggerName, "loggerName");
    final SubCategory direct = overrides.get(loggerName);
    if (direct != null) {
      return direct;
    }
    SubCategory best = defaultSubCategory;
    int bestLen = -1;
    for (final Map.Entry<String, SubCategory> entry : prefixMappings.entrySet()) {
      if (loggerName.startsWith(entry.getKey()) && entry.getKey().length() > bestLen) {
        best = entry.getValue();
        bestLen = entry.getKey().length();
      }
    }
    return best;
  }

  /** Convenience: accumulate the declared sub-categories used by this selector. */
  public List<SubCategory> declaredSubCategories() {
    return prefixMappings.values().stream().distinct().sorted().toList();
  }
}
