"""Procedural fabricated-corpus generator (tempdoc 635).

Generates the committed *source* (`docs.jsonl` + `queries.json` + `meta.json`) for a
clean, high-fidelity self-demo corpus, deterministically, with the de-risk-proven
difficulty levers as explicit parameters:

- **genuine multi-hop by construction** — questions are emitted FROM fabricated
  entity-relation *chains*, so the answer exists only by combining ≥2 docs (no
  single-doc shortcut; the §D.5 fidelity gate's shortcut sub-gate certifies this).
- **hard-negative distractors** — parallel fabricated chains (same vocabulary/shape,
  different entities, no answers), at a tunable ratio (de-risk U-B: ~5–10:1 to reach
  the realistic nDCG band).
- **long docs** — padded to > the 512-token chunk size so dense engages (de-risk U-C)
  and file-reads are expensive (the agent token-efficiency lever).
- **contamination-free** — all entities/facts are fabricated (the closed-book gate
  certifies the model can't answer from memory).

Per-axis renderers (R-3): `prose`, `code`, `tabular`, and a `lang` knob for the
multilingual member. Fully procedural + seeded → reproducible (no LLM needed; the LLM
patterns in `utility_judge` remain available for optional prose enrichment, unused here
to keep generation deterministic and gate-certifiable).
"""

from __future__ import annotations

import base64
import hashlib
import io
import json
import math
import random
import subprocess
import sys
from pathlib import Path

# The BUILD-PATH half of the entity bank only. `entity_harvest` (the offline harvester)
# is deliberately NOT imported here: a build that could re-harvest could silently produce
# different output from a different corpus snapshot, which would put the harvester's
# regexes and the host-corpus bytes inside this module's cross-interpreter determinism
# proof (`regenerate_and_diff`). Harvest once, pin the sha, sample forever.
from jseval import corpus_comparators as _comparators
from jseval import entity_bank as _entity_bank

# Padding boilerplate. REMAINING OWNER: the 635 goldens (`635-corpora/synth-*`), which are
# STANDALONE corpora with no host document — without padding they would be one-sentence
# documents, so they keep `doc_words=<int>` and this filler (tempdoc 767 §I.2 scope
# boundary). The 707 injected corpora pass `doc_words=None` and get NO padding: their
# fabricated sentences are interleaved into a REAL host document by `corpus_inject.assemble`,
# so the host already supplies register-native bulk and this text would be pure additive
# boilerplate — byte-identical across every gold doc, i.e. one grep selects them all
# (tempdoc 767 §I.1 "bulk" layer, measured: 280/280 gold docs enumerable by this string).
_FILLER = ("The surrounding district is known for long winters and quiet markets where "
           "traders gather to exchange goods and stories. Over the years many travellers "
           "passed through, leaving small monuments and the occasional inscription. Scholars "
           "studied the region's history at length, noting the slow rise of its institutions "
           "and the patient work of its builders. Records from the period are sparse but "
           "consistent, describing a community that valued careful measurement and steady craft. ")


# Semantic descriptors (review Issue A+B fix): the head doc describes the entity with
# one surface phrasing; the QUERY references it via SYNONYMS (low lexical overlap), so
# exact-match `Grep` / pure-BM25 fails at the entry point but dense/SPLADE bridges
# semantically — the only setup where JustSearch's retrieval can beat a grep-agent.
# INVARIANT (tempdoc 767 §I.3, guarded by `test_sem_pools_are_root_disjoint`): the two members
# of every pair must share NO token. A pair like ("Carpathian highlands", "Carpathian uplands")
# varies the head noun but leaves a distinctive modifier verbatim, so the query and its gold doc
# share a corpus-wide df=1 token — a perfect grep anchor pinning exactly one document, which
# defeats the whole point of the synonym bridge. Replacements stay domain-neutral and carry no
# proper noun the host corpus does not already use. Pool LENGTHS are load-bearing (see
# `_max_semantic_chains`), so fixes are in-place substitutions, never additions or removals.
# (doc_noun, query_noun) type synonyms + (doc_place, query_place) place synonyms. The
# head's descriptor combines a type + a UNIQUE place per gold chain, so the query (synonyms
# of both) identifies exactly one head SEMANTICALLY — but shares no surface tokens with the
# doc, so grep/pure-BM25 fail at the entry. Distractors reuse the same vocabulary (other
# type+place combos) → hard negatives.
_SEM_TYPE = [
    ("reactor", "power station"), ("observatory", "stargazing facility"),
    ("watermill", "grain-grinding works"), ("archive", "records vault"),
    ("foundry", "metal-casting works"), ("telescope", "optical instrument"),
    ("tramway", "streetcar line"), ("aqueduct", "water channel"),
    ("printing house", "publishing works"), ("lighthouse", "coastal beacon"),
    ("vineyard", "wine estate"), ("bathhouse", "thermal spa"),
    # -- tempdoc 624 scale-corpus: appended 12->21 to widen the triple space (append-only,
    # existing indices/entries above are untouched so committed corpora regenerate identically).
    ("granary", "grain depot"), ("shipyard", "vessel works"),
    ("brewery", "ale house"), ("tannery", "hide-processing works"),
    ("mint", "coin-striking works"), ("smithy", "blacksmith works"),
    # tempdoc 767: the query side of index 19 was "small place of worship" — the only 4-word
    # type synonym in the pool. An EN short-natural query is `6 + |type_syn| + |place_syn|`
    # words (`corpus_query_strata._short_natural`) against a 12-word cap, and the longest
    # place synonyms are 4 words, so a 4-word type synonym is the single reason the EN cap
    # broke (20 of the 924 two-axis diagonal combinations, ALL of them at this index —
    # measured). A 2-word in-place substitution takes that to 0/924. In-place: pool LENGTHS
    # are load-bearing (`_max_semantic_chains`), so this is a substitution, not a removal.
    ("greenhouse", "glasshouse nursery"), ("chapel", "prayer hall"),
    ("windmill", "grain-milling tower"),
]
_SEM_PLACE = [
    ("northern marshlands", "upper wetlands"), ("eastern ridge", "crest to the east"),
    ("river bend", "curve of the watercourse"), ("old courthouse", "former justice building"),
    ("western district", "quarter to the west"), ("highland plateau", "elevated tableland"),
    ("hill city", "town on the slopes"), ("southern hills", "knolls to the south"),
    ("market square", "central marketplace"), ("rocky headland", "stony promontory"),
    ("sunny valley", "sunlit dale"), ("coastal cliffs", "shoreline bluffs"),
    ("pine forest", "evergreen woodland"), ("salt flats", "saline plains"),
    ("granite quarry", "stone pit"), ("harbour mouth", "port entrance"),
    ("desert basin", "arid hollow"), ("frozen lake", "iced-over tarn"),
    ("walled garden", "enclosed grounds"), ("clock tower", "belfry spire"),
    ("copper mine", "ore working"), ("windswept moor", "blustery heath"),
    ("river delta", "estuary fan"), ("mountain pass", "high col"),
    ("fishing wharf", "angling quay"), ("orchard slope", "fruit-grove hillside"),
    # -- tempdoc 624 scale-corpus: appended 26->44 to widen the triple space (append-only).
    ("limestone caverns", "chalky underground hollows"), ("iron bridge", "metal river crossing"),
    ("floodplain village", "lowland riverside settlement"), ("terraced hillside plots", "layered slope embankments"),
    ("abandoned rail yard", "disused switching grounds"), ("chalk downs", "white escarpment hills"),
    ("tidal flats", "mudflat shallows"), ("border checkpoint", "frontier crossing post"),
    ("derelict airstrip", "disused landing ground"), ("underground cistern", "buried water reservoir"),
    ("shipwreck cove", "sunken-vessel inlet"), ("thermal spring basin", "hot-water hollow"),
    ("sandstone crags", "reddish rock escarpment"), ("ferry landing", "boat crossing point"),
    ("collapsed mineshaft", "caved-in excavation shaft"), ("sunlit overlook", "sun-facing viewing ledge"),
    ("brackish lagoon", "salt-tinged inlet"), ("derelict watchtower", "abandoned lookout post"),
]

# Third combinatorial descriptor axis (tempdoc 624 T.1): a numbered/ordinal qualifier,
# synonym-paired like type/place — the doc surface uses the cardinal ("unit seven"), the
# query synonym uses the ordinal ("the seventh installation"), zero token overlap per pair.
# It widens the achievable non-colliding descriptor space (12 types x 26 places = 312 combos
# was measured to produce 43-94% distractor-descriptor duplication at realistic corpus
# scale/distractor_ratio). Tempdoc 624 scale-corpus: it ALSO now carries gold-chain uniqueness
# jointly with type+place — the query references the full (type, place, qualifier) synonym
# triple, so the gold-chain ceiling is the triple-injectivity period lcm(T,P,Q) (see
# `_max_semantic_chains`), NOT the place-pool size the original single-axis cap used.
# Tempdoc 624 scale-corpus (pool-growth follow-up): the 12x26x20=6240-triple space was in turn
# too crowded at ~3000 docs (distractors sharing 2-of-3 descriptors with a gold query buried it
# below the retrieval-fidelity floor). Pools grown append-only to 21 types x 44 places x 25 quals
# = 23100 triples; math.lcm(21,44,25) == 23100 exactly since the three sizes are pairwise coprime
# (21=3*7, 44=2^2*11, 25=5^2), so the CRT injectivity argument in `_max_semantic_chains` still
# gives an exact (not just a lower-bound) gold-chain ceiling.
_SEM_QUAL = [
    ("unit one", "the first installation"), ("unit two", "the second installation"),
    ("unit three", "the third installation"), ("unit four", "the fourth installation"),
    ("unit five", "the fifth installation"), ("unit six", "the sixth installation"),
    ("unit seven", "the seventh installation"), ("unit eight", "the eighth installation"),
    ("unit nine", "the ninth installation"), ("unit ten", "the tenth installation"),
    ("unit eleven", "the eleventh installation"), ("unit twelve", "the twelfth installation"),
    ("unit thirteen", "the thirteenth installation"), ("unit fourteen", "the fourteenth installation"),
    ("unit fifteen", "the fifteenth installation"), ("unit sixteen", "the sixteenth installation"),
    ("unit seventeen", "the seventeenth installation"), ("unit eighteen", "the eighteenth installation"),
    ("unit nineteen", "the nineteenth installation"), ("unit twenty", "the twentieth installation"),
    # -- tempdoc 624 scale-corpus: appended 20->25 (append-only).
    # tempdoc 767: the doc side of 21-25 uses the DIGIT cardinal, not the spelled one — the
    # spelled form ("unit twenty-one") shares the token `twenty` with its own query-side
    # ordinal ("the twenty-first installation"), which is exactly the shared-root leak
    # `_pair_shares_token` now guards against. Digits keep the pool's cardinal/ordinal
    # contract (doc = cardinal surface, query = ordinal synonym) while being token-disjoint.
    ("unit 21", "the twenty-first installation"), ("unit 22", "the twenty-second installation"),
    ("unit 23", "the twenty-third installation"), ("unit 24", "the twenty-fourth installation"),
    ("unit 25", "the twenty-fifth installation"),
]
_SEM_QUAL_DE = [
    ("Einheit eins", "die erste Anlage"), ("Einheit zwei", "die zweite Anlage"),
    ("Einheit drei", "die dritte Anlage"), ("Einheit vier", "die vierte Anlage"),
    ("Einheit fünf", "die fünfte Anlage"), ("Einheit sechs", "die sechste Anlage"),
    ("Einheit sieben", "die siebte Anlage"), ("Einheit acht", "die achte Anlage"),
    ("Einheit neun", "die neunte Anlage"), ("Einheit zehn", "die zehnte Anlage"),
    ("Einheit elf", "die elfte Anlage"), ("Einheit zwölf", "die zwölfte Anlage"),
    ("Einheit dreizehn", "die dreizehnte Anlage"), ("Einheit vierzehn", "die vierzehnte Anlage"),
    ("Einheit fünfzehn", "die fünfzehnte Anlage"), ("Einheit sechzehn", "die sechzehnte Anlage"),
    ("Einheit siebzehn", "die siebzehnte Anlage"), ("Einheit achtzehn", "die achtzehnte Anlage"),
    ("Einheit neunzehn", "die neunzehnte Anlage"), ("Einheit zwanzig", "die zwanzigste Anlage"),
    # -- tempdoc 624 scale-corpus: appended 20->25 (append-only), index-aligned with _SEM_QUAL above.
    ("Einheit einundzwanzig", "die einundzwanzigste Anlage"), ("Einheit zweiundzwanzig", "die zweiundzwanzigste Anlage"),
    ("Einheit dreiundzwanzig", "die dreiundzwanzigste Anlage"), ("Einheit vierundzwanzig", "die vierundzwanzigste Anlage"),
    ("Einheit fünfundzwanzig", "die fünfundzwanzigste Anlage"),
]

