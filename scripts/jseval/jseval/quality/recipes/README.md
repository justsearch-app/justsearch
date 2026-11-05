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
export JAVA_TOOL_OPTIONS=-Djava.awt.headless=true
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
recorded under `second_build`) and was not adopted. Both have 40 or more pages, so the page cap excludes them.
The decision also excludes any OCR'd PDF whose OCR took 15 s or more. Neither
build's engine log records per-document OCR time, so that rule excluded
nothing. Clusters (`clusters.json`) were computed on the 2,916-file population.
Eight clusters lose members. One of them, the Drucksachen run cl-0279, keeps
only one member. No known-item target's in-cluster status rests on a lost
member. One known-item query (ki-142) lost its target and was dropped. One topical
query keeps two of its three members.

The collection is a 1,000-file subset of those 2,890 (step `subset`, recorded
in `real-use-queries/subset.json` with the reason each file is kept). It keeps
760 files: every known-item target, its byte-identical copies, every member of
each cluster a target belongs to and every member of each topical query's
group. The other 240 are a fixed-seed sample per source, format and language
stratum. Each stratum first gets as many sampled files as it has targets (so
every source's targets keep other files to be told apart from), then the fill
moves strata toward their shares of the 2,890 files. OCR-routed PDFs are only
kept, never sampled; 88 are in the subset. EUR-Lex German PDFs have no files
beyond their five targets. The 2,890-file population stays defined by the
recipe and `excluded.json`, so the collection can grow back. `subset` refuses
to run while `subset.json` exists: the selection depends on the stored queries,
so selecting again after a query drop would silently change the corpus and its
index. Removing `subset.json` deliberately is the only way to reselect.

Its index is owned by document identity. Editing queries or judgments reuses
the prepared index, and so do docs, web-UI, CI and jseval scoring edits: the
index is keyed by the distribution snapshot, preparation harness, JDK, OCR
runtime, native folders, models and runtime settings by content (see
`scripts/jseval/README.md`), not by the source tree. Prepare it once in the
background, GPU allowed, with the runtime environment it is keyed with:

```sh
source ~/.cache/justsearch-quality/real-use-queries/gpu.env   # recorded with the index
python -m jseval quality prepare real-use     # or: python -m jseval.quality.real_use_queries prepare
python -m jseval quality baseline real-use
python -m jseval quality check real-use
```

`prepare` gives real-use a 2-hour background-preparation deadline
(`prepare_timeout_sec: 7200` in `config/real-use.json`); a fresh build spends
most of its time on embeddings and took 62 minutes under load. Every other
collection keeps the 1,800 s default. The deadline is a lifecycle setting, not
an index-key input, so no collection's key changes with it.

`gpu.env` sets the ONNX Runtime CUDA path (also prefixed to
`LD_LIBRARY_PATH`), `JUSTSEARCH_GPU_ENABLED=true`,
`JUSTSEARCH_LLM_ENABLED=false` and `JAVA_TOOL_OPTIONS=-Djava.awt.headless=true`
(an allow-listed value). The key covers the JUSTSEARCH_* values, the JVM
options and the content of the CUDA and `LD_LIBRARY_PATH` folders, so a
different value or native file builds a different index; the same files at
another path do not.

Queries come from `jseval.quality.real_use_queries` and cover S30. Its steps
are `extract`, `clusters`, `plan`, `generate`, `gate` and `write`; each is
resumable. Derived document text stays under
`~/.cache/justsearch-quality/real-use-queries/`. `real-use-queries/` records
the inputs and outputs without document text:

- prompt templates (`prompts/`);
- every generation attempt (`generation.jsonl`: model, template and prompt
  SHA-256, parameters, model output, gate results);
  the first 272 known-item attempts used a template with SHA-256
  `5e178a92e060b124864f06f24c84c675794edb8029c2248aa5474ab4caae4f7c` that was
  never committed. It was not preserved and cannot be recovered; its hash stays
  in those records. 49 stored queries come from it. `prompts/known-item.txt` is
  the later template, not that one;
- clusters with their evidence (`clusters.json`);
- the stratified plan (`plan.json`);
- the gate results per query and stratum, and every dropped query with its
  reason (`gates.json`);
- extraction sizing (`chunks.json`).

Known-item queries have one target each, judged grade 2. A byte-identical copy
would get grade 1, but the corpus has none. Topical queries come from clusters
or topic groups, never from a single document. They are stored with
`kind: topical` and no judgments. The config's `pending_kinds` excludes them
from scoring until a pooled labelling pass; `check` reports how many are
pending.

The plan holds 10% spare queries (`SPARE`) for drops. Four planned queries
never passed the verbatim-leak and word-limit gates (reason `gates`). Five
topical queries repeat an earlier query's text and are dropped (reason
`duplicate`). That leaves 187 known-item queries, all judged, and 122 topical
queries pending labels.

