import assert from "node:assert/strict";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";

import {
  buildMatrixModel,
  censusJavaEnumConstants,
  parseConfigKeys,
  parseEnvRegistry,
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
