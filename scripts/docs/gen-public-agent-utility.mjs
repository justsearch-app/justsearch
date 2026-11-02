/**
 * Project the selected immutable agent-utility publication into public docs.
 *
 * The pointer is the only mutable selection surface. Accepted numbers are read
 * from its hash-pinned manifest and canonical record; a null pointer renders an
 * explicit no-result statement. Never edit generated regions by hand.
 */

import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";

const START =
  "<!-- agent-utility:generated:start - run: node scripts/docs/gen-public-agent-utility.mjs -->";
const END = "<!-- agent-utility:generated:end -->";

function repoRoot() {
  for (let dir = process.cwd(); ; dir = path.dirname(dir)) {
    if (fs.existsSync(path.join(dir, "settings.gradle.kts"))) return dir;
    if (path.dirname(dir) === dir) throw new Error("cannot locate repository root");
  }
}

function readJson(file) {
  return JSON.parse(fs.readFileSync(file, "utf8"));
}

function sha256(file) {
  return crypto.createHash("sha256").update(fs.readFileSync(file)).digest("hex");
}

/**
 * The one ACTIVE claim policy, selected by its own `status` rather than by
 * filename. Hardcoding a version-numbered filename is the defect this file
 * already fixed once for the policy ID: the name silently goes stale at the next
 * ratification and the page then quotes a superseded policy. The rendered
 * sentence asserts "is active and fully resolved", so both facts are verified
 * here and generation FAILS rather than printing a false claim.
 */
function activeClaimPolicy(root) {
  const dir = path.join(root, "scripts", "jseval");
  const candidates = fs
    .readdirSync(dir)
    .filter((name) => /^utility-claim-policy\..*\.json$/.test(name) && !name.endsWith(".schema.json"))
    .map((name) => ({ name, policy: readJson(path.join(dir, name)) }))
    .filter(({ policy }) => policy.status === "active");
  if (candidates.length !== 1) {
    throw new Error(
      `expected exactly one active claim policy, found ${candidates.length}` +
        `${candidates.length ? `: ${candidates.map((c) => c.name).join(", ")}` : ""}`,
    );
  }
  const { name, policy } = candidates[0];
  if ((policy.unresolved || []).length !== 0) {
    throw new Error(
      `active claim policy ${name} has unresolved items, cannot render it as fully resolved: ` +
        `${policy.unresolved.join("; ")}`,
    );
  }
  return policy;
}

function loadSelection(root) {
  const pointerPath = path.join(root, "scripts", "jseval", "public-agent-utility", "current.v1.json");
  const pointer = readJson(pointerPath);
  if (pointer.schema !== "agent-utility-publication-pointer.v1" || !("current" in pointer)) {
    throw new Error("malformed agent-utility publication pointer");
  }
  if (pointer.current === null) return { pointer, manifest: null, record: null };

  const manifestPath = path.resolve(path.dirname(pointerPath), pointer.current.path);
  if (sha256(manifestPath) !== pointer.current.manifest_sha256) {
    throw new Error("selected agent-utility manifest hash mismatch");
  }
  const manifest = readJson(manifestPath);
  if (
    manifest.schema !== "agent-utility-publication.v1" ||
    manifest.publication_id !== pointer.current.publication_id ||
    manifest.lifecycle_state !== "accepted"
  ) {
    throw new Error("pointer must select the matching accepted publication");
  }
  const recordPath = path.resolve(path.dirname(manifestPath), manifest.record.path);
  if (sha256(recordPath) !== manifest.record.sha256) {
    throw new Error("selected agent-utility record hash mismatch");
  }
  const record = readJson(recordPath);
  const policyPath = path.resolve(path.dirname(manifestPath), manifest.policy.path);
  if (sha256(policyPath) !== manifest.policy.sha256) {
    throw new Error("selected agent-utility policy hash mismatch");
  }
  if (
    record.semantic_digest !== manifest.record.semantic_digest ||
    record.claim_verdict?.accepted !== true
  ) {
    throw new Error("selected record lacks the accepted, hash-pinned claim verdict");
  }
  const observationsPath = path.resolve(path.dirname(manifestPath), manifest.observations.path);
  if (sha256(observationsPath) !== manifest.observations.sha256) {
    throw new Error("selected agent-utility observation evidence hash mismatch");
  }
  const projectionPath = path.join(root, "scripts/jseval/public-agent-utility/question-level.v1.json");
  let projection = null;
  if (fs.existsSync(projectionPath)) {
    projection = readJson(projectionPath);
    const policy = activeClaimPolicy(root);
    const policyPath = path.join(root, "scripts/jseval/utility-claim-policy.v5.json");
    if (projection.schema !== "agent-utility-question-level-projection.v1" ||
        projection.publication_id !== manifest.publication_id ||
        projection.record_sha256 !== manifest.record.sha256 ||
        projection.observations_sha256 !== manifest.observations.sha256 ||
        projection.policy_id !== policy.policy_id ||
        projection.policy_sha256 !== sha256(policyPath) ||
        projection.outcome !== record.claim_verdict.outcome) {
      throw new Error("question-level projection does not match the selected publication and active policy");
    }
    const byId = new Map(projection.strata.map((cell) => [cell.stratum_id, cell.question_level]));
    const strata = record.estimands.intention_to_treat.strata;
    if (byId.size !== strata.length) throw new Error("question-level projection has different strata");
    for (const cell of strata) {
      const stats = byId.get(cell.stratum_id);
      if (!stats || stats.unit !== "qid" || stats.n_paired_cells !== cell.n_paired_observations ||
          stats.n_qids !== cell.query_count ||
          Math.abs(stats.aggregate_delta - cell.accuracy.delta) > 0.000051 ||
          !Array.isArray(stats.delta_ci) || stats.delta_ci.length !== 2) {
        throw new Error(`question-level projection changes the paired set or estimate: ${cell.stratum_id}`);
      }
      cell.question_level = stats;
    }
  }
  return { pointer, manifest, record, projection };
}