# German synonym pools — the Invariant-#6 (ADR-0043) showcase: the doc descriptor and the
# query synonym share NO surface tokens, so grep/pure-BM25 fail, and the multilingual dense
# model must bridge German↔German semantically. Catalog phrasing ("Standort: <type>, <place>.")
# avoids gender/preposition agreement across all combinations.
_SEM_TYPE_DE = [
    ("Reaktor", "Kraftwerk"), ("Sternwarte", "Beobachtungsstation"),
    ("Wassermühle", "Getreidemühle"), ("Archiv", "Aktenlager"),
    ("Gießerei", "Metallwerk"), ("Teleskop", "Fernrohr"),
    ("Straßenbahn", "Trambahn"), ("Aquädukt", "Wasserleitung"),
    ("Druckerei", "Verlagshaus"), ("Leuchtturm", "Küstenfeuer"),
    ("Weingut", "Weinanbaugebiet"), ("Badehaus", "Thermalbad"),
    # -- tempdoc 624 scale-corpus: appended 12->21 (append-only), index-aligned with _SEM_TYPE.
    ("Kornspeicher", "Getreidelager"), ("Werft", "Schiffsbauanlage"),
    ("Brauerei", "Bierhaus"), ("Gerberei", "Lederwerk"),
    ("Münzstätte", "Prägeanstalt"), ("Schmiede", "Eisenwerkstatt"),
    ("Gewächshaus", "Pflanzenhalle"), ("Kapelle", "kleines Gotteshaus"),
    ("Windmühle", "Getreidemahlturm"),
]
_SEM_PLACE_DE = [
    ("nördliches Marschland", "oberes Feuchtgebiet"), ("östlicher Bergrücken", "Höhenzug im Osten"),
    ("Flussbiegung", "Krümmung des Flusses"), ("altes Gerichtsgebäude", "früheres Justizgebäude"),
    # tempdoc 767: "Karpatenhochland"/"Karpaten-Bergland" is the German half of the same
    # minted-proper-noun leak as the English index 5 — `Karpaten` occurs in no real host
    # document, so it is a df=1 anchor, and German compounding means a substring grep for it
    # hits both members even though they are token-disjoint. Replaced with a common-noun pair
    # (index-aligned with the English "highland plateau"/"elevated tableland").
    ("westlicher Bezirk", "Viertel im Westen"), ("Hochlandplateau", "erhöhte Tafelebene"),
    ("Hügelstadt", "Stadt an den Hängen"), ("südliche Hügel", "Anhöhen im Süden"),
    ("Marktplatz", "zentrales Marktviertel"), ("felsige Landzunge", "steiniges Vorgebirge"),
    ("sonniges Tal", "lichtdurchflutete Senke"), ("Küstenklippen", "Steilküste am Ufer"),
    ("Kiefernwald", "immergrüner Forst"), ("Salzebene", "salzhaltiges Flachland"),
    ("Granitsteinbruch", "Steingrube"), ("Hafeneinfahrt", "Zugang zum Hafen"),
    ("Wüstenbecken", "trockene Mulde"), ("zugefrorener See", "vereister Bergsee"),
    ("ummauerter Garten", "eingefriedete Anlage"), ("Uhrturm", "Glockenturm"),
    ("Kupfermine", "Erzgrube"), ("windige Heide", "stürmisches Moor"),
    ("Flussdelta", "Mündungsfächer"), ("Gebirgspass", "hohes Joch"),
    ("Fischerkai", "Anglersteg"), ("Obsthang", "Obstgarten am Hang"),
    # -- tempdoc 624 scale-corpus: appended 26->44 (append-only), index-aligned with _SEM_PLACE.
    ("Kalksteinhöhlen", "unterirdische Hohlräume"), ("Eisenbrücke", "Flussübergang aus Metall"),
    ("Überschwemmungsdorf", "tiefliegende Siedlung am Fluss"), ("terrassierte Hanglage", "gestufte Böschungsflächen"),
    ("stillgelegter Rangierbahnhof", "aufgegebenes Verschiebegelände"), ("Kreidehügel", "weiße Steilhänge"),
    ("Gezeitenwatt", "schlammige Untiefen"), ("Grenzposten", "Kontrollpunkt am Übergang"),
    ("verlassene Rollbahn", "ungenutztes Landefeld"), ("unterirdische Zisterne", "verborgener Wasserspeicher"),
    ("Wrackbucht", "Einbuchtung mit versunkenen Schiffen"), ("Thermalquellenbecken", "heiße Wassermulde"),
    ("Sandsteinfelsen", "rötliche Steilkante"), ("Fähranleger", "Übersetzstelle für Boote"),
    ("eingestürzter Schacht", "verschüttete Grabungsstätte"), ("sonniger Aussichtspunkt", "sonnenzugewandte Terrassenkante"),
    ("brackige Lagune", "salzige Meeresbucht"), ("verfallener Wachturm", "aufgegebener Beobachtungsposten"),
]


def _max_semantic_chains(lang="en"):
    """The largest gold-chain count for which every chain's (type, place, qualifier) index-triple
    is distinct — hence its synonym query identifies exactly one head.

    `_sem_for`'s gold branch assigns chain ``g`` the triple ``(g % T, g % P, g % Q)``. By the CRT
    that combined residue map is injective on ``[0, lcm(T, P, Q))``, so ``lcm`` is the EXACT ceiling.
    Tempdoc 624 scale-corpus: this replaces the old ``len(places)`` cap, which predated the query
    referencing the *full* triple — back then only the place index needed to be unique per gold, so
    the ceiling was the place-pool size (26). The rendered query now disambiguates a head by all
    three synonyms (`_render_prose`: "the {type} in the {place}, {qual}"), so two gold chains sharing
    one descriptor axis (e.g. the same place at ``g`` and ``g+P``) still differ on the other two and
    stay unambiguous — the true ceiling is the triple period lcm(T, P, Q), an order of magnitude
    larger, which is what lets a scale corpus carry hundreds of distinct queries.

    Tempdoc 624 scale-corpus (pool-growth follow-up): pools are now 21 types x 44 places x 25
    quals = 23100 triples, chosen pairwise-coprime (21=3*7, 44=2^2*11, 25=5^2) so
    lcm(21, 44, 25) == 23100 == the full triple-space size — no combination is wasted.

    Tempdoc 767: this is the ceiling for the THREE-axis regime only. The two-axis regime has
    its own, smaller-but-still-large ceiling — see `_max_semantic_pair_chains`. The
    distinction became load-bearing when the descriptor-width guard in `generate()` stopped
    treating "one axis has run out of values" as "the descriptor has run out of values"."""
    types = _SEM_TYPE_DE if lang == "de" else _SEM_TYPE
    places = _SEM_PLACE_DE if lang == "de" else _SEM_PLACE
    quals = _SEM_QUAL_DE if lang == "de" else _SEM_QUAL
    return math.lcm(len(types), len(places), len(quals))


def _max_semantic_pair_chains(lang="en"):
    """The same ceiling for the TWO-axis regime: the largest gold-chain count for which every
    chain's (type, place) index-PAIR is distinct.

    `_sem_for`'s two-axis gold branch assigns chain ``g`` the pair ``(g % T, g % P)``. By the
    same CRT argument as `_max_semantic_chains`, that pair map is injective on
    ``[0, lcm(T, P))`` and lossy from the next chain on — with T=21 and P=44 coprime,
    lcm(21, 44) == 21 * 44 == 924, i.e. the whole pair space and not merely a period inside
    it. Measured and pinned by `test_two_axis_pair_bound_is_exactly_924`:
    `_gold_descriptor_reservations(n, use_qual=False)` returns exactly ``n`` distinct pairs
    for every n <= 924 and only 924 at n = 925."""
    types = _SEM_TYPE_DE if lang == "de" else _SEM_TYPE
    places = _SEM_PLACE_DE if lang == "de" else _SEM_PLACE
    return math.lcm(len(types), len(places))


#: Ceiling on the *expected* share of generated distractor chains whose two-axis descriptor
#: is shared with another distractor. Above it `generate()` switches the qualifier axis on
#: for diversity (NOT for uniqueness — see the guard comment in `generate()`). The standing
#: regression test `test_generate_third_axis_keeps_distractor_duplication_low_at_scale`
#: asserts the realised rate stays under 25%; the budget keeps a margin below that, since
#: the realised rate is seed-dependent scatter around this expectation.
_DISTRACTOR_PAIR_DUPLICATION_BUDGET = 0.20


