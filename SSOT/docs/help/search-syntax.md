# Search Syntax

The Search bar accepts plain words across document text, title and author. You do not need operators.

## Plain search

Type words such as `quarterly report 2025`. Any word can match. Documents matching more words, or matching them more strongly, rank higher. The last word can be partial: `justsearc` finds "justsearch".

Plain uppercase `OR`, `AND` and `NOT` retain their existing behavior. `AND` favors documents containing both words and `NOT` works against a word, but last-word prefix matching can still admit documents containing only the last word. They are not strict filters. Lowercase forms are ordinary words.

## Advanced search in Search mode

- `"budget review"` requires that exact phrase within one field: text, title or author. Words split across fields do not form a phrase.
- `foo -bar` matches foo but excludes any document containing bar in text, title or author. `foo -"red pen"` excludes the phrase rather than separate occurrences of red and pen.
- `title:budget` requires budget in the title. `author:"Ada Lovelace"` requires that phrase in the author. These two field names are lowercase.
- `author:` reads the author recorded in the file: Word, OpenDocument and PDF documents, and the sender of an email. A Markdown front-matter author is not searched by `author:`. Files indexed by an earlier version gain their author the next time they change.
- Required phrases and field clauses all have to match; they may match different fields. In `foo "budget review"`, the phrase is required and foo helps ranking. With no required clause, at least one unqualified word must match. Exclusions always apply across all three fields.

Quotes deliberately choose strict retrieval. A pasted quoted title becomes a phrase search. Existing saved URLs and restored Search drafts keep their original text and recovery guards; quotes, exclusions and field clauses in them now have this advanced meaning.

A minus starts an exclusion only at the beginning of a term followed by a letter or quote. `e-mail`, `-5 degrees` and a lone trailing `-` stay literal. A backslash makes the next `"`, `-` or `\` literal: `\"`, `\-`, `\\`. Other sequences remain ordinary analyzed text, even within an advanced query: `path:x`, `foo*`, `(draft)`, `+foo`, `[a TO b]`, `10:30`, `C:\docs`. Wildcards, ranges and other field names have no special meaning.

Advanced searches use lexical matching on both quick and refined passes: no semantic candidates, chunk merging, AI expansion or typo correction. Selected restrictions apply exactly as sent. Results, counts and facets all describe documents satisfying the query; previous counts and facets stay pending until the new query is answered.

## Fixing syntax

A missing closing quote, an empty phrase, `title:` or `author:` without a value, or a query containing only exclusions is invalid. Standalone uppercase `OR`, `AND` and `NOT` cannot be combined with advanced syntax: "OR, AND and NOT can't be combined with quotes, - or title:/author:". Those strings inside phrases or field values are literal, so `"Research AND Development"` and `author:"NOT"` work.

While typing, an invalid query stops the automatic search quietly. Press Search or Enter to see the Engine's reason under the bar. Your text stays in place; correcting it resumes searching.

## Other modes and ranking

Ask, Structured, Delegate and model-only accept operator characters as ordinary prompt text (with the usual whitespace trimming). They make no advanced-search syntax guarantee.

Plain searches retain their existing ranking: BM25 keyword matching and document structure, with semantic embeddings and cross-encoder reranking when available. Advanced queries use strict lexical retrieval.