Triviality is measured, not gated. The `gate` step ranks every known-item
target with keyword search alone (the lexical leg, with the reranker and every
model stage off) on a core-started engine serving the prepared index, and records
each query's rank and the share at rank 1 per recall level in `gates.json`. No query is dropped or
rewritten to change that share. To measure again, run `python -m
jseval.quality.real_use_queries gate` inside the core's exclusive scoring window
after `quality prepare`. The gate starts only the prepared index's own engine;
it accepts neither `--url` nor `--root`.

Before starting that engine, the gate requires its environment to have
`JUSTSEARCH_QU_ENABLED` absent or `false` and `JUSTSEARCH_LLM_ENABLED=false`.
The core pins `JUSTSEARCH_HOME` to the index's isolated data directory
(`index.py:215–221`). The captured search config's `queryUnderstandingEnabled`
defaults to false (`ResolvedConfigBuilder.java:1442`); UI settings contribute
no override for this key (`ConfigStoreRebuilder.contributeUiSettings`).
`KnowledgeSearchEngine.java:896–899` passes that switch to extraction, and
`QueryUnderstandingService.java:172–173,223–227` returns before inference if
it is false or the AI service is unavailable. JVM properties outrank
environment variables (`ResolvedConfigBuilder.java:205–206`), and launchers and
the JVM split and unquote option text differently, so the gate does not parse
it: each of the five Java option variables (`JAVA_OPTS`, `UI_OPTS`,
`JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS`, `_JAVA_OPTIONS`) must be empty or
exactly an allowed value (today only `-Djava.awt.headless=true`). Anything else,
including an explicit `-Djustsearch.qu.enabled=false`, is refused before the
engine starts. The search request stays
unchanged. As a second check it refuses a nonempty
`queryUnderstanding.appliedBoosts`, the field in the response schema.
Rule-based answer-type classification is accepted. A date-only model boost
can be absent from that metadata (`KnowledgeSearchEngine.java:1486–1500`),
so the pre-start check is necessary: extraction cannot run at all.

Keyword search alone ranks the target first for:

| recall level | rank 1 | queries | share |
|---|---|---|---|
| exact | 60 | 65 | 92% |
| partial | 44 | 60 | 73% |
| vague | 28 | 62 | 45% |

On this 1,000-file collection, keyword search alone finds most exact and
partial targets first. Those queries can show that a search mode keeps or loses
what keyword search already finds, but they cannot show much improvement over
it. The measurement's power to tell search modes apart rests on the rest: the
vague queries, the targets keyword search does not rank first, and the topical
queries once they are labelled. Cite these shares wherever real-use scores are
cited.

Earlier versions of the gate did act on triviality. It regenerated rank-1
partial and vague queries (rounds `triviality-1` to `triviality-6` in
`generation.jsonl`) and then dropped 4 rank-1 partial queries by a seed. The
drops are undone. The regenerations stay: 55 of the 187 known-item queries are
the accepted output of such a round, and the record shows which ones.

### Topical labels

Topical queries ask for all files about a topic. `jseval.quality.topical_labels`
labels them; its steps are `extract`, `pool`, `excerpts`, `judge`, `project`,
`sample`, `second-judge`, `spot`, `accept` and `freeze`, each resumable.
Document text, excerpts, prompts, replies, model grades before the freeze and
the owner's sheet stay in `~/.cache/justsearch-quality/real-use-labels/`, never
in Git.

```sh
source ~/.cache/justsearch-quality/real-use-queries/gpu.env
python -m jseval quality prepare real-use                      # background, pausable
python -m jseval.quality.topical_labels extract                # text of the prepared index
python scripts/dev/admission.py exclusive --purpose "real-use pools" --timeout 600 -- \
  python -m jseval.quality.topical_labels pool                 # scoring window, like gate
python -m jseval.quality.topical_labels excerpts
python -m jseval.quality.topical_labels judge --max-calls 10 --phase calibration
python -m jseval.quality.topical_labels project
python -m jseval.quality.topical_labels sample
python -m jseval.quality.topical_labels second-judge --max-calls 2 --phase calibration
python -m jseval.quality.topical_labels second-judge --max-calls 1000
python -m jseval.quality.topical_labels spot     # 5 blocks for the owner, if the judges agree
python -m jseval.quality.topical_labels accept
python -m jseval.quality.topical_labels freeze
```

- **Pools.** Per topical query: the union of the top 10 of three pipelines on
  one prepared index, each response verified by its trace. Keyword search
  alone is exactly the gate's request. Dense retrieval alone has every other
  stage off. The Search window's refined request is the hybrid default, valid
  as `quality check` accepts it (by-design leg and reranker skips only). The
  pool adds the members of the topic group the query was written from (at
  most 10). Each pair records which pipeline retrieved it at which rank, and
  whether it is a group member. A pipeline that delivered fewer than 10 files
  is recorded `short`; one without a verified ranking after three attempts is
  recorded `failed` with its reason, never as an empty result. The pool's
  generation is the digest of its content.
- **Excerpts.** One per pair, from the pooled index's extracted text: the
  file name, the first ~600 characters and the two non-overlapping
  ~600-character windows sharing the most query terms (whole tokens of the
  existing tokenizer; this codebase has no German compound-splitting rule), at
  most ~1,800 characters of text. Judge and user see exactly this text, marked
  as data. Limit: labels are agreement on the excerpt; relevance outside it
  can be missed.