def _expected_distractor_pair_duplication(n_draws, n_gold_reserved, lang="en"):
    """Expected fraction of ``n_draws`` two-axis distractor descriptor draws that land in a
    (type, place) bin already occupied by another distractor.

    `_sem_for`'s distractor branch draws uniformly from the ``T * P`` pair space MINUS the
    pairs the gold chains reserved this call (rejection sampling against
    `_gold_descriptor_reservations`), so the free space is ``T * P - n_gold_reserved`` bins.
    For ``k`` balls in ``N`` bins the expected share of balls sharing a bin with at least one
    other is ``1 - (1 - 1/N)**(k - 1)``. Validated against the real generator: at k=780,
    N=898 (n_chains=26, distractor_ratio=30, hops=2) this predicts 57.8% and the generator
    measures 58.2%.

    Returns 1.0 when the free space cannot absorb the draws at all (``N <= 1``) — that is
    both maximal duplication and the regime where `_sem_for`'s two-axis rejection loop would
    not terminate, so the caller must take the qualifier axis there.
    """
    types = _SEM_TYPE_DE if lang == "de" else _SEM_TYPE
    places = _SEM_PLACE_DE if lang == "de" else _SEM_PLACE
    n_bins = len(types) * len(places) - n_gold_reserved
    if n_bins <= 1:
        return 1.0
    if n_draws < 2:
        return 0.0
    return 1.0 - (1.0 - 1.0 / n_bins) ** (n_draws - 1)


def _gold_descriptor_reservations(n_chains, lang="en", *, use_qual=True):
    """The descriptor index tuples the gold chains in a `generate()` call will occupy —
    mirrors `_sem_for`'s gold branch exactly: (type_idx, place_idx, qual_idx) triples in
    the scale regime, (type_idx, place_idx) pairs in the two-axis regime. Used to EXCLUDE
    those combinations from the distractor draw (tempdoc 624 T.1), so a distractor can
    never reproduce a gold descriptor by construction rather than merely being caught
    after the fact by `corpus_certify.descriptor_collision_report`."""
    types = _SEM_TYPE_DE if lang == "de" else _SEM_TYPE
    places = _SEM_PLACE_DE if lang == "de" else _SEM_PLACE
    if not use_qual:
        return {(g % len(types), g % len(places)) for g in range(n_chains)}
    quals = _SEM_QUAL_DE if lang == "de" else _SEM_QUAL
    return {(g % len(types), g % len(places), g % len(quals)) for g in range(n_chains)}


def _sem_for(idx, rng, *, gold, lang="en", exclude=None, use_qual=True):
    """Build a (doc_noun, query_noun, doc_place, query_place, doc_qual, query_qual) tuple.

    Gold chains get a deterministic (type, place, qualifier) triple cycled by index. The full
    triple carries uniqueness across gold chains within one `generate()` call — the rendered query
    references all three synonyms, so `generate()`'s semantic-mode cap is the triple-injectivity
    period `lcm(T, P, Q)` (`_max_semantic_chains`), not the place-pool size alone.

    Distractors draw UNIFORMLY AT RANDOM from the full descriptor space, EXCLUDING any index
    tuple already reserved by a gold chain this call (``exclude`` — see
    `_gold_descriptor_reservations`) via rejection sampling. Termination is the caller's
    responsibility and is not free in the TWO-axis regime: ``exclude`` holds min(n_chains,
    924) of the 924 (type, place) pairs, so a two-axis call with ~900 gold chains and any
    distractors would spin. `generate()`'s descriptor-width guard is what rules that out —
    it takes the qualifier axis whenever the free pair space cannot comfortably absorb the
    distractor draws (`_expected_distractor_pair_duplication` returns 1.0 at <= 1 free bin).
    In the three-axis regime the space is 23100 combinations, so a handful of draws suffices
    in the worst case. ``lang`` selects the English or German synonym pools.
    """
    types = _SEM_TYPE_DE if lang == "de" else _SEM_TYPE
    places = _SEM_PLACE_DE if lang == "de" else _SEM_PLACE
    quals = _SEM_QUAL_DE if lang == "de" else _SEM_QUAL
    if not use_qual:
        # Two-axis regime (the v0.1.0 descriptor width that generated the committed
        # 635-era gold every current 707 cell pins as fabricated inputs). Qual slots are
        # None so the renders emit the two-axis strings. Unlike v0.1.0, distractors use
        # the same T.1 exclusion discipline as the scale regime — a gold/distractor
        # (type, place) collision is structurally impossible, not seed-luck (v0.1.0 rng
        # parity had no remaining value: committed bytes are unreproducible anyway after
        # the 664 interleaving fix + append-grown pools shifting the modulo map).
        if gold:
            ti, pi = idx % len(types), idx % len(places)
        else:
            reserved = exclude or set()
            while True:
                ti = rng.randrange(len(types))
                pi = rng.randrange(len(places))
                if (ti, pi) not in reserved:
                    break
        t, p = types[ti], places[pi]
        return (t[0], t[1], p[0], p[1], None, None)
    if gold:
        ti, pi, qi = idx % len(types), idx % len(places), idx % len(quals)
    else:
        reserved = exclude or set()
        while True:
            ti = rng.randrange(len(types))
            pi = rng.randrange(len(places))
            qi = rng.randrange(len(quals))
            if (ti, pi, qi) not in reserved:
                break
    t, p, q = types[ti], places[pi], quals[qi]
    return (t[0], t[1], p[0], p[1], q[0], q[1])


def _ident(name: str) -> str:
    """A minted entity surface as a Python-ish identifier, for the ``code`` axis.

    Bank-minted names are type-matched to real ones, so they carry spaces, hyphens and
    apostrophes ("Marden Colwell"); the ``code`` renderer emits them as function names.
    The document ``_id`` stays the plain lowercased surface — this is a rendering
    concern only.
    """
    return "".join(ch if ch.isalnum() else "_" for ch in name.lower())


def _pad(text: str, target_words: int | None) -> str:
    """Pad ``text`` with :data:`_FILLER` to ``target_words``; ``None`` means do not pad.

    ``None`` is the 707 injected-corpus path — see :data:`_FILLER` for why padding is
    opt-out there rather than deleted outright.
    """
    if target_words is None:
        return text
    out = text
    while len(out.split()) < target_words:
        out += " " + _FILLER
    return out


def _filler_tail(target_words: int | None, *, comment: bool = False) -> str:
    """The renderer-appended filler block, or ``""`` when padding is off.

    The prose renderer's filler arrives solely through :func:`_pad`, but the code and
    tabular renderers also concatenate ``_FILLER`` directly into the document body — so
    the ``doc_words=None`` opt-out has to be honoured here too, or those two axes would
    still emit the enumerable boilerplate.
    """
    if target_words is None:
        return ""
    if comment:
        return "\n\n# " + _FILLER.replace(". ", ".\n# ")
    return "\n\n" + _FILLER


# --- relation vocabulary per axis (kind, prose phrasing, question phrasing) ---
#
# tempdoc 767 §I.3 (template anchor): every chain used to render with `_RELATIONS["prose"][0]`,
# because `_render_prose` indexes by HOP (`rels[i % len(rels)]`) and the 707 cells are all
# `hops=1` — so all 20 gold heads carried the byte-identical 5-gram "was designed by the
# engineer" (measured: gold coverage 0.500 against a 0.225 native base rate, one grep selecting
# half the gold set). `generate()` now ROTATES this list per chain (`_rotate`), so the head
# phrasing is spread evenly across the pool: 8 phrasings over 20 chains caps any single
# template at 3 of 40 gold docs (0.075).
#
# INVARIANT: a tuple's doc phrasing and question phrasing must share no content token
# ("designed"/"designer", "financed"/"financier"), or the semantic bridge leaks lexically and
# `query_overlap_report` rises. Guarded by `test_relation_phrasings_are_token_disjoint`.
_RELATIONS = {
    "prose": [
        ("designed", "was designed by the engineer", "the designer of"),
        ("founded", "was founded by", "the founder of"),
        ("built", "was built by", "the builder of"),
        ("led", "was led by", "the leader of"),
        # -- tempdoc 767: appended 4->8 to spread the head-template anchor.
        ("commissioned", "was commissioned by", "the commissioner of"),
        ("operated", "was operated by", "the operator of"),
        ("financed", "was financed by", "the financier of"),
        ("restored", "was restored by", "the restorer of"),
    ],
}

# The TERMINAL (attribute) sentence of an English prose chain. This was a single fixed string,
# `"{last} is associated with {attr}. "`, emitted by every one of the 20 gold tail docs —
# the other half of the two-anchor structure §I.3 found (the head template being the first).
# Rotated per chain by `generate()` exactly like `_RELATIONS`.
#
# INVARIANT: no template may contain 5 consecutive LITERAL tokens, or it reintroduces a fixed
# 5-gram anchor of its own regardless of how evenly it is spread. The longest literal run below
# is 4 ("The entry filed under"). Guarded by `test_tail_phrasings_have_no_fixed_5gram`.
_TAIL_PHRASINGS = [
    "{last} is associated with {attr}. ",
    "The record for {last} lists {attr}. ",
    "{last} carries the designation {attr}. ",
    "The entry filed under {last} reads {attr}. ",
    "{last} corresponds to {attr}. ",
    "The register notes {attr} for {last}. ",
    "{last} is catalogued as {attr}. ",
    "The final annotation for {last} gives {attr}. ",
]

# --- German relation + tail vocabulary (tempdoc 748 §D-2) ------------------------------
#
# The German prose path had the defect tempdoc 767 §I.3 closed for English, but never
# closed for German: `_render_prose`'s `de` branch emitted ONE fixed link sentence
# ("Das Objekt {x} ist mit {y} verknüpft.") and ONE fixed tail ("{last} ist mit dem Wert
# {attr} verbunden."), so every German gold document in every 707 de-miracl cell carried a
# byte-identical template — "Das Objekt" selects the whole head set and "ist mit dem Wert"
# the whole tail set, one grep each. These pools are rotated per CHAIN by `generate()`
# exactly like their English counterparts.
#
# STRUCTURAL NOTE: the English tuple's doc phrasing is contiguous between the two entities
# ("The {a} was designed by the engineer {b}"). German word order does not allow that for a
# passive agent phrase — "wurde von {b} entworfen" puts the participle after the agent — so
# the German doc member is the trailing participle and the frame is
# "{a} wurde von {b} {participle}." That frame ("wurde … von") is among the most common
# constructions in German encyclopedic prose, i.e. camouflage rather than a signature, and
# the varying participle is what the rotation spreads.
#
# INVARIANT (mirrors `_RELATIONS`): a tuple's doc phrasing and question phrasing must share
# no content token, or the semantic bridge leaks lexically. "entworfen"/"Konstrukteurs" are
# distinct tokens — as are English's "designed"/"designer". Guarded by
# `test_relation_phrasings_are_token_disjoint` (both pools).
_RELATIONS_DE = [
    ("entworfen", "entworfen", "des Konstrukteurs"),
    ("gegründet", "gegründet", "des Urhebers"),
    ("erbaut", "erbaut", "des Baumeisters"),
    ("geleitet", "geleitet", "der Leitung"),
    ("beauftragt", "beauftragt", "des Auftraggebers"),
    ("betrieben", "betrieben", "der Betriebsführung"),
    ("finanziert", "finanziert", "des Geldgebers"),
    ("restauriert", "restauriert", "der Instandsetzung"),
]

