/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.core.search;

import java.util.ArrayList;
import java.util.List;

/** The Search window's subset. Other query syntaxes never enter this classifier. */
public final class SearchSyntax {
  private SearchSyntax() {}

  /** A literal analyzed value; field is null for the combined content/title/author scope. */
  public record Clause(String field, String text, boolean phrase, boolean excluded) {}

  public record Parsed(boolean advanced, List<Clause> clauses) {
    public Parsed {
      clauses = List.copyOf(clauses);
    }
  }

  /** Safe, fixed reason text: never contains query text or parser internals. */
  public static final class InvalidQuery extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    InvalidQuery(String reason) {
      super(reason);
    }
  }

  public static Parsed parse(String query) {
    String input = query == null ? "" : query;
    var clauses = new ArrayList<Clause>();
    boolean advanced = false;
    boolean operator = false;
    int offset = 0;
    while (offset < input.length()) {
      if (Character.isWhitespace(input.charAt(offset))) {
        offset++;
        continue;
      }
      boolean excluded = input.charAt(offset) == '-' && offset + 1 < input.length()
          && (Character.isLetter(input.codePointAt(offset + 1)) || input.charAt(offset + 1) == '"');
      if (excluded) offset++;
      String field = null;
      if (!excluded) {
        for (String name : List.of("title", "author")) {
          if (input.startsWith(name + ":", offset)) {
            field = name;
            offset += name.length() + 1;
            if (offset == input.length() || Character.isWhitespace(input.charAt(offset))) {
              throw new InvalidQuery(name + ": needs a word or quoted phrase.");
            }
            break;
          }
        }
      }
      boolean phrase = input.charAt(offset) == '"';
      if (phrase) offset++;
      var value = new StringBuilder();
      boolean escaped = false;
      boolean closed = !phrase;
      while (offset < input.length()) {
        char c = input.charAt(offset);
        if (c == '\\' && offset + 1 < input.length()) {
          char next = input.charAt(offset + 1);
          if (next == '"' || next == '-' || next == '\\') {
            value.append(next);
            escaped = true;
            offset += 2;
            continue;
          }
        }
        if (c == '"') {
          if (phrase) {
            closed = true;
            offset++;
          }
          break;
        }
        if (!phrase && Character.isWhitespace(c)) break;
        value.append(c);
        offset++;
      }
      if (!closed) throw new InvalidQuery("Close the quoted phrase with a matching quote.");
      if (phrase && value.toString().isBlank()) {
        throw new InvalidQuery("Quoted phrases cannot be empty.");
      }
      String text = value.toString();
      advanced |= excluded || field != null || phrase;
      operator |= !excluded && field == null && !phrase && !escaped
          && (text.equals("OR") || text.equals("AND") || text.equals("NOT"));
      clauses.add(new Clause(field, text, phrase, excluded));
    }
    if (advanced && operator) {
      throw new InvalidQuery("OR, AND and NOT can't be combined with quotes, - or title:/author:");
    }
    if (advanced && clauses.stream().allMatch(Clause::excluded)) {
      throw new InvalidQuery("Add a word, phrase or field clause to search alongside exclusions.");
    }
    return new Parsed(advanced, clauses);
  }
}