- **Judge.** `claude-opus-5-5` through `claude -p` on the subscription only,
  with the generator's refusal of API keys and provider overrides. Up to five
  files per call in seeded random order, each graded independently 0-3
  (`prompts/topical-judge.txt`). Each judgment is checkpointed per pair and
  bound to the query text, document content, extracted text, excerpt, pool
  generation, model and prompt template; a resumed run reuses it only when all
  of these are equal. A kill during a write loses only the torn last pair.
  `judge` stops with its reason: `paused` (create `PAUSE` in the work folder,
  or send SIGTERM), `call limit reached`, `time budget reached`, `quota`,
  `timeout` or `stalled` (three consecutive failed calls). Judging the whole
  pool starts only after its projected calls and wall time (`project`) are
  confirmed.
- **Labels.** Binary at the fixed cutoff grade >= 2 (relevant 1, else 0),
  judged zeros kept; raw grades and pool provenance go to
  `real-use-queries/topical-labels.json` at the freeze.
- **Agreement sample**, fixed before any grade was seen: 150 pairs from the
  complete graded pool (seed 20261105), at most 2 per query, 75 the primary
  judge calls relevant and 75 not, each half split by query language in
  proportion, at least 30 graded 1 or 2. Pair ids and order carry no stratum.
  No owner grades these 150 pairs: they are the second judge's set.
- **Second judge.** `gpt-6.1-sol`, another model family, through `codex exec`
  on the ChatGPT subscription only. Any `OPENAI_*` variable (API key, base URL
  and other endpoint overrides), the CLI's own `CODEX_API_KEY`,
  `CODEX_ACCESS_TOKEN`, `CODEX_HOME`, login-issuer and token-refresh overrides,
  or a Bedrock token refuses before any call, even when empty, and
  `codex login status` must report `Logged in using ChatGPT`. The call
  (reviewed against codex-cli 0.166.0, `codex exec --help`):
  `codex exec --ignore-user-config --ignore-rules --ephemeral
  --skip-git-repo-check --sandbox read-only --model gpt-6.1-sol --json
  --output-schema <grades schema> -c model_provider="openai"
  -c approval_policy="never" -c web_search="disabled" -c project_doc_max_bytes=0
  --disable <each tool feature> -`, with the prompt on stdin, in an empty
  temporary folder. A reply whose event stream shows any tool item, or an
  error event, is refused. It grades exactly the sample's 150 pairs with the
  same rubric, excerpts and batching (one query per call, up to five files in
  seeded random order). Its records (`second/` in the work folder) are bound
  like the primary's, stamped with revision and dispatch time, and keep only a
  digest of each reply; it pauses and stops with the same reasons
  (`quota: ChatGPT usage limit reached`).
- **Decision**, fixed before any second grade: the judges agree when both give
  the same binary label (grade >= 2). Below 80% of the 150 pairs, unrounded
  (119 of 150), FAIL, and the owner is not asked. At or above it, the owner's
  spot check: 5 pairs drawn by a recorded seed (`20261105:<revision>:spot`)
  from the pairs where the judges disagree, at least 2 the primary calls
  relevant and the second not, 2 the other way and 2 from German queries,
  each as far as such pairs exist; with fewer than 5 disagreements, all of
  them plus primary grade-1/2 boundary pairs. The sheet is plain text, one
  block per pair, no grades: the question "Would you want this file among the
  results for this search?" and `Answer:` yes, no or unsure; the parser
  refuses missing, duplicate or altered blocks, and every block must be
  answered. An owner who sides against the primary judge (yes/no unlike its
  label) on 4 or 5 of the 5 makes it FAIL; otherwise the spot result is
  recorded as information and the decision is PASS. A FAIL allows one rubric
  or prompt revision, a full re-label, a new sample of new pairs and the second
  judge again; a second FAIL stops. The decision record adds the judges'
  confusion matrix and disagreements by language and grade (information only).
- **Freeze**, only after a PASS on exactly the current grades of both judges:
  binary qrels for every topical pair through the collection writer, counts and
  grade histogram in the config, `topical` removed from `pending_kinds`. The
  record's provenance names the primary and second judge, their measured
  agreement on the 150 pairs and the owner's spot-check result. Known-item
  labels and document identity are unchanged, so the prepared index is
  reused. A query whose pool holds no relevant file is excluded from scoring
  with the recorded reason `no relevant document in pool` (config `excluded`),
  never given an invented positive. Rerunning the query writer keeps the
  topical labels of every query whose text is unchanged.

Once no query kind awaits labels, real-use joins the default `quality check`
and `full-suite` collections. Later comparisons keep the labels frozen: in a
topical group a comparison is INCONCLUSIVE when either arm's top-10 unjudged
share exceeds the pooled tolerance (`unjudged.max_differing_share`, counted
per arm with shared unknowns), and the unjudged pairs are listed for judgment.
