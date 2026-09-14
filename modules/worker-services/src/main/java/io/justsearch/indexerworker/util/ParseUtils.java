/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.util;

/**
 * Utility methods for parsing and string manipulation.
 *
 * <p>Extracted from WorkerSearchService for reusability and testability.
 */
public final class ParseUtils {

  private ParseUtils() {
    // Utility class - no instantiation
  }

  /**
   * Parses a string to int with a default value on failure.
   *
   * @param value the string to parse (may be null)
   * @param defaultValue the default value if parsing fails
   * @return parsed int, or defaultValue on failure
   */
  public static int parseIntSafe(String value, int defaultValue) {
    if (value == null || value.isBlank()) return defaultValue;
    try {
      return Integer.parseInt(value.trim());
    } catch (NumberFormatException e) {
      return defaultValue;
    }
  }

  /**
   * Extracts the filename from a file path.
   *
   * @param path the file path (may be null)
   * @return the filename, or "unknown" if path is null
   */
  public static String extractFilename(String path) {
    if (path == null) return "unknown";
    int lastSlash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
    return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
  }

  /**
   * Trims content to a maximum length.
   *
   * @param content the content to trim (may be null)
   * @param maxLength the maximum length
   * @return trimmed content, or empty string if null
   */
  public static String trimToLength(String content, int maxLength) {
    if (content == null) return "";
    if (content.length() <= maxLength) return content;
    return content.substring(0, maxLength);
  }
}