# The TERMINAL (attribute) sentence of a German prose chain — the counterpart of
# `_TAIL_PHRASINGS`.
#
# INVARIANT: no template may contain 5 consecutive LITERAL tokens **as
# `corpus_leak._tokenize` counts them** — that tokenizer is `[a-z0-9']+`, so a German
# umlaut or ß SPLITS one word into two tokens ("abschließende" -> "abschlie" + "ende",
# "für" -> "f" + "r"). A 4-WORD German literal run therefore becomes a 5-TOKEN n-gram
# anchor, which is exactly how the first draft of this pool failed
# `ngram_selectivity_report` at gold coverage 0.06 against a 0.02 German-Wikipedia null
# (measured, tempdoc 748 §D-2). Every literal span below is <= 3 tokens under that
# tokenizer, so every 5-gram window necessarily contains a slot-derived token. Guarded by
# `test_tail_phrasings_have_no_fixed_5gram`, which now counts tokens the leak instrument's
# way rather than the Unicode way.
_TAIL_PHRASINGS_DE = [
    "{last} ist dem Wert {attr} zugeordnet. ",
    "Der Eintrag zu {last} nennt {attr}. ",
    "{last} hat die Kennung {attr}. ",
    "Im Verzeichnis steht {last} mit {attr}. ",
    "{last} entspricht {attr}. ",
    "Das Register vermerkt {attr} zu {last}. ",
    "{last} ist als {attr} erfasst. ",
    "Notiz zu {last}: {attr}. ",
]


def _rotate(pool, offset):
    """``pool`` rotated left by ``offset`` — the per-chain template spread (tempdoc 767 §I.3).

    Rotating (rather than picking `pool[offset]`) keeps `_render_prose`'s existing
    hop-indexed `pool[i % len(pool)]` walk intact: chain ``g`` starts at ``pool[g % len(pool)]``
    and a multi-hop chain still cycles through distinct phrasings for its successive hops.
    Deterministic and rng-free, so it adds no draw to the seeded stream.
    """
    k = offset % len(pool)
    return pool[k:] + pool[:k]


def _chain(rng, hops, minter):
    """A fabricated chain: entities e0..e{hops} (globally-unique), ending in an attribute.

    ``minter`` is the call's single :class:`jseval.entity_bank.Minter`; it owns global
    uniqueness for both names and values, which is what the deleted monotonic uid counter
    used to provide.

    tempdoc 767 §I.2 (answer leak): the attribute is drawn from a format-diverse space
    that is INDEPENDENT of the entities. Previously both the entity names and the value
    were derived from the same monotonic counter (``Tasdell272`` … ``ochre ferrolite
    0272``), so the answer was inferable from any entity name in the chain without
    retrieving the final document at all.
    """
    ents = [minter.mint_entity(rng) for _ in range(hops + 1)]
    return ents, minter.mint_value(rng)


def _aggregation_members(rng, minter, k, gold_kind):
    """Mint ``k`` gold members for one aggregation group + the precomputed gold answer.

    ``set``/``count`` members carry a format-diverse minted value; ``extremum`` members carry
    a numeric measure (``M-<int>``, distinct within the group) so the extremum is well defined.
    Every name is globally unique (minter ledger) and every value is digit-independent of the
    names (minter invariant / numeric measure), so no member's answer is inferable from a name.
    Deterministic given ``rng`` state, mint order fixed: entity, then value, per member.
    """
    members = []
    if gold_kind == "extremum":
        seen = set()
        for _ in range(k):
            name = minter.mint_entity(rng)
            while True:
                n = rng.randrange(1000, 1_000_000)
                if n not in seen:
                    seen.add(n)
                    break
            members.append((name, f"M-{n}"))
        answer = f"M-{max(seen)}"
    else:
        for _ in range(k):
            name = minter.mint_entity(rng)
            members.append((name, minter.mint_value(rng)))
        if gold_kind == "count":
            answer = str(k)
        else:  # set
            answer = _comparators.SET_DELIMITER.join(sorted(value for _, value in members))
    return members, answer


def _render_prose(ents, attr, rels, target_words, lang="en", sem=None, tails=_TAIL_PHRASINGS):
    """Render a chain as gold docs (one per hop link) + a multi-hop question.

    If ``sem`` is a `_SEM` tuple, the HEAD doc describes the entity by a descriptor and
    the QUERY references the head via SYNONYMS (low lexical overlap → grep fails, semantic
    retrieval bridges) — the Issue-A/B fix. The rest of the chain uses names as before.

    NOTE (tempdoc 731 §3.3, issue 14): the emitted ``question_type`` below counts chain
    *edges*, not behavioral retrieval hops — `f"{len(ents)-1}_hop"` for an N-entity chain
    means N-1 edges, but answering requires N retrievals (one gold doc per entity). Do not
    relabel this field: `queries.json` bytes are commitment-bound (`query_gold_sha256` in
    `corpus_certify.py:107,274,292`), so a relabel invalidates every committed cell
    signature. The behavioral hop count is already derivable without a relabel:
    `retrieval_hops = len(evidence_ids)` (edges + 1).
    """
    docs = []
    # Descriptor-axis width: two-axis (qual slots None — the v0.1.0 regime the committed
    # 635-era gold used) vs three-axis (tempdoc 624 T.1 scale regime). dq/qq are the
    # doc-side/query-side qualifier suffixes; empty in the two-axis regime.
    dq = f", {sem[4]}" if sem and sem[4] is not None else ""
    qq = f", {sem[5]}" if sem and sem[5] is not None else ""
    for i in range(len(ents) - 1):
        rel = rels[i % len(rels)]
        if lang == "de":
            # tempdoc 748 §D-2: the link sentence used to be the fixed string "Das Objekt
            # {x} ist mit {y} verknüpft." on every German gold doc. `rels` is now the
            # per-chain-rotated `_RELATIONS_DE`, so the participle varies; see that pool
            # for why the German doc member is a participle rather than a full phrase.
            link = f"{ents[i]} wurde von {ents[i+1]} {rel[1]}. "
            if sem and i == 0:
                # head doc: German descriptor (sem[0]/sem[2]/sem[4]); the query references it
                # by German SYNONYMS (sem[1]/sem[3]/sem[5]) → grep fails, multilingual dense bridges.
                body = f"Standort: {sem[0]}, {sem[2]}{dq}. " + link
                title = f"Standort {sem[0]}, {sem[2]}{dq}"
            else:
                body = link
                title = f"Über {ents[i]}"
        elif sem and i == 0:
            # head doc: surface descriptor (doc_noun/doc_place[/doc_qual]) + name + link
            body = (f"The {sem[0]} in the {sem[2]}{dq}, designated {ents[i]}, "
                    f"{rel[1]} {ents[i+1]}. ")
            title = f"The {sem[0]} in the {sem[2]}{dq}"
        else:
            body = f"The {ents[i]} {rel[1]} {ents[i+1]}. "
            title = f"The {ents[i]}"
        docs.append((ents[i].lower(), title, _pad(body, target_words)))
    last = ents[-1]
    if lang == "de":
        # tempdoc 748 §D-2: the tail was the fixed "{last} ist mit dem Wert {attr}
        # verbunden." on every German gold tail doc; `tails` is now the per-chain-rotated
        # pool, exactly as on the English path.
        docs.append((last.lower(), f"Über {last}",
                     _pad(tails[0].format(last=last, attr=attr), target_words)))
        # head reference: SYNONYM descriptor (semantic) or the verbatim name (lexical),
        # wrapped in one genitive relation phrase per hop — the German mirror of the
        # English `f"{rels[i][2]} {phrase}"` nesting. Genitive chains natively in German
        # ("des Konstrukteurs des Standorts …"), so multi-hop nests without a connector.
        head_ref = f"des Standorts {sem[1]}, {sem[3]}{qq}" if sem else ents[0]
        phrase = head_ref
        for i in range(len(ents) - 1):
            phrase = f"{rels[i % len(rels)][2]} {phrase}"
        q = f"Welcher Wert steht am Ende der Kette {phrase}?"
    else:
        # tail phrasing is rotated per chain by `generate()` (tempdoc 767 §I.3) — a single
        # fixed string here made every gold tail doc share one template.
        docs.append((last.lower(), f"The {last}",
                     _pad(tails[0].format(last=last, attr=attr), target_words)))
        # head reference: SYNONYM descriptor (semantic) or the verbatim name (lexical)
        head_ref = f"the {sem[1]} in the {sem[3]}{qq}" if sem else ents[0]
        phrase = head_ref
        for i in range(len(ents) - 1):
            phrase = f"{rels[i % len(rels)][2]} {phrase}"
        q = f"What is the value associated with {phrase}?"
    evidence = [e.lower() for e in ents]
    # question_type counts edges (N-1 for an N-entity chain); behavioral retrieval hops =
    # edges + 1 = len(evidence_ids) — see the docstring above and tempdoc 731 §3.3.
    return docs, {"query": q, "answer": attr, "question_type": f"{len(ents)-1}_hop", "evidence_ids": evidence}


# Aggregation membership sentences (tempdoc 776 §A.1). A member document links its own
# distinctive name to the SHARED group descriptor (`{type}` in `{place}{dq}`) — the shared
# signal that forces retrieval to aggregate across the k member docs, carried in the BODY so
# each member keeps a distinct `title` (no gold-involved title collision). Rotated per group
# by `generate()` exactly like `_RELATIONS`/`_TAIL_PHRASINGS`, so no single membership phrasing
# anchors the whole aggregation gold set (the "extend the existing mechanism, no new authored
# fingerprint" discipline). INVARIANT: no 5 consecutive LITERAL tokens (the descriptor slots
# break every run); the longest literal run below is 4 ("is listed under the").
_MEMBERSHIP_PHRASINGS = [
    "The {name} entry belongs to the {type} in the {place}{dq}. ",
    "Filed with the {type} in the {place}{dq}, {name} appears in the register. ",
    "{name} is listed under the {type} in the {place}{dq}. ",
    "Among the {type} in the {place}{dq}, the record for {name} is held. ",
    "The register of the {type} in the {place}{dq} includes {name}. ",
    "{name} forms part of the {type} in the {place}{dq}. ",
]
_MEMBERSHIP_PHRASINGS_DE = [
    "Der Eintrag {name} gehört zu {type}, {place}{dq}. ",
    "Bei {type}, {place}{dq}, ist {name} verzeichnet. ",
    "{name} ist unter {type}, {place}{dq} aufgeführt. ",
    "Zum Bestand von {type}, {place}{dq} zählt {name}. ",
    "Das Register von {type}, {place}{dq} enthält {name}. ",
    "{name} gehört zum Bestand von {type}, {place}{dq}. ",
]