function cells(record) {
  const outcomes = record.claim_verdict?.stratum_outcomes || [];
  const byId = new Map(outcomes.map((item) => [item.stratum_id, item.outcome]));
  const out = (record.estimands?.intention_to_treat?.strata || []).map((cell) => {
    const outcome = byId.get(cell.stratum_id);
    if (!outcome) throw new Error(`accepted record lacks an outcome for stratum ${cell.stratum_id}`);
    return { cell, outcome };
  });
  return out.sort((a, b) => a.cell.stratum_id.localeCompare(b.cell.stratum_id));
}

const OUTCOME_COPY = {
  benefit: "Adding JustSearch produced a policy-qualified utility benefit in every required stratum.",
  harm: "Adding JustSearch produced material harm in at least one required stratum.",
  null: "The conditions were equivalent within the pre-registered accuracy and efficiency margins.",
  "adoption-only": "Agents adopted JustSearch, but the campaign did not establish an efficiency or accuracy improvement across every required stratum.",
};

function outcomeCopy(outcome) {
  const copy = OUTCOME_COPY[outcome];
  if (!copy) throw new Error(`accepted record has unsupported outcome ${outcome}`);
  return copy;
}

function number(value, digits = 1) {
  if (typeof value !== "number" || !Number.isFinite(value)) return "n/a";
  return value.toFixed(digits);
}

function signed(value, digits = 1, suffix = "") {
  if (typeof value !== "number" || !Number.isFinite(value)) return "n/a";
  return `${value >= 0 ? "+" : ""}${value.toFixed(digits)}${suffix}`;
}

function percentage(value) {
  return typeof value === "number" && Number.isFinite(value)
    ? `${(value * 100).toFixed(1)}%`
    : "n/a";
}

function accuracyDelta(value) {
  return typeof value === "number" && Number.isFinite(value)
    ? signed(value * 100, 1, " pp")
    : "n/a";
}

function accuracyBlock(cell) {
  return cell.question_level ? { ...cell.accuracy, delta_ci: cell.question_level.delta_ci } : cell.accuracy;
}

function usd(value, signedValue = false) {
  if (typeof value !== "number" || !Number.isFinite(value)) return "n/a";
  const prefix = value < 0 ? "-" : signedValue ? "+" : "";
  return `${prefix}$${Math.abs(value).toFixed(6)}`;
}

function interval(block, formatter) {
  const values = block?.delta_ci || block?.delta_ci95;
  if (!Array.isArray(values) || values.length !== 2) return "CI unavailable";
  return `CI ${formatter(values[0])} to ${formatter(values[1])}`;
}

function deltaWithInterval(block, key, formatter) {
  return `${formatter(block?.[key])} (${interval(block, formatter)})`;
}

function markdown(value) {
  return String(value ?? "n/a").replaceAll("|", "\\|").replaceAll("\n", " ");
}

