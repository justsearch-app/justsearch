import assert from "node:assert/strict";
import { randomBytes, createHash } from "node:crypto";
import { execFileSync, spawnSync } from "node:child_process";
import { existsSync, mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { tmpdir } from "node:os";

const scriptDir = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(scriptDir, "../..");
const configPath = join(repoRoot, ".gitleaks.toml");
const gitleaks = process.env.GITLEAKS_PATH || "gitleaks";

assert.equal(existsSync(configPath), true, "the checked-in Gitleaks config must exist");

const alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
function alpha(length) {
  const bytes = randomBytes(length);
  return Array.from(bytes, (byte) => alphabet[byte % alphabet.length]).join("");
}

function hex(length) {
  return createHash("sha256").update(`${randomBytes(24).toString("hex")}:${length}`).digest("hex").slice(0, length);
}

function vettedEntityBankMatch() {
  const entityBank = readFileSync(
    join(repoRoot, "scripts/jseval/707-corpora/en-legal-clerc/entity-bank/entity-bank.v2.json"),
    "utf8",
  );
  const match = entityBank.match(/ghp_[A-Za-z0-9]{36}/);
  assert.ok(match, "the reviewed entity-bank collision chunk must still have its known detector match");
  return match[0];
}

function scan(files) {
  const root = mkdtempSync(join(tmpdir(), "justsearch-gitleaks-"));
  try {
    for (const [relativePath, content] of Object.entries(files)) {
      const target = join(root, relativePath);
      mkdirSync(dirname(target), { recursive: true });
      writeFileSync(target, content, "utf8");
    }

    const reportPath = join(root, "scan-report.json");
    const result = spawnSync(
      gitleaks,
      ["dir", ".", "--config", configPath, "--no-banner", "--redact", "--report-format", "json", "--report-path", reportPath],
      { cwd: root, encoding: "utf8", windowsHide: true },
    );
    assert.equal(result.error, undefined, `could not execute Gitleaks (${gitleaks})`);
    assert.ok(result.status === 0 || result.status === 1, `Gitleaks exited unexpectedly: ${result.status}`);
    const findings = existsSync(reportPath) ? JSON.parse(readFileSync(reportPath, "utf8")) : [];
    return findings;
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
}

function expectClean(name, files) {
  const findings = scan(files);
  assert.deepEqual(findings, [], `${name} should be allowlisted by its exact rule/path/context`);
}

function expectFinding(name, files, rule) {
  const findings = scan(files);
  assert.ok(findings.length > 0, `${name} must remain detector-positive`);
  if (rule) assert.ok(findings.some((finding) => finding.RuleID === rule), `${name} must hit ${rule}`);
}

const cohortDigest = hex(64);
expectClean("generated cohort digest", {
  "scripts/jseval/calibration-fixture.json": `{ "agent_cohort_key": "${cohortDigest}" }\n`,
});
expectFinding("unrelated key beside a cohort digest", {
  "scripts/jseval/calibration-fixture.json": `{ "agent_cohort_key": "${cohortDigest}", "api_key": "${hex(64)}" }\n`,
}, "generic-api-key");
expectFinding("cohort-shaped data outside generated artifact path", {
  "scripts/jseval/other-fixture.json": `{ "agent_cohort_key": "${cohortDigest}" }\n`,
}, "generic-api-key");

const sourceRevision = hex(40);
const secondSourceRevision = hex(40);
expectClean("generated git revision", {
  "scripts/jseval/fixture.utility-comparison.v1.json": `{ "generator": "sourcegraph" }\n    "git_sha": "${sourceRevision}",\n`,
});
expectFinding("git revision field outside generated artifact path", {
  "scripts/jseval/other-fixture.json": `{ "generator": "sourcegraph" }\n    "git_sha": "${sourceRevision}",\n`,
}, "sourcegraph-access-token");
expectFinding("second source revision beside a git revision", {
  "scripts/jseval/fixture.utility-comparison.v1.json": `{ "generator": "sourcegraph" }\n    "git_sha": "${sourceRevision}", "other_sha": "${secondSourceRevision}"\n`,
}, "sourcegraph-access-token");

const entityMatch = vettedEntityBankMatch();
expectClean("reviewed entity-bank collision chunk", {
  "scripts/jseval/707-corpora/en-legal-clerc/entity-bank/entity-bank.v2.json": `["${entityMatch}"]\n`,
});
expectFinding("different PAT in the reviewed entity-bank file", {
  "scripts/jseval/707-corpora/en-legal-clerc/entity-bank/entity-bank.v2.json": `["${entityMatch}", "ghp_${alpha(36)}"]\n`,
}, "github-pat");

const resumeToken = Buffer.from("surface:health-events:42", "utf8").toString("base64");
expectClean("streaming envelope example", {
  "docs/reference/ui/frontend-kernel/kernel/05-streaming-envelope.md": `  "resumeToken": "${resumeToken}"\n`,
});
expectFinding("unrelated key beside the streaming example", {
  "docs/reference/ui/frontend-kernel/kernel/05-streaming-envelope.md": `  "resumeToken": "${resumeToken}", "api_key": "${hex(64)}"\n`,
}, "generic-api-key");
expectFinding("credential elsewhere in the streaming document", {
  "docs/reference/ui/frontend-kernel/kernel/05-streaming-envelope.md": `  "api_key": "${hex(64)}"\n`,
}, "generic-api-key");

const auditTitle = readFileSync(join(repoRoot, "docs/tempdocs/586-frontend-live-uiux-audit.md"), "utf8").split(/\r?\n/)[1];
expectClean("fixed audit title wording", {
  "docs/tempdocs/586-frontend-live-uiux-audit.md": `${auditTitle}\n`,
});
expectFinding("unrelated key beside the fixed audit title", {
  "docs/tempdocs/586-frontend-live-uiux-audit.md": `${auditTitle}, "api_key": "${hex(64)}"\n`,
}, "generic-api-key");

const storeCipherLine = readFileSync(
  join(repoRoot, "modules/app-agent-api/src/test/java/io/justsearch/agent/api/encryption/StoreCipherTest.java"),
  "utf8",
).split(/\r?\n/)[12];
expectClean("fixed StoreCipherTest demo key", {
  "modules/app-agent-api/src/test/java/io/justsearch/agent/api/encryption/StoreCipherTest.java": `${storeCipherLine}\n`,
});
expectFinding("credential elsewhere in StoreCipherTest", {
  "modules/app-agent-api/src/test/java/io/justsearch/agent/api/encryption/StoreCipherTest.java": `${storeCipherLine}\nprivate static final String api_key = "${hex(64)}";\n`,
}, "generic-api-key");

const validationIdentifier = ["valid", "I18n", "Keys"].join("");
const validationLine = [
  "    ",
  validationIdentifier,
  " = ",
  validationIdentifier,
  " == null ? Set.of() : Set.copyOf(",
  validationIdentifier,
  ");",
].join("");
expectClean("ValidationContext identifier", {
  "modules/app-services/src/main/java/io/justsearch/app/services/registry/validator/ValidationContext.java": `${validationLine}\n`,
});
expectFinding("credential elsewhere in ValidationContext", {
  "modules/app-services/src/main/java/io/justsearch/app/services/registry/validator/ValidationContext.java": `${validationLine}\n    String api_key = "${hex(64)}";\n`,
}, "generic-api-key");

const indexFixture = [
  "    return {\"",
  "key",
  "\": \"abc123def456\", \"components\": {\"git_sha\": \"deadbeef\", \"git_dirty\": False}}",
].join("");
const selectorFixture = ["        pin_", "selector_", "key=\"PINNED-KEY-abc123\","].join("");
expectClean("jseval index-cache fixtures", {
  "scripts/jseval/tests/test_index_cache.py": `${indexFixture}\n${selectorFixture}\n`,
});
expectFinding("credential beside an index-cache fixture", {
  "scripts/jseval/tests/test_index_cache.py": `${indexFixture}\n    api_key = "${hex(64)}"\n`,
}, "generic-api-key");
expectFinding("credential in another jseval test", {
  "scripts/jseval/tests/other_fixture.py": `api_key = "${hex(64)}"\n`,
}, "generic-api-key");

expectFinding("credential in a dataset", {
  "datasets/fixture.json": `{ "api_key": "${hex(64)}" }\n`,
}, "generic-api-key");
expectFinding("credential in a report", {
  "reports/fixture.json": `{ "api_key": "${hex(64)}" }\n`,
}, "generic-api-key");

for (const extension of ["java", "ts", "tsx"]) {
  expectFinding(`credential in a ${extension} test`, {
    [`modules/example/OtherTest.${extension}`]: `api_key = "${hex(64)}"\n`,
  }, "generic-api-key");
}

// Historical exceptions must carry an immutable commit, never just a path.
const historyIgnore = readFileSync(join(repoRoot, "scripts/ci/gitleaks-history.ignore"), "utf8")
  .split(/\r?\n/).filter((line) => line && !line.startsWith("#"));
assert.ok(historyIgnore.length > 0);
assert.equal(new Set(historyIgnore).size, historyIgnore.length);
for (const fingerprint of historyIgnore) {
  assert.match(fingerprint, /^[a-f0-9]{40}:[^\r\n]+:(?:generic-api-key|sourcegraph-access-token):\d+$/);
}

const historyRoot = mkdtempSync(join(tmpdir(), "justsearch-gitleaks-history-"));
try {
  const git = (...args) => execFileSync("git", args, {
    cwd: historyRoot, encoding: "utf8", stdio: ["ignore", "pipe", "pipe"], windowsHide: true,
  });
  git("init", "-q");
  git("config", "core.hooksPath", join(historyRoot, "disabled-hooks"));
  git("config", "commit.gpgSign", "false");
  git("config", "user.name", "Scanner Fixture");
  git("config", "user.email", "scanner@example.invalid");
  const scanHistory = (ignorePath) => {
    const reportPath = join(historyRoot, "result.json");
    const result = spawnSync(gitleaks, ["git", ".", "--config", configPath, "--redact", "--no-banner",
      "--log-opts=HEAD", "--report-format", "json", "--report-path", reportPath,
      ...(ignorePath ? ["--gitleaks-ignore-path", ignorePath] : [])],
    { cwd: historyRoot, encoding: "utf8", windowsHide: true });
    assert.equal(result.error, undefined);
    assert.ok(result.status === 0 || result.status === 1);
    return JSON.parse(readFileSync(reportPath, "utf8"));
  };
  writeFileSync(join(historyRoot, "fixture.txt"), `api_key = "${hex(64)}"\n`);
  git("add", "fixture.txt");
  git("commit", "-qm", "historical fixture");
  const oldFindings = scanHistory();
  assert.ok(oldFindings.some((finding) => finding.RuleID === "generic-api-key"));
  const ignorePath = join(historyRoot, "reviewed.ignore");
  writeFileSync(ignorePath, oldFindings.map((finding) => finding.Fingerprint).join("\n") + "\n");
  assert.deepEqual(scanHistory(ignorePath), [], "exact historical fingerprints suppress reviewed old findings");
  writeFileSync(join(historyRoot, "fixture.txt"), `api_key = "${hex(64)}"\n`);
  git("add", "fixture.txt");
  git("commit", "-qm", "new detector positive at the same path and line");
  const newHead = git("rev-parse", "HEAD").trim();
  assert.ok(scanHistory(ignorePath).some((finding) => finding.Commit === newHead),
    "historical fingerprint must not suppress a new commit at the same path, rule, and line");
} finally {
  rmSync(historyRoot, { recursive: true, force: true });
}

console.log("test-gitleaks-config: PASS (pinned detector positives, exact exceptions, and negative controls)");
