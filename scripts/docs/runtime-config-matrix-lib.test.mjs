import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";

import {
  buildMatrixModel,
  censusJavaEnumConstants,
  parseConfigKeys,
  parseEnvRegistry,
  parseYamlContributions,
  renderMatrixMarkdown,
} from "./runtime-config-matrix-lib.mjs";

function withFixture(run) {
  const root = mkdtempSync(path.join(tmpdir(), "runtime-config-matrix-"));
  try {
    const envRegistryPath = path.join(root, "EnvRegistry.java");
    const configKeyPath = path.join(root, "ConfigKey.java");
    const builderPath = path.join(root, "ResolvedConfigBuilder.java");
    const configApplyPath = path.join(root, "config-apply.v1.json");
    writeFileSync(configApplyPath, JSON.stringify({ entries: [{ key: "justsearch.normal", applyScope: "hot" }] }));
    writeFileSync(
      envRegistryPath,
      `enum EnvRegistry {
        NORMAL("justsearch.normal", "JUSTSEARCH_NORMAL", LifecycleStage.PERMANENT),
        WITH_DEFAULT("justsearch.defaulted", "JUSTSEARCH_DEFAULTED", "true", LifecycleStage.PERMANENT),
        EXPERIMENT("justsearch.experiment", "JUSTSEARCH_EXPERIMENT", LifecycleStage.EXPERIMENTAL),
        RETIRING("justsearch.retiring", "JUSTSEARCH_RETIRING", "old", LifecycleStage.DEPRECATED);
      }`,
    );
    writeFileSync(
      configKeyPath,
      `enum ConfigKey {
        INTERNAL("internal.normal", LifecycleStage.PERMANENT),
        INTERNAL_EXPERIMENT("internal.experiment", LifecycleStage.EXPERIMENTAL);
      }`,
    );
    writeFileSync(
      builderPath,
      `putYaml("justsearch.normal", root, "normal");`,
    );
    run({ root, envRegistryPath, configKeyPath, builderPath, configApplyPath });
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
}

test("parsers preserve the explicit lifecycle stage on every declaration", () => {
  withFixture(({ envRegistryPath, configKeyPath }) => {
    assert.deepEqual(
      parseEnvRegistry(envRegistryPath).entries.map(({ constant, lifecycleStage }) => ({
        constant,
        lifecycleStage,
      })),
      [
        { constant: "NORMAL", lifecycleStage: "permanent" },
        { constant: "WITH_DEFAULT", lifecycleStage: "permanent" },
        { constant: "EXPERIMENT", lifecycleStage: "experimental" },
        { constant: "RETIRING", lifecycleStage: "deprecated" },
      ],
    );
    assert.deepEqual(parseConfigKeys(configKeyPath).entries, [
      { constant: "INTERNAL", configKey: "internal.normal", lifecycleStage: "permanent" },
      {
        constant: "INTERNAL_EXPERIMENT",
        configKey: "internal.experiment",
        lifecycleStage: "experimental",
      },
    ]);
  });
});

test("a syntactically reshaped EnvRegistry declaration cannot disappear silently", () => {
  withFixture(({ envRegistryPath }) => {
    writeFileSync(
      envRegistryPath,
      `enum EnvRegistry {
        STABLE("justsearch.stable", "JUSTSEARCH_STABLE", LifecycleStage.PERMANENT),
        RESHAPED("justsearch.reshaped", "JUSTSEARCH_RESHAPED", LifecycleStage . EXPERIMENTAL);
      }`,
    );
    assert.throws(
      () => parseEnvRegistry(envRegistryPath),
      /declaration parity failed.*missing=\[RESHAPED\].*census=2 parsed=1/,
    );
  });
});

test("an implicit ConfigKey stage is a parity failure, not a permanent default", () => {
  withFixture(({ configKeyPath }) => {
    writeFileSync(
      configKeyPath,
      `enum ConfigKey {
        EXPLICIT("internal.explicit", LifecycleStage.PERMANENT),
        IMPLICIT("internal.implicit");
      }`,
    );
    assert.throws(
      () => parseConfigKeys(configKeyPath),
      /declaration parity failed.*missing=\[IMPLICIT\].*census=2 parsed=1/,
    );
  });
});

test("the independent census ignores declaration-shaped comments and literals", () => {
  const source = `enum ConfigKey {
    REAL("real", LifecycleStage.PERMANENT),
    ALSO_REAL("also", LifecycleStage.PERMANENT) {
      String example = "FAKE(\\\"fake\\\", LifecycleStage.EXPERIMENTAL)";
      // COMMENTED("commented", LifecycleStage.DEPRECATED),
    };
  }`;
  assert.deepEqual(censusJavaEnumConstants(source, "ConfigKey"), ["REAL", "ALSO_REAL"]);
});

test("matrix projects canonical declaration and lifecycle without copying values", () => {
  withFixture(({ root, envRegistryPath, configKeyPath, builderPath, configApplyPath }) => {
    const model = buildMatrixModel({
      repoRoot: root,
      envRegistryPath,
      configKeyPath,
      builderPath,
      configApplyPath,
    });
    const rows = new Map(model.rows.map((row) => [row.declaration, row]));
    assert.equal(model.applyScopeCount, 1, "count the register, not enum declarations or scope categories");

    assert.equal(rows.get("EnvRegistry.NORMAL").lifecycleStage, "permanent");
    assert.equal(rows.get("EnvRegistry.EXPERIMENT").lifecycleStage, "experimental");
    assert.equal(rows.get("ConfigKey.INTERNAL_EXPERIMENT").lifecycleStage, "experimental");
    assert.equal(rows.get("EnvRegistry.NORMAL").yamlKey, "justsearch.normal");
    assert.match(renderMatrixMarkdown(model), /\| EnvRegistry\.EXPERIMENT \| experimental \|/);
  });
});

test("API-port row projects the typed settings contributor and separates bound-port evidence", () => {
  withFixture(({ root, envRegistryPath, configKeyPath, builderPath, configApplyPath }) => {
    writeFileSync(envRegistryPath,
      `enum EnvRegistry {
        API_PORT("justsearch.api.port", "JUSTSEARCH_API_PORT", LifecycleStage.PERMANENT);
      }`);
    const uiSettingsContributorPath = path.join(root, "ConfigStoreRebuilder.java");
    writeFileSync(uiSettingsContributorPath,
      `builder.putSettings("justsearch.api.port", String.valueOf(settings.configuredApiPort()));`);
    const model = buildMatrixModel({ repoRoot: root, envRegistryPath, configKeyPath,
      builderPath, configApplyPath, uiSettingsContributorPath });
    const row = model.rows.find((item) => item.declaration === "EnvRegistry.API_PORT");
    assert.match(row.precedenceNotes, /settings\.json > default/);
    assert.match(row.precedenceNotes, /bound port is runtime evidence/);
    assert.match(renderMatrixMarkdown(model), /typed nullable `apiPort` contributes/);
  });
});

test("mixed YAML and operator sources project operator precedence in rows and prose", () => {
  withFixture(({ root, envRegistryPath, configKeyPath, builderPath, configApplyPath }) => {
    const model = buildMatrixModel({ repoRoot: root, envRegistryPath, configKeyPath,
      builderPath, configApplyPath });
    const row = model.rows.find((item) => item.declaration === "EnvRegistry.NORMAL");
    assert.equal(row.yamlKey, "justsearch.normal");
    assert.equal(row.sysprop, "justsearch.normal");
    assert.equal(row.envVar, "JUSTSEARCH_NORMAL");
    assert.equal(row.precedenceNotes, "sysprop > env > YAML > default");
    const markdown = renderMatrixMarkdown(model);
    assert.match(markdown, /1\. `sysprop > env > YAML > default`/);
    assert.doesNotMatch(markdown, /YAML > sysprop/);
  });
});

function readRepoFile(relativePath) {
  return readFileSync(new URL(`../../${relativePath}`, import.meta.url), "utf8");
}

test("overlapping ConfigKey declarations inherit the operator sources and precedence", () => {
  withFixture(({ root, envRegistryPath, configKeyPath, builderPath, configApplyPath }) => {
    writeFileSync(configKeyPath, `enum ConfigKey {
      OVERLAPPING("justsearch.normal", LifecycleStage.EXPERIMENTAL),
      YAML_ONLY("internal.normal", LifecycleStage.PERMANENT);
    }`);
    const model = buildMatrixModel({ repoRoot: root, envRegistryPath, configKeyPath,
      builderPath, configApplyPath });
    const rows = new Map(model.rows.map((row) => [row.declaration, row]));
    const operator = rows.get("EnvRegistry.NORMAL");
    const overlapping = rows.get("ConfigKey.OVERLAPPING");
    for (const field of ["yamlKey", "envVar", "sysprop", "envRegistryConstant", "precedenceNotes"]) {
      assert.equal(overlapping[field], operator[field], `shared resolved key must share ${field}`);
    }
    assert.equal(overlapping.lifecycleStage, "experimental");
    const unmatched = rows.get("ConfigKey.YAML_ONLY");
    assert.equal(unmatched.precedenceNotes, "YAML > default");
    assert.equal(unmatched.envVar, "");
    assert.equal(unmatched.sysprop, "");
    assert.match(renderMatrixMarkdown(model), /ConfigKey entries without a matching EnvRegistry declaration/);
  });
});

test("typed YAML node helpers retain YAML in both mixed-source declarations", () => {
  for (const helper of ["putYamlIntFromNode", "putYamlIntClampedFromNode"]) {
    withFixture(({ root, envRegistryPath, configKeyPath, builderPath, configApplyPath }) => {
      writeFileSync(configKeyPath, `enum ConfigKey {
        OVERLAPPING("justsearch.normal", LifecycleStage.PERMANENT);
      }`);
      writeFileSync(builderPath, `${helper}(\n "justsearch.normal", node, "normal"${
        helper === "putYamlIntClampedFromNode" ? ", 0, 2" : ""});`);
      assert.deepEqual(parseYamlContributions(builderPath).yamlKeys, ["justsearch.normal"], helper);
      const model = buildMatrixModel({ repoRoot: root, envRegistryPath, configKeyPath,
        builderPath, configApplyPath });
      for (const declaration of ["EnvRegistry.NORMAL", "ConfigKey.OVERLAPPING"]) {
        const row = model.rows.find((item) => item.declaration === declaration);
        assert.equal(row.yamlKey, "justsearch.normal", helper);
        assert.equal(row.precedenceNotes, "sysprop > env > YAML > default", helper);
      }
    });
  }
});

test("real resolver YAML helper calls are represented in the matrix", () => {
  const builderPath = new URL(
    "../../modules/configuration/src/main/java/io/justsearch/configuration/resolved/ResolvedConfigBuilder.java",
    import.meta.url);
  const source = readFileSync(builderPath, "utf8");
  const extracted = new Set(parseYamlContributions(builderPath).yamlKeys);
  // Independent call census catches future typed helper omissions.
  for (const call of source.matchAll(/\bputYaml\w*\(\s*"([^"]+)"\s*,/g)) {
    assert.ok(extracted.has(call[1]), `missing YAML contribution: ${call[0]}`);
  }
  const model = buildMatrixModel();
  for (const key of ["search.mcp_delivery.budget_bytes",
    "search.mcp_delivery.entity_carriage_max_chars", "search.mcp_framing.thin_result_floor_bytes"]) {
    assert.ok(source.includes(`"${key}"`));
    assert.ok(extracted.has(key), `real resolver must contribute YAML for ${key}`);
    for (const row of model.rows.filter((item) => item.sysprop === key)) {
      assert.equal(row.yamlKey, key);
      assert.equal(row.precedenceNotes, "sysprop > env > YAML > default");
    }
    assert.equal(model.rows.filter((item) => item.sysprop === key).length, 2);
  }
  for (const helper of ["putYamlIntFromNode", "putYamlIntClampedFromNode"]) {
    const body = source.split(`private void ${helper}(`)[1].split("\n  }")[0];
    assert.match(body, /put\(key, ORDINAL_YAML, "yaml"/);
  }
});

test("schema recovery guide uses emitted lifecycle reasons and preserves marker compatibility", () => {
  const guide = readRepoFile("docs/explanation/11-index-schema-migration.md");
  const section = guide.split("### A `FAIL_CLOSED` refusal")[1].split("\n### ")[0];
  const reasons = readRepoFile(
    "modules/app-api/src/main/java/io/justsearch/app/api/lifecycle/LifecycleReasonCode.java");
  for (const constant of ["INDEX_SCHEMA_OPEN_REFUSED", "INDEX_CORRUPT", "INDEX_FAILED"]) {
    const wire = reasons.match(new RegExp(`${constant}\\("([^"\\n]+)"\\)`))[1];
    assert.ok(section.includes(`\`${wire}\``), `guide must document ${constant} as ${wire}`);
  }
  assert.doesNotMatch(section, /worker\.(?:index_schema_mismatch|index_corrupt|spawn\.failed)/);
  assert.ok(section.includes("`<dataDir>/worker-fatal-reason`"));
  assert.ok(section.includes("`index_schema_mismatch`"));
  assert.ok(section.includes("`index_corrupt`"));
});

test("RAG streaming contract names registered routes and the producer's metadata event", () => {
  const doc = readRepoFile("docs/reference/contracts/search-and-rag-reason-codes.md");
  const routes = readRepoFile("modules/ui/src/main/java/io/justsearch/ui/api/routes/AiRoutes.java");
  for (const route of ["/api/chat/ask", "/api/chat/batch-summarize"]) {
    assert.ok(routes.includes(`"${route}"`));
    assert.ok(doc.includes(`POST ${route}`), `contract must name ${route}`);
  }
  const producer = readRepoFile(
    "modules/app-services/src/main/java/io/justsearch/app/services/conversation/spi/RAGContext.java");
  const event = producer.match(/events\.add\(new SseEvent\("([^"]+)", ragMeta\)\)/)[1];
  assert.ok(doc.includes(`\`${event}\``), `contract must name metadata event ${event}`);
  assert.doesNotMatch(doc, /rag_meta|SummaryController|POST \/api\/ask\/stream|POST \/api\/summarize\/batch\/stream/);
  for (const field of ["retrieval_mode", "retrieval_mode_reason", "context_truncated", "chunks_used", "chunks_found"]) {
    assert.ok(producer.includes(`ragMeta.put("${field}"`));
    assert.ok(doc.includes(`"${field}"`));
  }
  for (const field of ["best_chunk_score", "score_gap", "retrieval_coverage", "chunks_considered"]) {
    assert.ok(producer.includes(`ragMeta.put("${field}"`));
    assert.ok(doc.includes(`\`${field}\``));
  }
  assert.match(doc, /event: rag\.meta\ndata: /);
  assert.ok(doc.includes("finalResponse"));
  assert.ok(doc.includes("iterationsUsed"));
});

test("conversation failure contract distinguishes controller, engine and pre-stream refusals", () => {
  const doc = readRepoFile("docs/reference/contracts/search-and-rag-reason-codes.md");
  const controller = readRepoFile("modules/ui/src/main/java/io/justsearch/ui/api/ChatController.java");
  const controllerError = controller.split("private static SseEvent errorEvent(")[1]
    .split('return new SseEvent("error", err);')[0];
  const controllerClaim = doc.split("Controller SSE errors")[1]?.split("\n\n")[0] ?? "";
  for (const field of ["error", "errorCode", "errorClass", "retryable"]) {
    assert.ok(controllerError.includes(`err.put("${field}"`));
    assert.ok(controllerClaim.includes(`\`${field}\``), `controller claim must include ${field}`);
  }
  assert.doesNotMatch(controllerError, /i18nKey/);
  assert.match(controllerClaim, /no `i18nKey`/);
  const cancelled = controller.split("catch (io.justsearch.app.api.EngineWorkCancelledException cancelled)")[1]
    .split("} catch")[0];
  assert.ok(cancelled.includes('new SseEvent("error", Map.of('));
  const cancellationClaim = doc.split("Cancellation SSE errors")[1]?.split("\n\n")[0] ?? "";
  const fields = [...cancelled.matchAll(/"(\w+)"\s*,/g)].map((match) => match[1]);
  assert.deepEqual(fields, ["error", "message", "errorCode", "reasonCode"]);
  for (const field of fields.slice(1)) {
    assert.ok(cancellationClaim.includes(`\`${field}\``), `cancellation claim must include ${field}`);
  }
  assert.match(cancellationClaim, /no `error`, `errorClass`, `retryable`, or `i18nKey`/);
  const engine = readRepoFile(
    "modules/app-services/src/main/java/io/justsearch/app/services/conversation/ConversationEngine.java");
  const engineError = engine.split("private static void emitError(")[1]
    .split('sink.accept(new SseEvent("error", payload));')[0];
  const injector = readRepoFile(
    "modules/app-services/src/main/java/io/justsearch/app/services/conversation/spi/RAGContext.java");
  const injectorError = injector.split("private static Map<String, Object> errorPayload(")[1];
  const engineClaim = doc.split("Engine/injector SSE errors")[1]?.split("\n\n")[0] ?? "";
  for (const field of ["error", "errorCode", "i18nKey"]) {
    assert.ok(engineError.includes(`payload.put("${field}"`));
    assert.ok(injectorError.includes(`p.put("${field}"`));
    assert.ok(engineClaim.includes(`\`${field}\``));
  }
  const locked = controller.split("if (engine.wouldDiscardWhileLocked(")[1]
    .split("sseWriter.initSseHeaders")[0];
  assert.match(controller, /LOCKED_STATUS = 423/);
  assert.ok(locked.includes("ctx.status(LOCKED_STATUS).json(locked)"));
  assert.match(doc, /HTTP 423 JSON before SSE headers/);
  assert.match(doc, /admitted streaming response/);
  assert.match(doc, /Malformed bodies.*controller SSE errors/);
});