function stratumLabel(cell) {
  return [cell.corpus_member, cell.corpus, cell.corpus_size, cell.query_variant]
    .filter((value) => value !== null && value !== undefined && value !== "")
    .map(markdown)
    .join(" / ");
}

function researchTable(record) {
  return [
    "| Corpus stratum | Agent model | Outcome | Provider cache-creation token delta | Accuracy delta | Adoption | Paired n |",
    "|---|---|---|---:|---:|---:|---:|",
    ...cells(record).map(({ cell, outcome }) => {
      const tokens = deltaWithInterval(
        cell.provider_cache_creation_input_tokens, "delta_mean", (value) => signed(value, 1),
      );
      const accuracy = deltaWithInterval(accuracyBlock(cell), "delta", accuracyDelta);
      const adoption = percentage(cell.adoption?.with_tool?.adoption_rate);
      return `| ${stratumLabel(cell)} | ${markdown(cell.model)} | ${outcome} | ${tokens} | ${accuracy} | ${adoption} | ${cell.n_paired_observations} |`;
    }),
  ].join("\n");
}

function distribution(block, formatter) {
  if (!block || block.available === false) return markdown(block?.reason || "unavailable");
  return `mean ${formatter(block.mean)}; median ${formatter(block.median)}; p95 ${formatter(block.p95)}; n=${block.n ?? "n/a"}`;
}

function metricTable(cell) {
  const tokens = cell.provider_cache_creation_input_tokens || {};
  const cost = cell.cost_usd || {};
  return [
    "| Measure | Condition A | Condition B (with JustSearch) | Paired delta and interval |",
    "|---|---:|---:|---:|",
    `| Accuracy | ${percentage(cell.accuracy?.baseline)} | ${percentage(cell.accuracy?.with_tool)} | ${deltaWithInterval(accuracyBlock(cell), "delta", accuracyDelta)} |`,
    `| Provider cache-creation input tokens | ${distribution(tokens.baseline, (value) => number(value, 1))} | ${distribution(tokens.with_tool, (value) => number(value, 1))} | ${deltaWithInterval(tokens, "delta_mean", (value) => signed(value, 1))} |`,
    `| Cost (USD) | ${distribution(cost.baseline, usd)} | ${distribution(cost.with_tool, usd)} | ${deltaWithInterval(cost, "delta_mean", (value) => usd(value, true))} |`,
  ].join("\n");
}

function lossTable(cell) {
  const loss = cell.per_arm_loss || {};
  return [
    "| Condition | Expected | Attempted | Completed | Excluded | Pending | Exclusion rate |",
    "|---|---:|---:|---:|---:|---:|---:|",
    ...["A", "B"].map((condition) => {
      const arm = loss[condition] || {};
      return `| ${condition} | ${arm.n_expected ?? "n/a"} | ${arm.n_attempted ?? "n/a"} | ${arm.n_completed ?? "n/a"} | ${arm.n_excluded ?? "n/a"} | ${arm.n_pending ?? "n/a"} | ${percentage(arm.exclusion_rate)} |`;
    }),
  ].join("\n");
}

function certificationTable(cell) {
  const certification = cell.corpus_certification || {};
  const gates = Object.entries(certification.scientific_gate_sha256 || {})
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([gate, digest]) => `${gate}=\`${digest}\``)
    .join("; ") || "n/a";
  return [
    "| Certification identity | Value |",
    "|---|---|",
    `| Member / dataset / size / query variant | ${markdown(certification.member)} / ${markdown(certification.dataset)} / ${certification.size ?? "n/a"} / ${markdown(certification.query_variant)} |`,
    `| Corpus signature | \`${certification.corpus_signature ?? "n/a"}\` |`,
    `| Certification SHA-256 | \`${certification.certification_sha256 ?? "n/a"}\` |`,
    `| Fully certified | ${certification.fully_certified === true ? "yes" : "no"} |`,
    `| Scientific gate evidence | ${gates} |`,
  ].join("\n");
}

