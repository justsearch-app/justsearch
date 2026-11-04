# Public real-use corpus v1

This directory contains recipes, provenance, filenames and judgments. It contains
no document bytes. `real-use.json` pins each raw artifact and each resulting file
by SHA-256 and length. `real-use.manifest.tsv` maps realistic relative paths to
opaque document IDs. A changed upstream response fails verification; fetching
does not silently update the recipe.

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
rate limits. Raw downloads use `jseval.dataset_cache`. The quality lifecycle
retains its existing clean-engine, prepared-distribution and admission checks;
Gradle builds use `js-gradle-slot`.

| Source | Files | Selection and reuse |
| --- | ---: | --- |
| GovDocs1 | 1,000 | Numbered archives 000/001, format quotas and bounded file sizes; original member bytes. Source permits redistribution to its knowledge; third-party rights retained. |
| Enron | 500 | Existing `fetch_enron_raw_sample`, seed 20261102, 60-word floor; original maildir messages as `.eml`, subject-based names. CMU/FERC public-record distribution. |
| Bundestag | 450 | 405 Drucksachen in fixed-seed number runs and 45 Plenarprotokolle across Wahlperioden 18–20; public document server, no API key. Official works; attribution to Deutscher Bundestag/Bundesrat – DIP. |
| EUR-Lex | 430 | 200 acts in German and English, ten PDF counterparts and twenty consolidated versions. Published Cellar RDF metadata and Publications Office Cellar-backed download handler; EU reuse with attribution. |
| GovData | 200 | 40 CSV and 160 XLSX files, explicit distribution-level dl-de/by-2.0 or dl-de/zero-2.0 only, at most 512 kB. GovData catalogue metadata published through data.europa.eu. |
| Wikipedia | 180 | 125 English and 55 German HTML/Markdown notes from related energy/climate categories. Permitted article/category pages; paragraph-only Markdown derivation. CC BY-SA 4.0 with article/revision attribution. |
| SafeDocs | 200 | Published Tika language `deu`, page count 1–40 and provenance joined by `url_id`; `.de`, `.at`, `.ch` hosts, at most 2 MiB. Third-party rights retained. |

Total: **2,960 files**, 1,120 German and 1,840 English. The recipe records per-file
source, source identifier, origin URL, licence name/URL and attribution. Its
`selection` entries record methods and hashes of selection metadata. Selection
metadata does not need downloading again to reproduce the pinned files.

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