#: Aggregation group sizes cycle deterministically over 3-5 member documents (tempdoc 776).
_AGGREGATION_SIZES = (3, 4, 5)
#: Aggregation gold_kinds cycle deterministically so a mix exercises every comparator.
_AGGREGATION_KINDS = ("set", "count", "extremum")


def _render_single_fact(ent, attr, sem, chain_idx, target_words, lang="en",
                        tails=_TAIL_PHRASINGS):
    """One gold doc carrying one camouflaged fact (tempdoc 776 §A.1).

    The paraphrase barrier is the bridge hop-1 head mechanism reused verbatim: the document
    describes ``ent`` via a surface descriptor (``sem[0]``/``sem[2]``) and the QUERY references
    it via zero-overlap SYNONYMS (``sem[1]``/``sem[3]``) — grep/pure-BM25 fail at the entry and
    dense must bridge. One evidence doc; ``gold_kind`` = ``single_value``; the answer never
    appears in the query, so it is not guessable without retrieving this document.
    """
    dq = f", {sem[4]}" if sem and sem[4] is not None else ""
    qq = f", {sem[5]}" if sem and sem[5] is not None else ""
    tail = _rotate(tails, chain_idx)[0]
    if lang == "de":
        body = (f"Der Standort {sem[0]}, {sem[2]}{dq}, bezeichnet als {ent}. "
                + tail.format(last=ent, attr=attr))
        title = f"Standort {sem[0]}, {sem[2]}{dq}"
        q = f"Welcher Wert ist mit dem Standort {sem[1]}, {sem[3]}{qq} verzeichnet?"
    else:
        body = (f"The {sem[0]} in the {sem[2]}{dq}, designated {ent}. "
                + tail.format(last=ent, attr=attr))
        title = f"The {sem[0]} in the {sem[2]}{dq}"
        q = f"What is the value recorded for the {sem[1]} in the {sem[3]}{qq}?"
    docs = [(ent.lower(), title, _pad(body, target_words))]
    return docs, {"query": q, "answer": attr, "question_type": "single_fact",
                  "gold_kind": "single_value", "evidence_ids": [ent.lower()]}


def _render_aggregation(members, answer, sem, gold_kind, chain_idx, target_words, lang="en",
                        memberships=_MEMBERSHIP_PHRASINGS, tails=_TAIL_PHRASINGS):
    """k gold docs sharing ONE camouflaged group descriptor + a set/count/extremum question.

    ``members`` is ``[(name, value_str), ...]`` (3-5 entries). Each member document carries a
    DISTINCT title (its own name) and a body that (a) links the name to the shared group
    descriptor via a rotated membership phrasing and (b) records the member's value via a
    rotated tail phrasing. The query names the group only by SYNONYM descriptor, so the whole
    gold set must be retrieved and aggregated. ``answer`` is precomputed by the caller per
    ``gold_kind`` (the ``set`` join / the ``count`` / the ``extremum`` value); the comparator
    registry (:mod:`jseval.corpus_comparators`) scores it deterministically, no judge.
    """
    # tempdoc 748: the pool is chosen by the caller (`generate()` picks per language) rather
    # than overridden here, so an explicitly-passed pool is never silently discarded.
    if lang == "de" and memberships is _MEMBERSHIP_PHRASINGS:
        memberships = _MEMBERSHIP_PHRASINGS_DE
    dq = f", {sem[4]}" if sem and sem[4] is not None else ""
    qq = f", {sem[5]}" if sem and sem[5] is not None else ""
    memb = _rotate(memberships, chain_idx)
    rot = _rotate(tails, chain_idx)
    docs = []
    for j, (name, value) in enumerate(members):
        link = memb[j % len(memb)].format(name=name, type=sem[0], place=sem[2], dq=dq)
        body = link + rot[j % len(rot)].format(last=name, attr=value)
        title = f"Eintrag {name}" if lang == "de" else f"The {name} entry"
        docs.append((name.lower(), title, _pad(body, target_words)))
    if lang == "de":
        group = f"den Standort {sem[1]}, {sem[3]}{qq}"  # für/… governs the accusative
        if gold_kind == "count":
            q = f"Wie viele Einträge sind für {group} verzeichnet?"
        elif gold_kind == "extremum":
            q = f"Was ist der höchste für {group} verzeichnete Messwert?"
        else:
            q = f"Welche Werte sind für {group} verzeichnet?"
    else:
        group = f"the {sem[1]} in the {sem[3]}{qq}"
        if gold_kind == "count":
            q = f"How many entries are recorded for {group}?"
        elif gold_kind == "extremum":
            q = f"What is the highest measurement recorded for {group}?"
        else:
            q = f"Which values are recorded for {group}?"
    return docs, {"query": q, "answer": answer, "question_type": "aggregation",
                  "gold_kind": gold_kind, "evidence_ids": [name.lower() for name, _ in members]}


def _render_code(ents, attr, target_words, idx, sem=None):
    """Render a chain as code files: fn e0 calls e1 calls ... returns attr. Multi-hop = call trace.

    If ``sem`` is set, the head function carries its purpose as a descriptor comment
    (sem[0]/sem[2]/sem[4]) and the QUERY references it via SYNONYMS (sem[1]/sem[3]/sem[5])
    without naming the function — so grep/pure-BM25 fail at the entry and dense must bridge
    semantically.

    NOTE (tempdoc 731 §3.3, issue 14): the emitted ``question_type`` below counts chain
    *edges*, not behavioral retrieval hops — `f"{len(ents)-1}_hop"` for an N-entity chain
    means N-1 edges, but answering requires N retrievals (one gold doc per entity). Do not
    relabel this field: `queries.json` bytes are commitment-bound (`query_gold_sha256` in
    `corpus_certify.py:107,274,292`), so a relabel invalidates every committed cell
    signature. The behavioral hop count is already derivable without a relabel:
    `retrieval_hops = len(evidence_ids)` (edges + 1).
    """
    docs = []
    dq = f", {sem[4]}" if sem and sem[4] is not None else ""
    qq = f", {sem[5]}" if sem and sem[5] is not None else ""
    for i in range(len(ents) - 1):
        if sem and i == 0:
            # head doc: descriptor in the TITLE + a module docstring (the high-signal fields
            # dense embeds), mirroring the prose member — sem[0]/sem[2]/sem[4] (doc side) so the
            # query's sem[1]/sem[3]/sem[5] synonyms stay zero-overlap (grep-defeating).
            title = f"the {sem[0]} in the {sem[2]}{dq}"
            body = (f'"""This module concerns the {sem[0]} in the {sem[2]}{dq}."""\n'
                    f"def {_ident(ents[i])}():\n    return {_ident(ents[i+1])}()"
                    + _filler_tail(target_words, comment=True))
        else:
            title = f"{_ident(ents[i])}.py"
            body = (f"def {_ident(ents[i])}():\n    # module helper {idx}.{i}\n"
                    f"    return {_ident(ents[i+1])}()"
                    + _filler_tail(target_words, comment=True))
        docs.append((ents[i].lower(), title, _pad(body, target_words)))
    last = ents[-1]
    body = (f"def {_ident(last)}():\n    return {attr!r}"
            + _filler_tail(target_words, comment=True))
    docs.append((last.lower(), f"{_ident(last)}.py", _pad(body, target_words)))
    if sem:
        q = (f"What value is ultimately returned by the routine for the "
             f"{sem[1]} in the {sem[3]}{qq}?")
    else:
        q = f"What value does the function {_ident(ents[0])}() ultimately return when called?"
    # question_type counts edges (N-1 for an N-entity chain); behavioral retrieval hops =
    # edges + 1 = len(evidence_ids) — see the docstring above and tempdoc 731 §3.3.
    return docs, {"query": q, "answer": attr, "question_type": f"{len(ents)-1}_hop", "evidence_ids": [e.lower() for e in ents]}


def _render_tabular(ents, attr, target_words, idx, sem=None):
    """Render a chain as table rows requiring a join across docs.

    If ``sem`` is set, the head table carries a descriptor caption (sem[0]/sem[2]/sem[4]) and
    the QUERY references it via SYNONYMS (sem[1]/sem[3]/sem[5]) without naming the head entity
    — so grep/pure-BM25 fail and dense must bridge semantically.

    NOTE (tempdoc 731 §3.3, issue 14): the emitted ``question_type`` below counts chain
    *edges*, not behavioral retrieval hops — `f"{len(ents)-1}_hop"` for an N-entity chain
    means N-1 edges, but answering requires N retrievals (one gold doc per entity). Do not
    relabel this field: `queries.json` bytes are commitment-bound (`query_gold_sha256` in
    `corpus_certify.py:107,274,292`), so a relabel invalidates every committed cell
    signature. The behavioral hop count is already derivable without a relabel:
    `retrieval_hops = len(evidence_ids)` (edges + 1).
    """
    docs = []
    dq = f", {sem[4]}" if sem and sem[4] is not None else ""
    qq = f", {sem[5]}" if sem and sem[5] is not None else ""
    for i in range(len(ents) - 1):
        if sem and i == 0:
            # head table: descriptor in the TITLE + a leading caption (high-signal), mirroring
            # the prose member — doc-side sem[0]/sem[2]/sem[4] keeps the query's
            # sem[1]/sem[3]/sem[5] zero-overlap.
            title = f"the {sem[0]} in the {sem[2]}{dq}"
            caption = f"Table for the {sem[0]} in the {sem[2]}{dq}.\n"
        else:
            title = f"table_{ents[i].lower()}"
            caption = ""
        body = (f"{caption}| entity | linked_to |\n|---|---|\n| {ents[i]} | {ents[i+1]} |"
                + _filler_tail(target_words))
        docs.append((ents[i].lower(), title, _pad(body, target_words)))
    last = ents[-1]
    body = (f"| entity | attribute |\n|---|---|\n| {last} | {attr} |" + _filler_tail(target_words))
    docs.append((last.lower(), f"table_{last.lower()}", _pad(body, target_words)))
    if sem:
        q = (f"In the records for the {sem[1]} in the {sem[3]}{qq}, following the links, "
             f"what attribute is recorded for the final entity?")
    else:
        q = f"Following the links starting from {ents[0]}, what attribute is recorded for the final entity?"
    # question_type counts edges (N-1 for an N-entity chain); behavioral retrieval hops =
    # edges + 1 = len(evidence_ids) — see the docstring above and tempdoc 731 §3.3.
    return docs, {"query": q, "answer": attr, "question_type": f"{len(ents)-1}_hop", "evidence_ids": [e.lower() for e in ents]}


