# Search Syntax

The search bar takes plain words. There are no special operators to learn:
type what you are looking for.

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
- **Symbols are taken literally.** Quotes, `AND`/`OR`/`NOT`, wildcards, and
  `field:value` have no special meaning in the search bar; they are searched
  as ordinary text. Typing `"machine learning pipeline"` finds documents with
  those words, not only the exact phrase.

AI tools connected over MCP can ask for exact-phrase and boolean
(AND/OR/NOT) queries instead.

## How ranking works

Without AI models, results are ranked by keyword frequency and document
structure (BM25). With AI models installed, JustSearch uses hybrid retrieval:

- **BM25** for keyword matching
- **Semantic embeddings** for meaning-based similarity
- **Cross-encoder reranking** for final relevance scoring

The hybrid approach finds documents that match your intent, even when they
use different words than your query.