function referenceStratum(cell, outcome) {
  const adopted = cell.adoption?.with_tool || {};
  return [
    `### ${stratumLabel(cell)} / ${markdown(cell.model)}`,
    "",
    `Stratum outcome: **${outcome}**. Adoption was ${percentage(adopted.adoption_rate)} ` +
      `(${adopted.adopted_cells ?? "n/a"}/${adopted.eligible_cells ?? "n/a"} eligible cells).`,
    "",
    metricTable(cell),
    ...(cell.question_level ? ["", `Accuracy interval: question-cluster ${cell.question_level.ci_method}, ` +
      `${cell.question_level.n_qids} questions; paired sign-flip p=${number(cell.question_level.signflip_p_two_sided, 4)}.`] : []),
    "",
    `Seeds: ${cell.seed_count ?? "n/a"} (${(cell.seed_ids || []).map(markdown).join(", ") || "n/a"}); ` +
      `queries: ${cell.query_count ?? "n/a"}; expected/observed cells: ` +
      `${cell.n_expected_cells ?? "n/a"}/${cell.n_observed_cells ?? "n/a"}; ITT/per-protocol paired observations: ` +
      `${cell.n_paired_observations ?? "n/a"}/${cell.n_per_protocol_pairs ?? "n/a"}; ` +
      `paired retention: ${percentage(cell.paired_retention)}.`,
    "",
    lossTable(cell),
    "",
    certificationTable(cell),
  ].join("\n");
}

function noResult(selection, target, root) {
  const reason = selection.pointer.reason;
  const state = selection.pointer.previous ? "The previously selected result was withdrawn. " : "";
  const policy = activeClaimPolicy(root);
  const policyId = policy.policy_id;
  const strataCount = (policy.required_strata || []).length;
  const common =
    `No agent-utility result is currently accepted for publication. ${state}${reason} ` +
    `The checked-in claim policy (\`${policyId}\`) is active and fully resolved: it pins a required ${strataCount}-stratum ` +
    "campaign matrix, a model cohort, and its scientific margins. " +
    "One pre-registered confirmatory campaign has run against an earlier policy revision (2026-07-18); it rejected promotion on " +
    "identity-verification gates, and the complete evidence — including both voided runs — is committed under " +
    "`scripts/jseval/624-run-2026-07-18-confirmatory/`. " +
    "Owner decisions, certifications, and any paid rerun require separate authorization; the harness does not invent them.";
  if (target === "readme") return common;
  if (target === "research") {
    return [
      common,
      "",
      "The latest rejected campaign record (with its policy-evaluated verdict and per-gate reasons) lives in the " +
        "evidence directory above; earlier sanitized pilot evidence is retained as a rejected fixture and can be " +
        "recomposed without credentials, a backend, or model calls. A result can appear here only after an immutable " +
        "bundle replays, passes the settled policy, and is explicitly selected by the owner.",
      "",
      "```bash",
      "cd scripts/jseval",
      "python -m jseval utility-recompose --evidence tests/fixtures/agent-utility-rejected-2026-07-12/observations.v1.jsonl --output-dir out",
      "python -m jseval utility-replay --publication <publication-id>",
      "```",
    ].join("\n");
  }
  return [
    common,
    "",
    "The publication chain is: all attempted Inspect cells -> strict sanitized observation evidence -> pure offline " +
      "recomposition -> versioned claim-policy verdict -> immutable accepted publication manifest -> explicit accepted-result pointer. " +
      "Rejected evidence remains a test/history fixture rather than a publication bundle. The pointer is " +
      "`scripts/jseval/public-agent-utility/current.v1.json`.",
    "",
    "Replay uses only committed evidence:",
    "",
    "```bash",
    "cd scripts/jseval",
    "python -m jseval utility-replay --publication <publication-id>",
    "```",
  ].join("\n");
}