# Degraded-scan defaults (tempdoc 624 §T.2 confidence pass): a live probe against
# Claude Code's own `Read` tool found a plain rendered scan is read correctly via
# multimodal vision (no block at all), but THIS band -- small font, low contrast,
# Gaussian blur, a several-degree rotation, and salt-and-pepper noise -- made `Read`
# correctly report it could see text but not read it clearly, and decline to answer.
# Tuned to defeat a casual multimodal read while (intended to) remain within reach of
# the production Tika/VLM extraction path -- the real-ingest half of this is the one
# unverified assumption named in §T.2 (item 7 of the confidence pass).
_SCAN_DEFAULTS = {
    "font_size": 13,
    "bg_gray": 210,
    "text_gray": 70,
    "blur_radius": 1.3,
    "rotation_deg": 6.5,
    "noise_ratio": 0.08,
}


# Sanity ceilings for `render_scan_image` (tempdoc 624 confidence-pass follow-up):
# unbounded width/font_size/text-length let `height` grow without limit before the
# PIL `Image` is allocated -- a latent resource-exhaustion risk if this function is
# ever reused with less-trusted input. Calibrated against the largest real caller
# (`635-corpora/synth-scan-v1`, doc_words=520): worst committed doc is 3,888 chars
# of title+text, rendered at the only width/font_size ever passed in this codebase
# (900px / 13pt) to a pre-rotation page height of 580px. Each ceiling below carries
# ~10x headroom over that real maximum -- generous enough that no existing caller
# will ever come close, while still bounding worst-case memory allocation.
MAX_SCAN_TEXT_CHARS = 40_000
MAX_SCAN_WIDTH_PX = 9_000
MAX_SCAN_FONT_SIZE = 130
MAX_SCAN_HEIGHT_PX = 6_000


class ScanRenderLimitExceeded(ValueError):
    """Raised by ``render_scan_image`` when an input would allocate an unreasonably
    large PIL Image (oversized text, width, font size, or the resulting page height)."""


def render_scan_image(text, *, width=900, font_size=13, bg_gray=210, text_gray=70,
                       blur_radius=1.3, rotation_deg=6.5, noise_ratio=0.08, seed=0) -> bytes:
    """Render ``text`` as a synthetic degraded scanned-page PNG (returns PNG bytes).

    Word-wraps onto a plain page, then applies (in order) a rotation, a Gaussian
    blur, and salt-and-pepper noise -- the degradation band confirmed live against
    Claude Code's own `Read` tool (see ``_SCAN_DEFAULTS``). Deterministic for a
    given ``seed`` (noise placement is the only randomized step).

    Raises ``ScanRenderLimitExceeded`` if ``text``, ``width``, ``font_size``, or the
    resulting wrapped page height would exceed the sanity ceilings above -- rather
    than silently truncating or allocating an unbounded image.
    """
    try:
        from PIL import Image, ImageDraw, ImageFilter, ImageFont
    except ImportError:
        raise ImportError(
            "Pillow is required for the 'scan' corpus axis. "
            "Install with: pip install -e scripts/jsharness -e 'scripts/jseval[scan]'"
        )

    if len(text) > MAX_SCAN_TEXT_CHARS:
        raise ScanRenderLimitExceeded(
            f"render_scan_image: text is {len(text)} chars, exceeds the "
            f"{MAX_SCAN_TEXT_CHARS}-char sanity ceiling"
        )
    if width > MAX_SCAN_WIDTH_PX:
        raise ScanRenderLimitExceeded(
            f"render_scan_image: width={width}px exceeds the {MAX_SCAN_WIDTH_PX}px sanity ceiling"
        )
    if font_size > MAX_SCAN_FONT_SIZE:
        raise ScanRenderLimitExceeded(
            f"render_scan_image: font_size={font_size} exceeds the "
            f"{MAX_SCAN_FONT_SIZE}pt sanity ceiling"
        )

    rng = random.Random(seed)
    font = ImageFont.load_default(size=font_size)
    margin = 24
    line_height = font_size + 6
    max_line_width = width - 2 * margin

    probe_img = Image.new("L", (10, 10))
    probe_draw = ImageDraw.Draw(probe_img)
    lines: list[str] = []
    cur = ""
    for word in text.split():
        trial = f"{cur} {word}".strip()
        if probe_draw.textlength(trial, font=font) <= max_line_width:
            cur = trial
        else:
            if cur:
                lines.append(cur)
            cur = word
    if cur:
        lines.append(cur)
    if not lines:
        lines = [""]

    height = margin * 2 + line_height * len(lines)
    if height > MAX_SCAN_HEIGHT_PX:
        raise ScanRenderLimitExceeded(
            f"render_scan_image: wrapped page height={height}px ({len(lines)} lines) "
            f"exceeds the {MAX_SCAN_HEIGHT_PX}px sanity ceiling"
        )
    page = Image.new("L", (width, height), color=bg_gray)
    draw = ImageDraw.Draw(page)
    y = margin
    for line in lines:
        draw.text((margin, y), line, fill=text_gray, font=font)
        y += line_height

    page = page.rotate(rotation_deg, expand=True, fillcolor=bg_gray, resample=Image.BICUBIC)
    page = page.filter(ImageFilter.GaussianBlur(radius=blur_radius))

    pixels = page.load()
    w, h = page.size
    n_noise = int(w * h * noise_ratio)
    for _ in range(n_noise):
        x = rng.randrange(w)
        y2 = rng.randrange(h)
        pixels[x, y2] = 255 if rng.random() < 0.5 else 0

    buf = io.BytesIO()
    page.convert("RGB").save(buf, format="PNG")
    return buf.getvalue()


def render_scan_page(doc_id: str, title: str, text: str, *, degrade: dict | None = None) -> bytes:
    """Render one document's (title, text) as a degraded scan-page PNG, deterministic
    per ``doc_id`` (a stable SHA-256 seed, not the per-process-randomized `hash()`).

    Called at MATERIALIZE time (`corpus_build.build_golden`), not generation time --
    the ``axis="scan"`` corpus member's committed *source* (`docs.jsonl`) stays plain
    ground-truth text, identical in shape to every other axis; the image is a derived,
    regenerable *artifact* (same doc_id + text -> same PNG, always), never persisted in
    source control (tempdoc 624 §T.2 -- keeps the "single source -> two projections"
    pattern `corpus_build.py` already establishes: `datasets/` is gitignored precisely
    because materialized artifacts are always reconstructable from the committed source).
    """
    page_text = f"{title}\n\n{text}" if title else text
    # `hash()` is per-process-randomized (PEP 456) unless PYTHONHASHSEED is pinned,
    # which this repo never does (see the axis_offset comment in generate() below) --
    # a SHA-256 digest of the doc id is stable across processes instead.
    seed = int(hashlib.sha256(doc_id.encode("utf-8")).hexdigest(), 16) % (2**32)
    params = dict(_SCAN_DEFAULTS)
    if degrade:
        params.update(degrade)
    return render_scan_image(page_text, seed=seed, **params)


def materialize_doc_entry(doc: dict, type_axis: str | None) -> dict:
    """Build one document's `materialize.materialize()` input entry, applying the
    axis-aware scan-rendering decision exactly once (tempdoc 624 follow-up).

    `doc` is a plain BEIR-shape dict (`_id`/`title`/`text`) — a `docs.jsonl` source
    line or a golden/mixed `corpus.jsonl` line. When `type_axis == "scan"`, attaches a
    base64-encoded degraded-scan PNG (via `render_scan_page`) as `image_b64`, so
    `materialize.materialize()` writes a `.png` artifact instead of a `.txt`.

    This is the ONE place the `type_axis == "scan"` check + `render_scan_page` call are
    made. Both `corpus_build.build_golden` (materializing `datasets/golden/<name>/
    corpus-dir/`) and `ingest._materialize_into` (materializing an eval run's ingest
    directory from `corpus.jsonl`) call this helper instead of each re-implementing the
    check — a second independent copy is exactly how the axis got dropped silently on
    one of the two paths the first time.
    """
    entry = {"_id": doc["_id"], "title": doc.get("title", ""), "text": doc["text"]}
    if type_axis == "scan":
        png_bytes = render_scan_page(doc["_id"], doc.get("title", ""), doc["text"])
        entry["image_b64"] = base64.b64encode(png_bytes).decode("ascii")
    return entry


#: Bumped whenever the emitted payload changes shape. Its PRESENCE in a corpus's
#: ``generation_provenance`` is what tells `corpus_certify.regeneration_determinism_report`
#: to demand the v2 parameter set and to FAIL (not skip) on a missing key — see that
#: function for why a silent skip was the danger here.
PAYLOAD_VERSION = "payload.v2"


#: The schemas a `schema_mix` may request (tempdoc 776 §A.1). `bridge` is the existing
#: 2-entity chain; `single_fact` and `aggregation` are the new schemas. Temporal/negation
#: stay candidates (767 §A / 776 §A.1).
_SCHEMA_KINDS = ("bridge", "single_fact", "aggregation")


def _schema_plan(schema_mix, *, axis: str, sem_active: bool) -> list[str] | None:
    """Validate ``schema_mix`` and expand it to a deterministic ordered list of schema kinds.

    ``None`` returns ``None`` (the default single-schema bridge path is taken unchanged — the
    opt-in boundary that keeps every existing recipe byte-stable). A dict maps schema kind ->
    count; the plan is kinds emitted in the fixed :data:`_SCHEMA_KINDS` order (deterministic).
    The new schemas render prose and need the synonym paraphrase barrier, so a mix requires
    ``axis == "prose"`` and ``semantic=True``.
    """
    if schema_mix is None:
        return None
    if not isinstance(schema_mix, dict) or not schema_mix:
        raise ValueError("schema_mix must be a non-empty {schema: count} mapping or None")
    unknown = set(schema_mix) - set(_SCHEMA_KINDS)
    if unknown:
        raise ValueError(f"schema_mix has unknown schemas {sorted(unknown)}; known: {list(_SCHEMA_KINDS)}")
    counts = {}
    for kind, count in schema_mix.items():
        if not isinstance(count, int) or isinstance(count, bool) or count < 0:
            raise ValueError(f"schema_mix[{kind!r}] must be a non-negative int, got {count!r}")
        counts[kind] = count
    plan = [kind for kind in _SCHEMA_KINDS for _ in range(counts.get(kind, 0))]
    if not plan:
        raise ValueError("schema_mix totals zero questions")
    if axis != "prose":
        raise ValueError("schema_mix is only supported on axis='prose'")
    if not sem_active:
        raise ValueError("schema_mix requires semantic=True (the single_fact/aggregation paraphrase barrier)")
    return plan


