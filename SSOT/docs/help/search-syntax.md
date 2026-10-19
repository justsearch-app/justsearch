# Search Syntax

The search bar takes plain words. You do not need any operators: type what
you are looking for.

## Basic search

Type any words to search across all indexed documents. Each word is matched
against the document text, title, and author:

```text
quarterly report 2025
```

## Matching words

- **Any word can match.** Words are combined with OR, so a document does not
  need to contain every word to appear. Documents that match more of your
  words, and match them more strongly, rank higher.
- **The last word can be partial.** The final word of your query also matches
  longer words that start with it, so `justsearc` finds "justsearch".
- **Uppercase `AND` and `NOT` are read as operators, but they are not a strict
  filter.** `budget AND forecast` favours documents that contain both words,
  and `NOT` before a word works against documents that contain it. Because
  the last word also matches on its own as a partial word, `budget AND
  forecast` can still show a document that contains only "forecast", and
  `budget NOT forecast` can still show documents that contain "forecast".
  `OR` is what the search bar does anyway. Typed in lowercase, these are
  ordinary words.
- **Other symbols are taken literally.** Quotes, wildcards, and `field:value`
  have no special meaning in the search bar; they are searched as ordinary
  text. Typing `"machine learning pipeline"` finds documents with those words,
  not only the exact phrase.

AI tools connected over MCP can also ask for exact-phrase queries.

## How ranking works

Without AI models, results are ranked by keyword frequency and document
structure (BM25). With AI models installed, JustSearch uses hybrid retrieval:

- **BM25** for keyword matching
- **Semantic embeddings** for meaning-based similarity
- **Cross-encoder reranking** for final relevance scoring

The hybrid approach finds documents that match your intent, even when they
use different words than your query.
