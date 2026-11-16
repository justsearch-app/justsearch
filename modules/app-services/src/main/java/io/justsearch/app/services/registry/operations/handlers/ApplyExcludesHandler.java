/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.operations.handlers;

import io.justsearch.core.context.EngineContext;

import io.justsearch.agent.api.registry.OperationHandler;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.ExcludesService;
import io.justsearch.app.services.indexing.ExcludeGlobs;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectReader;

/**
 * Handler for {@code core.apply-excludes}.
 *
 * <p>DESTRUCTIVE — walks every watched root and deletes already-indexed documents whose paths match
 * the configured exclude rules (Worker-delegated; Head never touches Lucene).
 *
 * <p>Library's in-place ask is the consent, and it is about one saved list: the caller sends that list
 * as {@code rules} (glob strings and exact-path entries, as saved). The removal runs only when the rules
 * in effect are exactly that list, and otherwise refuses with {@code EXCLUDES_CHANGED} before removing
 * anything. A call without {@code rules} keeps the earlier behaviour (the rules in effect are applied);
 * such a caller carries only the HIGH-risk consent capsule the trust gate requires of every call.
 */
public final class ApplyExcludesHandler implements OperationHandler {

  private static final Logger log = LoggerFactory.getLogger(ApplyExcludesHandler.class);
  private static final ObjectReader JSON = HandlerJson.MAPPER.reader()
      .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
      .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  private final Supplier<ExcludesService> excludesSupplier;

  public ApplyExcludesHandler(Supplier<ExcludesService> excludesSupplier) {
    this.excludesSupplier = Objects.requireNonNull(excludesSupplier, "excludesSupplier");
  }

  @Override
  public OperationResult execute(String argumentsJson, EngineContext engineContext) {
    List<Object> consented;
    try {
      consented = consentedRules(argumentsJson);
    } catch (RuntimeException invalid) {
      return OperationResult.failure("Invalid skip-rule removal request: " + invalid.getMessage(),
          "BAD_REQUEST", Map.of("field", "rules"), false);
    }
    return ExcludesHandlerSupport.run(excludesSupplier, false, consented, log, "ApplyExcludesHandler", engineContext);
  }

  /** The saved list the removal was consented for, or null for a caller that sends none. */
  private static List<Object> consentedRules(String arguments) {
    var root = JSON.readTree(arguments == null || arguments.isBlank() ? "{}" : arguments);
    if (root == null || !root.isObject()) throw new IllegalArgumentException("arguments must be an object");
    if (root.isEmpty()) return null;
    if (root.size() != 1 || !root.has("rules")) throw new IllegalArgumentException("rules is the only accepted field");
    var rules = root.get("rules");
    if (!rules.isArray()) throw new IllegalArgumentException("rules must be an array");
    if (rules.size() > 512) throw new IllegalArgumentException("rules holds more than 512 entries");
    List<Object> entries = new java.util.ArrayList<>(rules.size());
    for (var entry : rules) {
      if (entry.isString()) entries.add(entry.asString());
      else if (entry.isObject()) entries.add(HandlerJson.MAPPER.convertValue(entry, Map.class));
      else throw new IllegalArgumentException("rules entries must be strings or exact-path entries");
    }
    // Exact-path entries are decoded strictly, as the saved policy is.
    ExcludeGlobs.fromPatterns(entries);
    return entries;
  }
}