def generate(out_dir, *, axis="prose", lang="en", n_chains=20, hops=2,
             distractor_ratio=6, doc_words=520, suite="635-self-demo-v1", seed=635,
             semantic=False, entity_bank=None, schema_mix=None):
    """Generate a fabricated corpus source into ``out_dir`` (docs.jsonl/queries.json/meta.json).

    distractor_ratio = distractor docs per gold doc (de-risk: ~5–10:1 to reach the band).
    ``0`` is valid and is the right value for the INJECTED (707) path: `corpus_inject.assemble`
    keeps only the docs named by `evidence_ids` and draws its distractors from the real host
    corpus, so generated distractors there are produced and discarded (verified byte-identical
    assembled output either way — `test_distractor_ratio_zero_leaves_the_gold_payload_identical`).
    They are not free: they are what can push the descriptor-width guard below onto the
    qualifier axis, whose longer queries break the 5-12-word short-natural stratum.
    semantic=True (all axes, en+de): the head is referenced in the query by SYNONYMS of its
    descriptor (low lexical overlap → grep/BM25 fail at the entry, semantic retrieval wins).
    code/tabular carry the descriptor in a head comment/caption; German uses the de synonym
    pools. ``n_chains`` is capped at the descriptor-injectivity ceiling `_max_semantic_chains`
    (23100); the descriptor WIDTH used below that is chosen by the two-concern guard in the
    body (uniqueness up to `_max_semantic_pair_chains` = 924, distractor diversity).

    ``doc_words`` is the padding target, and ``None`` means DO NOT PAD (tempdoc 767 §I.2):
    the 707 corpora are injected into a real host document that already supplies
    register-native bulk, so padding there is purely additive boilerplate that makes every
    gold document enumerable by one grep. The standalone 635 goldens keep an integer.

    ``entity_bank`` is the directory holding a frozen, committed ``entity-bank.v2.json`` +
    ``commitment.v1.json`` (see :mod:`jseval.entity_bank`). It is REQUIRED: chain entities
    are minted type- and length-matched against real host entities and collision-checked
    against them, which replaces the syllable-pair minter. The bank's sha256 is recorded in
    ``generation_provenance`` so a skeptic can reproduce the corpus exactly.
    """
    if entity_bank is None:
        raise ValueError(
            "generate() requires entity_bank=<dir containing entity-bank.v2.json>; "
            "the syllable-pair name minter it replaces was deleted in tempdoc 767"
        )
    bank_root = Path(entity_bank)
    verdict = _entity_bank.validate_entity_bank(bank_root)
    if not verdict["passed"]:
        raise ValueError(f"unusable entity bank at {bank_root}: {verdict['reason']}")
    bank = _entity_bank.load_bank(bank_root)
    minter = _entity_bank.Minter(bank)
    # tempdoc 776 §A.1: the opt-in multi-schema plan. `None` => the existing single-schema
    # bridge path, byte-identical. Validated before any RNG draw so a bad mix fails fast.
    schema_plan = _schema_plan(schema_mix, axis=axis, sem_active=bool(semantic))
    # `hash(axis)` (a builtin str hash) is randomized per-process (PEP 456) unless
    # PYTHONHASHSEED is pinned, which this repo never does — so the "seeded -> reproducible"
    # claim above was false: two separate process invocations with the identical nominal `seed`
    # produced a completely different corpus (confirmed empirically, tempdoc 664 confidence pass:
    # 280/280 docs differed). A SHA-256 digest of `axis` is stable across processes, restoring
    # the determinism the docstring already promised.
    axis_offset = int(hashlib.sha256(axis.encode("utf-8")).hexdigest(), 16) % 1000
    rng = random.Random(seed + axis_offset)
    out_dir = Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    # tempdoc 748 §D-2: German gets its own relation + tail pools. Before this, `rels` was
    # unused on the German render path (which emitted one fixed link sentence) and the
    # German tail was a fixed string, so every German gold doc shared one template.
    rels = _RELATIONS_DE if lang == "de" else _RELATIONS["prose"]
    tail_pool = _TAIL_PHRASINGS_DE if lang == "de" else _TAIL_PHRASINGS
    membership_pool = _MEMBERSHIP_PHRASINGS_DE if lang == "de" else _MEMBERSHIP_PHRASINGS
    # Semantic (synonym-bridge) queries are now supported on ALL axes (prose/code/tabular)
    # and both languages — the head doc carries a descriptor and the query references it via
    # zero-overlap synonyms, so grep/pure-BM25 fail and dense must bridge (the only setup where
    # JustSearch's retrieval beats a grep-agent → a real ceiling instead of a trivial nDCG 1.0).
    sem_active = bool(semantic)
    if sem_active:
        # Cap at the (type, place, qualifier) triple-injectivity period, not the place-pool size:
        # the query disambiguates a gold head by the full synonym triple, so uniqueness holds up to
        # lcm(T, P, Q) chains (tempdoc 624 scale-corpus; see `_max_semantic_chains`).
        n_chains = min(n_chains, _max_semantic_chains(lang))
    # The gold-item count that feeds the descriptor-width guard and reservations. In the
    # default path this is `n_chains` (byte-identical); in a schema mix it is the plan length
    # — each gold item (bridge head, single_fact entity, aggregation GROUP) consumes exactly
    # one descriptor index, so `_sem_for(g, gold=True)` for g in range(n_gold_items) matches
    # `_gold_descriptor_reservations(n_gold_items)` exactly, just as the bridge path relies on.
    n_gold_items = len(schema_plan) if schema_plan is not None else n_chains
    # Axis-conditional descriptor width. The qualifier axis is not free: it lengthens every
    # query by a clause, which is what breaks the 5-12-word short-natural stratum
    # (`corpus_query_strata._short_natural`), and it changes retrieval difficulty against
    # every committed 635-era cell. So it is switched on only when something actually needs
    # it — and TWO different things can, for two different reasons. Keep them separate;
    # the pre-2026-07-21 guard (`n_chains > min(T, P)`) was a single condition standing in
    # for both, and was wrong about each.
    #
    #   1. GOLD DESCRIPTOR UNIQUENESS. A gold query names its head by the synonym descriptor,
    #      so no two gold chains may share one. `_sem_for` gives chain g the index pair
    #      (g % T, g % P); T=21 and P=44 are coprime, so by the CRT that pair is injective on
    #      [0, lcm(21, 44)) = [0, 924) — two-axis descriptors are collision-free to 924 gold
    #      chains and first collide at 925 (measured; pinned by
    #      `test_two_axis_pair_bound_is_exactly_924`). `min(T, P)` = 21 was the count of
    #      values on the SHORTER axis, not the count of distinct pairs; as a uniqueness bound
    #      it was over-conservative by 44x.
    #
    #   2. GENERATED-DISTRACTOR DIVERSITY. Distractors draw their pair uniformly from the
    #      pair space minus the gold reservations. Enough draws into that space and many
    #      distractors end up textually interchangeable — a loss of hard-negative diversity,
    #      never a qrel break (gold pairs are excluded from the draw, so `n_gold_involved`
    #      stays 0 in both regimes). At n_chains=26 / distractor_ratio=30 — 780 distractor
    #      chains into 898 free bins — two-axis measures 58.2% duplication against 4.1% with
    #      the qualifier, and by pigeonhole no seed can avoid it. Q=25 multiplies the draw
    #      space and dissolves the problem.
    #
    # They are separate because a caller can have either without the other: 900 gold chains
    # with no generated distractors needs no qualifier, and 26 gold chains with 780 distractor
    # chains needs one. Fusing them made every caller above 21 chains pay the query-length
    # cost for a uniqueness margin it did not need — and, in the injected (707) path, pay it
    # for distractors that `corpus_inject.assemble` discards outright.
    docs_per_chain = hops + 1
    n_distractor_chains = -(
        -int(n_gold_items * docs_per_chain * distractor_ratio) // docs_per_chain
    )
    # The gold pair reservations number exactly min(n_gold_items, 924) by the CRT argument above.
    pair_bound = _max_semantic_pair_chains(lang)
    use_qual = sem_active and (
        n_gold_items > pair_bound
        or _expected_distractor_pair_duplication(
            n_distractor_chains, min(n_gold_items, pair_bound), lang)
        > _DISTRACTOR_PAIR_DUPLICATION_BUDGET
    )
    # tempdoc 624 T.1: the exact (type, place, qualifier) index-triples the gold chains below
    # will occupy, so the distractor draw can EXCLUDE them — a gold/distractor descriptor
    # collision becomes structurally impossible rather than merely detected after the fact.
    # Both regimes exclude gold reservations from the distractor draw (pairs in the
    # two-axis regime, triples in the scale regime) — collisions are structurally
    # impossible; the descriptor-collision certification gate is the belt-and-braces check.
    gold_reserved = (
        _gold_descriptor_reservations(n_gold_items, lang, use_qual=use_qual) if sem_active else None
    )

    def render(e, a, sem, chain_idx):
        # `scan` reuses prose's text generation verbatim -- the axis only changes how a
        # document is later MATERIALIZED (`corpus_build.build_golden` renders each doc's
        # text as a degraded scan-page PNG at build time via `render_scan_page`), not how
        # its ground-truth text is composed. `docs.jsonl` for a scan-axis corpus is
        # therefore identical in shape to a plain prose source (tempdoc 624 §T.2).
        if axis in ("prose", "scan"):
            # tempdoc 767 §I.3: rotate the relation + tail pools by the CHAIN index so no
            # single phrasing dominates the gold set. `_rotate` is rng-free, so this adds
            # no draw to the seeded stream (distractors rotate off their own counter).
            return _render_prose(e, a, _rotate(rels, chain_idx), doc_words, lang, sem=sem,
                                 tails=_rotate(tail_pool, chain_idx))
        if axis == "code":
            return _render_code(e, a, doc_words, rng.randint(0, 999), sem=sem)
        return _render_tabular(e, a, doc_words, rng.randint(0, 999), sem=sem)

    all_docs, queries = [], []
    if schema_plan is None:
        for g in range(n_chains):
            ents, attr = _chain(rng, hops, minter)
            sem = _sem_for(g, rng, gold=True, lang=lang, use_qual=use_qual) if sem_active else None
            docs, q = render(ents, attr, sem, g)
            for did, title, text in docs:
                all_docs.append({"_id": did, "title": title, "text": text})
            queries.append(q)
    else:
        # tempdoc 776 §A.1: multi-schema gold. Each gold item consumes one descriptor index
        # `g` (the paraphrase barrier), minted from the same seeded RNG. Aggregation gold_kinds
        # cycle deterministically so a mix exercises every comparator; sizes cycle over 3-5.
        agg_counter = 0
        for g, kind in enumerate(schema_plan):
            sem = _sem_for(g, rng, gold=True, lang=lang, use_qual=use_qual)
            if kind == "bridge":
                ents, attr = _chain(rng, hops, minter)
                docs, q = _render_prose(ents, attr, _rotate(rels, g), doc_words, lang,
                                        sem=sem, tails=_rotate(tail_pool, g))
                q["gold_kind"] = "single_value"
            elif kind == "single_fact":
                ent = minter.mint_entity(rng)
                attr = minter.mint_value(rng)
                docs, q = _render_single_fact(ent, attr, sem, g, doc_words, lang,
                                              tails=tail_pool)
            else:  # aggregation
                k = _AGGREGATION_SIZES[g % len(_AGGREGATION_SIZES)]
                gold_kind = _AGGREGATION_KINDS[agg_counter % len(_AGGREGATION_KINDS)]
                agg_counter += 1
                members, answer = _aggregation_members(rng, minter, k, gold_kind)
                docs, q = _render_aggregation(members, answer, sem, gold_kind, g, doc_words,
                                              lang, memberships=membership_pool,
                                              tails=tail_pool)
            for did, title, text in docs:
                all_docs.append({"_id": did, "title": title, "text": text})
            queries.append(q)

    # distractors: parallel fabricated chains (globally-unique entities), rendered the
    # same way, NOT referenced by any query → hard negatives. `exclude=gold_reserved` (tempdoc
    # 624 T.1) keeps a distractor's descriptor draw disjoint from every gold chain's, so a
    # distractor can never be textually indistinguishable from the query's actual answer head.
    n_distract = int(len(all_docs) * distractor_ratio)
    made = 0
    # Distractors rotate the phrasing pools off their own chain counter, for the same reason
    # gold chains do: a distractor population that all shares one template is itself a
    # separating signal (the complement of a gold anchor is still an anchor).
    distract_idx = 0
    while made < n_distract:
        ents, attr = _chain(rng, hops, minter)
        sem = _sem_for(0, rng, gold=False, lang=lang, exclude=gold_reserved,
                       use_qual=use_qual) if sem_active else None
        docs, _q = render(ents, attr, sem, distract_idx)
        distract_idx += 1
        for did, title, text in docs:
            if made >= n_distract:
                break
            all_docs.append({"_id": did, "title": title, "text": text})
            made += 1

    # tempdoc 664 (twelfth pass): gold and distractor docs were previously written in two
    # unbroken blocks (all gold first, all distractors after) -- the only real "positional
    # non-uniformity" this generator has (there is no NoLiMa-style in-document depth to vary;
    # each hop is a separate short document, not a buried fact within one long context).
    # Interleaving with the same seeded `rng` (not a fresh Random()) keeps this inside the
    # existing seed-derived determinism chain -- query evidence_ids reference doc _id strings,
    # not positions, so this has no effect on query correctness.
    rng.shuffle(all_docs)

    # newline="\n": 635 gold sources are git-committed (and flow verbatim into the 707
    # fabricated-* commitments) — platform-default CRLF here re-introduces the
    # unmatchable-manifest bake-in the 2026-07-14 repair removed (independent-review find).
    with (out_dir / "docs.jsonl").open("w", encoding="utf-8", newline="\n") as f:
        for d in all_docs:
            f.write(json.dumps(d, ensure_ascii=False) + "\n")
    (out_dir / "queries.json").write_text(
        json.dumps(queries, ensure_ascii=False, indent=1), encoding="utf-8", newline="\n")
    # tempdoc 664 (seventh pass): `n_chains`/`doc_words` were missing here, so a corpus's own
    # recorded provenance could not reconstruct the exact `generate()` call that produced it —
    # a regeneration-determinism check needs the FULL parameter set, not a partial one.
    # tempdoc 767: `payload_version` + the two `entity_bank*` keys complete the
    # parameter set — without the bank's identity and digest the recorded provenance
    # can no longer reconstruct the exact `generate()` call, because the entity
    # surfaces now come from a committed artifact rather than an in-module pool.
    generation_provenance = {"method": "procedural-fabricated", "axis": axis, "lang": lang,
                             "seed": seed, "hops": hops, "distractor_ratio": distractor_ratio,
                             "semantic": sem_active, "templated": True,
                             "n_chains": n_chains, "doc_words": doc_words,
                             "payload_version": PAYLOAD_VERSION,
                             "entity_bank": _entity_bank.bank_reference(bank_root),
                             "entity_bank_sha256": verdict["bank_sha256"]}
    # tempdoc 776 §A.1: `schema_mix` is APPENDED only when a mix was requested, so a default
    # single-schema corpus's provenance bytes are unchanged (the opt-in byte-stability contract).
    if schema_plan is not None:
        # Canonical schema order so two callers with the same counts but different dict
        # insertion order still produce byte-identical meta.json.
        generation_provenance["schema_mix"] = {
            kind: schema_mix[kind] for kind in _SCHEMA_KINDS if kind in schema_mix
        }
    (out_dir / "meta.json").write_text(json.dumps({
        "version": "1.0", "type_axis": axis, "suite": suite,
        "contamination_class": "private-synthetic",
        "generation_provenance": generation_provenance,
    }, ensure_ascii=False, indent=2), encoding="utf-8", newline="\n")
    return {"docs": len(all_docs), "gold_chains": n_gold_items, "queries": len(queries),
            "distractor_docs": made}


