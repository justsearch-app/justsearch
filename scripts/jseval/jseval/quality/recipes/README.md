# Public real-use corpus v1

This directory contains recipes, provenance, filenames and judgments. It contains
no document bytes. `real-use.json` pins each resulting file by SHA-256 and length.
All sources except Wikipedia also require exact raw artifact checksums and lengths.
Wikipedia fetches each recorded revision through `/wiki/TITLE?oldid=REV`; its raw
HTML checksum and length describe the acquisition response as information only.
Skin/rendering changes are accepted only when the derived file stays identical.
`real-use.manifest.tsv` maps realistic relative paths to opaque document IDs.
Changed document bytes fail verification; fetching never updates the recipe.

From an installed `scripts/jseval` environment:

```sh
export JUSTSEARCH_DATASET_CACHE="$HOME/.cache/justsearch-quality/dataset-cache"
export JUSTSEARCH_GPU_ENABLED=false
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Djava.awt.headless=true"
python -m jseval quality corpus fetch real-use
python -m jseval quality corpus materialize real-use
python -m jseval quality known-answers real-use-slice
python -m jseval quality prepare real-use-slice
python -m jseval quality baseline real-use-slice
python -m jseval quality check real-use-slice
```

Materialization uses `~/.cache/justsearch-quality/sources/real-use-v1/`.
`materialize --out` can create a second copy elsewhere under the quality cache;
it verifies every file even when the destination already exists. The fetcher is
sequential, observes robots rules (including wildcard rules, crawl delays and
request rates), checks redirects before requesting their targets, and stops on
rate limits. Raw downloads use `jseval.dataset_cache`. Wikipedia derivations are
verified before cache publication and again on cache reads; their raw downloads
have a fixed 16 MiB cap. HTML derivation refuses nesting beyond 256 elements;
unmatched closing tags take constant work and matched closes pop each node once.
Both Wikipedia derivations refuse an empty article body. Every other source
retains its recorded raw size cap.
The quality lifecycle
retains its existing clean-engine, prepared-distribution and admission checks;
Gradle builds use `js-gradle-slot`.

Every request, including robots.txt, uses at most five manually checked redirects.
Redirects stay on the original host unless the recipe's `redirect_hosts` table
permits a destination. The recipe permits Digital Corpora's S3 destination;
undeclared host changes are refused. Robots reads have a 512 KiB cap and a
60-second timeout; failed policy checks disallow the fetch. Tar processing has a
fixed 2 GiB expanded-byte cap, counting skipped files and metadata, and stops
after the selected members. The pinned Enron tar expands to 1,823,016,960 bytes.

| Source | Files | Selection and reuse |
| --- | ---: | --- |
| GovDocs1 | 1,000 | Numbered archives 000/001, format quotas and bounded file sizes; original member bytes. Source permits redistribution to its knowledge; third-party rights retained. |
| Enron | 500 | Existing `fetch_enron_raw_sample`, seed 20261102, 60-word floor; original maildir messages as `.eml`, subject-based names. CMU/FERC public-record distribution. |
| Bundestag | 450 | 405 Drucksachen in fixed-seed number runs and 45 Plenarprotokolle across Wahlperioden 18–20; public document server, no API key. Official works; attribution to Deutscher Bundestag/Bundesrat – DIP. |
| EUR-Lex | 430 | 200 acts in German and English, ten PDF counterparts and twenty consolidated versions. Published Cellar RDF metadata and Publications Office Cellar-backed download handler; EU reuse with attribution. |
| GovData | 200 | 40 CSV and 160 XLSX files, explicit distribution-level dl-de/by-2.0 or dl-de/zero-2.0 only, at most 512 kB. GovData catalogue metadata published through data.europa.eu. |
| Wikipedia | 180 | 125 English and 55 German notes from related energy/climate categories, fetched by recorded revision. 91 paragraph-only Markdown notes and 89 minimal saved article HTML pages. CC BY-SA 4.0 with article/revision attribution. |
| SafeDocs | 200 | Published Tika language `deu`, page count 1–40 and provenance joined by `url_id`; `.de`, `.at`, `.ch` hosts, at most 2 MiB. Third-party rights retained. |

Total: **2,960 files**, 1,120 German and 1,840 English. The recipe records per-file
source, source identifier, origin URL, licence name/URL and attribution. Its
`licenses` table holds shared terms; each document's `license.ref` selects them,
with per-document fields overriding the shared values. Individual article,
revision and dataset attributions remain on their document rows. Its
`selection` entries record methods and hashes of selection metadata. Selection
metadata does not need downloading again to reproduce the pinned files.

Wikipedia HTML pages retain the article title, headings, paragraphs, lists and
tables from `mw-parser-output`. Source attributes and links are removed; skin,
navigation, scripts, styles, edit links and banners are excluded. Only Wikipedia
may use either derivation. The revision-pinning repair re-pinned all 89 HTML files
and three Markdown notes (Energieübertragung, Visit the main page [z], Wind power)
from the same recorded revisions. Every changed document, revision, prior digest
and replacement digest is listed in [real-use.wikipedia-repins.tsv](real-use.wikipedia-repins.tsv).
No slice member changed, so its content digests, queries and judgments are unchanged.