function accepted(selection, target) {
  const { pointer, manifest, record } = selection;
  const outcome = record.claim_verdict.outcome;
  const outcomeStatement = outcomeCopy(outcome);
  const identity =
    `Accepted publication \`${manifest.publication_id}\` (record ` +
    `\`${record.semantic_digest.slice(0, 12)}\`, policy \`${manifest.policy.id}\`).`;
  const recomputed = selection.projection ?
    "Accuracy intervals are recomputed from the published observations under v5 question-level semantics " +
    "(95% question-cluster bootstrap; seeds are replicates within questions). Point estimates and the " +
    "historical adoption-only outcome are unchanged. Token and cost intervals remain descriptive cell-level intervals. " +
    "Provenance: `scripts/jseval/public-agent-utility/question-level.v1.json`; reproduce with " +
    "`python -m jseval.utility_public_projection --check`. The hash-pinned v4 bundle is unchanged." : "";
  if (target === "readme") {
    return `${identity} ${outcomeStatement}\n\n` +
      (recomputed ? `${recomputed}\n\n` : "") +
      "[See the per-stratum evidence, provenance, and limitations](docs/reference/benchmarks/agent-utility.md).";
  }
  if (target === "research") {
    return [
      `${identity} ${outcomeStatement}`,
      "",
      ...(recomputed ? [recomputed, ""] : []),
      researchTable(record),
      "",
      "Provider cache-creation input tokens exclude cache reads and retain the provider-specific meaning of that counter. " +
        "See the [agent-utility benchmark reference](docs/reference/benchmarks/agent-utility.md) for arm distributions, cost, loss, certification, and replay evidence.",
    ].join("\n");
  }
  const manifestPath = `scripts/jseval/public-agent-utility/${pointer.current.path}`;
  const bundlePath = path.posix.dirname(manifestPath);
  const evidencePath = path.posix.normalize(path.posix.join(bundlePath, manifest.observations.path));
  const recordPath = path.posix.normalize(path.posix.join(bundlePath, manifest.record.path));
  const policyPath = path.posix.normalize(path.posix.join(bundlePath, manifest.policy.path));
  return [
    `${identity} ${outcomeStatement}`,
    "",
    ...(recomputed ? [recomputed, ""] : []),
    ...cells(record).flatMap(({ cell, outcome: stratumOutcome }) => [
      referenceStratum(cell, stratumOutcome), "",
    ]),
    "### Immutable evidence and replay",
    "",
    `- Publication manifest: \`${manifestPath}\``,
    `- Canonical record: \`${recordPath}\``,
    `- Sanitized observation evidence: \`${evidencePath}\``,
    `- Captured policy: \`${policyPath}\``,
    `- Replay: \`${manifest.replay_command}\``,
  ].join("\n");
}

function generated(selection, target, root) {
  return [START, "", selection.record ? accepted(selection, target) : noResult(selection, target, root), "", END].join("\n");
}

/**
 * Locate the marker region and compute the projected status for one target — read-only, no writes.
 * Statuses: "missing" (file does not exist), "no-markers" (markers absent), "in-sync" (byte-identical),
 * "drift" (differs — not yet written).
 */
function evaluate(file, body, root) {
  const rel = path.relative(root, file).replaceAll("\\", "/");
  if (!fs.existsSync(file)) return { rel, file, status: "missing" };
  const text = fs.readFileSync(file, "utf8");
  const start = text.indexOf(START);
  const end = text.indexOf(END);
  if (start < 0 || end < start) return { rel, file, status: "no-markers" };
  const next = text.slice(0, start) + body + text.slice(end + END.length);
  if (next === text) return { rel, file, status: "in-sync" };
  return { rel, file, status: "drift", next };
}

function apply(result) {
  fs.writeFileSync(result.file, result.next);
  return { ...result, status: "updated" };
}

function main() {
  const root = repoRoot();
  const check = process.argv.includes("--check");
  const selection = loadSelection(root);
  const targets = [
    ["README.md", "readme"],
    ["RESEARCH.md", "research"],
    [path.join("docs", "reference", "benchmarks", "agent-utility.md"), "reference"],
  ];

  let results = targets.map(([relative, target]) =>
    evaluate(path.join(root, relative), generated(selection, target, root), root),
  );

  // A target lacking the marker region blocks every write this run, so a later failing target can
  // never leave an earlier target already rewritten (two-phase: evaluate all, then write all).
  const blocked = results.some((r) => r.status === "missing" || r.status === "no-markers");
  if (!check && !blocked) {
    results = results.map((r) => (r.status === "drift" ? apply(r) : r));
  }

  let failed = false;
  for (const r of results) {
    if (r.status === "missing" || r.status === "no-markers") {
      console.log(`gen-public-agent-utility: FAIL ${r.rel} — ${r.status} (target lacks the marker region)`);
      failed = true;
    } else if (r.status === "drift") {
      const remedy = check ? "run without --check to regenerate" : "blocked by another target lacking its marker region";
      console.log(`gen-public-agent-utility: DRIFT ${r.rel} — projection is stale; ${remedy}`);
      failed = true;
    } else if (r.status === "in-sync") {
      console.log(`gen-public-agent-utility: ${r.rel} - in-sync`);
    } else if (r.status === "updated") {
      console.log(`gen-public-agent-utility: ${r.rel} - updated`);
    }
  }

  if (failed) {
    process.exitCode = 1;
    return;
  }
  console.log(`gen-public-agent-utility: OK${check ? " (in sync)" : ""}`);
}

main();