def regenerate_and_diff(out1, out2, *, axis, lang, seed, hops, distractor_ratio, semantic,
                         n_chains, doc_words, entity_bank, schema_mix=None, timeout=60) -> dict:
    """Spawn :func:`generate` twice, in two SEPARATE Python processes, into ``out1``/``out2`` with
    identical parameters, then diff ``docs.jsonl``/``queries.json`` (tempdoc 664, twelfth pass).

    Runs in separate processes deliberately: an in-process call would hide any per-process-random
    source (like the original ``hash(axis)`` non-determinism bug this technique found — confirmed
    empirically pre-fix: 280/280 docs differed between two "identical seed" runs) because such
    sources are stable *within* one process.

    Shared by :func:`jseval.corpus_certify.regeneration_determinism_report` (the certification-time
    check) and the pytest regression test with the same name — a single implementation of the
    subprocess-spawn-and-diff technique rather than two independent copies that could drift apart
    (the "projection vs fork" principle this tempdoc is about, applied to test code).

    ``out1``/``out2`` are caller-supplied directories, left populated on return (not cleaned up
    here) so a caller that needs to inspect the generated content afterward — the interleave-order
    regression test — can do so; a caller that only needs the verdict (certification) is expected to
    pass ephemeral `tempfile.TemporaryDirectory()`-backed paths and let its own context manager
    clean up.

    A SECOND cross-process non-determinism source (found empirically: the three "deterministic
    across processes" tests pass when the pytest invocation's cwd happens to be `scripts/jseval/`,
    but fail reliably -- ``docs.jsonl``/``queries.json`` mismatched -- when invoked from the repo
    root, the realistic invocation form). Root cause: `sys.executable -c <script>` gives the child
    `sys.path[0] = ''` (its own cwd), searched *before* site-packages. Without an explicit `cwd`,
    the child inherits the PARENT's cwd. If that cwd doesn't directly contain a `jseval/`
    subdirectory (true for any cwd other than `scripts/jseval/` itself), import resolution falls
    through to whatever `jseval` happens to be `pip install -e`'d globally -- which, on a dev
    machine with a separate long-lived checkout on `PATH`/site-packages (`pip show jseval` ->
    `Editable project location: ...`), can be a *different, stale physical checkout* containing the
    pre-tempdoc-664 `hash(axis)` bug. Both spawned subprocesses then run that stale buggy code
    (not the current worktree's, already-fixed `corpus_generate.py`), and since `hash()` is
    per-process-randomized, they diverge -- reproducing the exact pre-664 symptom despite this
    file's own fix. Pinning `cwd` to this file's OWN package directory (`Path(__file__).parent`'s
    parent -- the dir that directly contains `jseval/`) makes the child's `sys.path[0]` shadow any
    ambient site-packages install, guaranteeing the subprocess imports the SAME code this function
    itself is running from, regardless of the caller's cwd.

    :returns: ``{"ok": True, "mismatched_files": [...]}`` or ``{"ok": False, "error": "..."}`` if a
      regeneration subprocess itself failed (not a mismatch — a hard error, e.g. bad parameters).
    """
    # tempdoc 767: parameters are marshalled as ONE json argv element rather than a
    # by-name-positionally-decoded argv list. The old form decoded `int(sys.argv[9])` for
    # doc_words, which cannot express the `None` (do-not-pad) value the 707 corpora now
    # use, and silently re-typed anything it did not parse. The out_dir stays argv[1]
    # (cmd index 3), which is what the mocked-subprocess test keys on.
    script = (
        "import json; from jseval import corpus_generate as cg; "
        "cg.generate(sys.argv[1], **json.loads(sys.argv[2]))"
    )
    params = {
        "axis": axis, "lang": lang, "seed": seed, "hops": hops,
        "distractor_ratio": distractor_ratio, "semantic": bool(semantic),
        "n_chains": n_chains, "doc_words": doc_words, "entity_bank": str(entity_bank),
    }
    # tempdoc 776 §A.1: `schema_mix` is added to the marshalled params only for a mix, so a
    # default single-schema regeneration's subprocess argv stays byte-identical.
    if schema_mix is not None:
        params["schema_mix"] = schema_mix
    args = [json.dumps(params, sort_keys=True)]
    # The directory that directly contains `jseval/` (this file is
    # `<pkg_root>/jseval/corpus_generate.py`) -- passed as the subprocess's `cwd` so its
    # `sys.path[0] = ''` resolves to THIS package, not an ambient site-packages install.
    pkg_root = Path(__file__).resolve().parent.parent

    for out in (Path(out1), Path(out2)):
        result = subprocess.run(
            [sys.executable, "-c", "import sys; " + script, str(out), *args],
            capture_output=True, text=True, timeout=timeout, cwd=pkg_root,
        )
        if result.returncode != 0:
            return {"ok": False, "error": f"regeneration subprocess failed: {result.stderr[-500:]}"}

    mismatches = [
        fname for fname in ("docs.jsonl", "queries.json")
        if (Path(out1) / fname).read_text(encoding="utf-8") != (Path(out2) / fname).read_text(encoding="utf-8")
    ]
    return {"ok": True, "mismatched_files": mismatches}