The article-root repair additionally re-pinned Bajgora Wind Farm, Güssing,
Novar Wind Farm, Ravina Project Toronto, Tehri Dam and Wildpoldsried from their
same recorded revisions. Their coordinate indicators also carry
`mw-parser-output`; derivation now prefers the language-marked article root.
The six additional rows in the repinning table record the title-only digests
and the corrected article digests. No articles were replaced and none belong
to the slice.

The Wikipedia share is 70 below target (28%, within the allowed adjustment): the
chosen category candidates were exhausted or rate limited. EUR-Lex contributes
30 additional physical/temporal counterparts. Parliamentary metadata-linked
families were replaced by small consecutive-number runs as agreed. Sources that
provide no accompanying page counts are recorded as such; their originals are
not parsed to discover counts. GovDocs1 and SafeDocs are only archive-routed,
hashed and copied outside the engine extraction sandbox. Generic filenames are
used where the published source gives no title. One mail reservation number that
coincided with a GovDocs1 identifier is omitted from its saved subject name;
eight blank-subject messages use neutral “Untitled message” filenames. Two
case-only “RE”/“Re” mail pairs use copy suffixes so the recipe also materializes
on case-insensitive filesystems. The core identity tests separately exercise
case-only paths, duplicate basenames, Unicode, URL-like characters and chunks.

`real-use-slice/` is the core's stored collection format: 50 files from all seven
sources, 26 English and 24 German, with 50 positive-only file-name queries and
grade-2 judgments. Queries use 2–4 words, shortened/paraphrased names, spelling
errors or changed word order. Full-corpus queries and judgments are deferred.
The slice uses three small CSV tables: real-engine measurements of its initial
CSV candidates produced 3.16 million extracted characters. Those larger tables
remain in the full corpus; the initial slice keeps CPU preparation bounded.
The config selects `layout: "as-is"`; original extensions and manifest paths
reach the engine verbatim. Only the six Markdown notes are eligible for source
passage probes. All files remain covered by source-byte and extracted-text
digests. Existing collections retain their default opaque `.txt` layout.
An unset `witness_formats` preserves historical witness selection across every
document; an explicit list filters by the original file extension.

## Collection `real-use`

`real-use/` is the full-corpus collection (config `real-use`, layout `as-is`,
witness format `md`, groups `lang` × `kind`). It holds 2,890 of the recipe's
2,960 files. The recipe still pins all of them. The 70 excluded files are listed
with evidence in `real-use-queries/excluded.json`:

- 4 GovDocs1 Office files whose sandbox parser fails.
- 40 PDFs (14 Bundestag, 23 GovDocs1, 3 SafeDocs) whose extraction the first
  build truncated at the policy cap.
- 26 OCR'd PDFs of 40 or more pages (`ocr_page_cap`). This is the design's PDF
  page cap; the sources without page counts had slipped past it. The page
  counts and OCR routes come from the second build's visual-extraction
  evidence.

The core witness refuses parser failures and truncated extractions. OCR
truncation depends on load, because the PDF OCR has a 30-second per-document
budget. The second build truncated two more large PDFs (109 and 64 pages,
recorded under `second_build`) and was not adopted. Both are under the page cap.
The decision also excludes any OCR'd PDF whose OCR took 15 s or more. Neither
build's engine log records per-document OCR time, so that rule excluded
nothing. Clusters (`clusters.json`) were computed on the 2,916-file population.
Eight clusters lose members. One of them, the Drucksachen run cl-0279, keeps
only one member. No known-item target's in-cluster status rests on a lost
member. One known-item query (ki-142) lost its target and was dropped. One topical
query keeps two of its three members.

Its index is keyed by document identity. Editing queries or judgments reuses
the prepared index. Prepare it once in the background, GPU allowed, with the
runtime environment it is keyed with:

```sh
source ~/.cache/justsearch-quality/real-use-queries/gpu.env   # recorded with the index
python -m jseval quality prepare real-use     # or: python -m jseval.quality.real_use_queries prepare
python -m jseval quality baseline real-use
python -m jseval quality check real-use
```

`gpu.env` sets the ONNX Runtime CUDA path (also prefixed to
`LD_LIBRARY_PATH`), `JUSTSEARCH_GPU_ENABLED=true`,
`JUSTSEARCH_LLM_ENABLED=false` and `JAVA_TOOL_OPTIONS=-Djava.awt.headless=true`.
The index key covers the JUSTSEARCH_* variables and JAVA_TOOL_OPTIONS, so a
different value builds a different index.

Queries come from `jseval.quality.real_use_queries` and cover S30. Its steps
are `extract`, `clusters`, `plan`, `generate`, `gate` and `write`; each is
resumable. Derived document text stays under
`~/.cache/justsearch-quality/real-use-queries/`. `real-use-queries/` records
the inputs and outputs without document text:

- prompt templates (`prompts/`);
- every generation attempt (`generation.jsonl`: model, template and prompt
  SHA-256, parameters, model output, gate results);
- clusters with their evidence (`clusters.json`);
- the stratified plan (`plan.json`);
- the gate results per query and stratum (`gates.json`);
- extraction sizing (`chunks.json`).

Known-item queries have one target each, judged grade 2. A byte-identical copy
would get grade 1, but the corpus has none. Topical queries come from clusters
or topic groups, never from a single document. They are stored with
`kind: topical` and no judgments. The config's `pending_kinds` excludes them
from scoring until a pooled labelling pass; `check` reports how many are
pending.
