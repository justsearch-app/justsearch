/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import io.justsearch.configuration.excludes.ExclusionPatterns;
import java.util.List;

/** Head scan-plan projection of the shared root-relative exclusion matcher. */
final class ExcludeMatcher {
  private final ExclusionPatterns patterns;

  private ExcludeMatcher(ExclusionPatterns patterns) { this.patterns = patterns; }
  static ExcludeMatcher empty() { return new ExcludeMatcher(ExclusionPatterns.empty()); }
  List<String> patterns() { return patterns.patterns(); }
  boolean isEmpty() { return patterns.isEmpty(); }

  static ExcludeMatcher fromRawJson(String rawJson, boolean windows) {
    try {
      return fromRawJsonStrict(rawJson, windows);
    } catch (RuntimeException invalid) {
      return empty();
    }
  }

  static ExcludeMatcher fromRawJsonStrict(String rawJson, boolean windows) {
    return new ExcludeMatcher(ExclusionPatterns.fromRawJsonStrict(rawJson, windows));
  }

  static ExcludeMatcher fromPatterns(List<String> globs, boolean windows) {
    return new ExcludeMatcher(ExclusionPatterns.fromPatterns(globs, windows));
  }

  static List<String> expandBarePattern(String pattern) { return ExclusionPatterns.expandBarePattern(pattern); }
}
