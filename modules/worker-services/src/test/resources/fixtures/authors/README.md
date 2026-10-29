Owned author metadata fixtures for IndexingAuthorExtractionTest; each body contains
“An ordinary invoice for the annual meeting.” and omits the author's name.

- DOCX: independent WP2.4 verifier's minimal authored.docx, dc:creator Ada Lovelace.
- ODT: minimal OpenDocument package, dc:creator Grace Hopper.
- PDF: one text page, document-info Author Katherine Johnson.
- EML: plain MIME message, From Dorothy Vaughan <dorothy@example.invalid>.
- MSG: POI POIFS package with Unicode MAPI subject, sender name/address and body
  properties, sender Annie Easley <annie@example.invalid>.
- Markdown: YAML front matter author Mary Jackson, the existing meta_author facet.

No external documents or personal data. Fixtures are deterministic local test data.
