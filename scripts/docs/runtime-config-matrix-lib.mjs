import fs from "node:fs";
import path from "node:path";

function repoRootFromCwd() {
  const cwd = process.cwd();
  const markers = ["settings.gradle.kts", "build.gradle.kts", ".git"];
  for (let dir = cwd; ; dir = path.dirname(dir)) {
    if (markers.some((m) => fs.existsSync(path.join(dir, m)))) {
      return dir;
    }
    const parent = path.dirname(dir);
    if (parent === dir) {
      break;
    }
  }
  return cwd;
}

function maskJavaCommentsAndLiterals(text) {
  const chars = [...text];
  let state = "code";
  for (let i = 0; i < chars.length; i++) {
    const current = chars[i];
    const next = chars[i + 1];
    if (state === "code") {
      if (current === "/" && next === "/") {
        chars[i] = chars[i + 1] = " ";
        i++;
        state = "line-comment";
      } else if (current === "/" && next === "*") {
        chars[i] = chars[i + 1] = " ";
        i++;
        state = "block-comment";
      } else if (current === '"' && next === '"' && chars[i + 2] === '"') {
        chars[i] = chars[i + 1] = chars[i + 2] = " ";
        i += 2;
        state = "text-block";
      } else if (current === '"') {
        chars[i] = " ";
        state = "string";
      } else if (current === "'") {
        chars[i] = " ";
        state = "char";
      }
    } else if (state === "line-comment") {
      if (current === "\n" || current === "\r") {
        state = "code";
      } else {
        chars[i] = " ";
      }
    } else if (state === "block-comment") {
      chars[i] = " ";
      if (current === "*" && next === "/") {
        chars[i + 1] = " ";
        i++;
        state = "code";
      }
    } else if (state === "text-block") {
      chars[i] = " ";
      if (current === '"' && next === '"' && chars[i + 2] === '"') {
        chars[i + 1] = chars[i + 2] = " ";
        i += 2;
        state = "code";
      }
    } else {
      chars[i] = " ";
      if (current === "\\") {
        if (i + 1 < chars.length) chars[++i] = " ";
      } else if ((state === "string" && current === '"')
          || (state === "char" && current === "'")) {
        state = "code";
      }
    }
  }
  if (state !== "code" && state !== "line-comment") {
    throw new Error(`Unterminated Java ${state}`);
  }
  return chars.join("");
}

/**
 * Lexically inventories every top-level enum constant independently of the argument-shape parser.
 * A parity mismatch makes a reformatted declaration fail instead of disappearing from governance.
 */
export function censusJavaEnumConstants(text, enumName) {
  const masked = maskJavaCommentsAndLiterals(text);
  const header = new RegExp(`\\benum\\s+${enumName}\\b`).exec(masked);
  if (!header) throw new Error(`Could not find enum ${enumName}`);
  const open = masked.indexOf("{", header.index + header[0].length);
  if (open < 0) throw new Error(`Could not find constant block for enum ${enumName}`);

  const names = [];
  let cursor = open + 1;
  let expectConstant = true;
  let parens = 0;
  let braces = 0;
  let brackets = 0;
  for (; cursor < masked.length; cursor++) {
    if (expectConstant) {
      while (/\s/.test(masked[cursor] ?? "")) cursor++;
      if (masked[cursor] === ";") return names;
      const name = /^[A-Z][A-Z0-9_]*/.exec(masked.slice(cursor));
      if (!name) {
        throw new Error(`Could not census enum ${enumName} constant near offset ${cursor}`);
      }
      names.push(name[0]);
      cursor += name[0].length - 1;
      expectConstant = false;
      continue;
    }

    const char = masked[cursor];
    if (char === "(") parens++;
    else if (char === ")") parens--;
    else if (char === "{") braces++;
    else if (char === "}") braces--;
    else if (char === "[") brackets++;
    else if (char === "]") brackets--;
    else if (parens === 0 && braces === 0 && brackets === 0 && char === ",") {
      expectConstant = true;
    } else if (parens === 0 && braces === 0 && brackets === 0 && char === ";") {
      return names;
    }
    if (parens < 0 || braces < 0 || brackets < 0) {
      throw new Error(`Unbalanced enum ${enumName} constant block near offset ${cursor}`);
    }
  }
  throw new Error(`Enum ${enumName} constant block has no terminating semicolon`);
}

function assertDeclarationParity(enumName, census, parsed, sourcePath) {
  const parsedNames = parsed.map((entry) => entry.constant);
  const parsedSet = new Set(parsedNames);
  const censusSet = new Set(census);
  const missing = census.filter((name) => !parsedSet.has(name));
  const unexpected = parsedNames.filter((name) => !censusSet.has(name));
  const duplicates = parsedNames.filter((name, index) => parsedNames.indexOf(name) !== index);
  if (missing.length || unexpected.length || duplicates.length || census.length !== parsed.length) {
    throw new Error(
      `${enumName} declaration parity failed in ${sourcePath}: `
        + `missing=[${missing.join(", ")}] unexpected=[${unexpected.join(", ")}] `
        + `duplicates=[${[...new Set(duplicates)].join(", ")}] `
        + `census=${census.length} parsed=${parsed.length}`,
    );
  }
}

/**
 * Parses EnvRegistry.java for operator-facing (sysprop, envVar) pairs.
 * Handles both 2-arg and 3-arg constructors.
 */
export function parseEnvRegistry(envRegistryPath) {
  const text = fs.readFileSync(envRegistryPath, "utf8");
  const census = censusJavaEnumConstants(text, "EnvRegistry");
  const bySysprop = new Map();
  const byEnvVar = new Map();
  const entries = [];

  const constantPattern =
    /([A-Z0-9_]+)\s*\(\s*"([^"]+)"\s*,\s*"([^"]+)"\s*(?:,\s*"[^"]*")?\s*,\s*LifecycleStage\.(PERMANENT|EXPERIMENTAL|DEPRECATED)\s*\)\s*[,;]/g;
  for (const match of text.matchAll(constantPattern)) {
    const constant = match[1];
    const sysprop = match[2];
    const envVar = match[3];
    const lifecycleStage = match[4].toLowerCase();
    entries.push({ constant, sysprop, envVar, lifecycleStage });
    bySysprop.set(sysprop, constant);
    byEnvVar.set(envVar, constant);
  }

  assertDeclarationParity("EnvRegistry", census, entries, envRegistryPath);

  return { entries, bySysprop, byEnvVar, declarationCount: census.length };
}

/**
 * Parses ConfigKey.java for YAML-only config keys.
 */
export function parseConfigKeys(configKeyPath) {
  if (!fs.existsSync(configKeyPath)) {
    return { entries: [] };
  }
  const text = fs.readFileSync(configKeyPath, "utf8");
  const census = censusJavaEnumConstants(text, "ConfigKey");
  const entries = [];

  const pattern =
    /([A-Z0-9_]+)\s*\(\s*"([^"]+)"\s*,\s*LifecycleStage\.(PERMANENT|EXPERIMENTAL|DEPRECATED)\s*\)\s*[,;]/g;
  for (const match of text.matchAll(pattern)) {
    entries.push({
      constant: match[1],
      configKey: match[2],
      lifecycleStage: match[3].toLowerCase(),
    });
  }
  assertDeclarationParity("ConfigKey", census, entries, configKeyPath);
  return { entries, declarationCount: census.length };
}

/**
 * Parses ResolvedConfigBuilder.java for putYaml*() calls to extract YAML key mappings.
 * Returns a set of YAML config keys that have YAML-source contributions.
 */
export function parseYamlContributions(builderPath) {
  const text = fs.readFileSync(builderPath, "utf8");
  const yamlKeys = new Set();

  // putYaml("configKey", root, "yamlPath") — first arg is the config key
  const putYamlPattern =
    /putYaml(?:Int|Long|Boolean|Double|FromNode|FromNodeLower)?\(\s*"([^"]+)"\s*,/g;
  for (const match of text.matchAll(putYamlPattern)) {
    yamlKeys.add(match[1]);
  }

  // putYaml(EnvRegistry.SOMETHING.sysProp(), root, "yamlPath") — uses EnvRegistry accessor
  const putYamlEnvPattern =
    /putYaml\(\s*(?:io\.justsearch\.configuration\.)?EnvRegistry\.([A-Z0-9_]+)\.sysProp\(\)\s*,/g;
  for (const match of text.matchAll(putYamlEnvPattern)) {
    yamlKeys.add(`EnvRegistry.${match[1]}`); // marker, resolved during merge
  }

  return { yamlKeys: Array.from(yamlKeys).sort() };
}

export function buildMatrixModel(opts = {}) {
  const repoRoot = opts.repoRoot ?? repoRootFromCwd();
  const configBase = path.join(
    repoRoot, "modules", "configuration", "src", "main", "java",
    "io", "justsearch", "configuration",
  );

  const envRegistryPath = opts.envRegistryPath ?? path.join(configBase, "EnvRegistry.java");
  const configKeyPath = opts.configKeyPath ?? path.join(configBase, "ConfigKey.java");
  const builderPath = opts.builderPath ?? path.join(configBase, "resolved", "ResolvedConfigBuilder.java");
  const configApplyPath = opts.configApplyPath ?? path.join(repoRoot, "governance", "config-apply.v1.json");
  // Classification/schema parity belongs to ConfigApplyRegisterTest; this is a count projection.
  const configApply = JSON.parse(fs.readFileSync(configApplyPath, "utf8"));

  const envRegistry = parseEnvRegistry(envRegistryPath);
  const configKeys = parseConfigKeys(configKeyPath);
  const yamlContrib = parseYamlContributions(builderPath);
  const yamlKeySet = new Set(yamlContrib.yamlKeys);

  const rows = [];

  // EnvRegistry entries: operator-facing, have env var and sysprop
  for (const entry of envRegistry.entries) {
    const hasYaml = yamlKeySet.has(entry.sysprop);
    rows.push({
      declaration: `EnvRegistry.${entry.constant}`,
      lifecycleStage: entry.lifecycleStage,
      yamlKey: hasYaml ? entry.sysprop : "",
      envVar: entry.envVar,
      sysprop: entry.sysprop,
      envRegistryConstant: entry.constant,
      ownerModule: "modules/configuration (ResolvedConfigBuilder)",
      precedenceNotes: hasYaml
        ? "YAML > sysprop > env > default"
        : "sysprop > env > default",
    });
  }

  // ConfigKey entries: YAML-only, no env var or sysprop
  for (const entry of configKeys.entries) {
    rows.push({
      declaration: `ConfigKey.${entry.constant}`,
      lifecycleStage: entry.lifecycleStage,
      yamlKey: entry.configKey,
      envVar: "",
      sysprop: "",
      envRegistryConstant: "",
      ownerModule: "modules/configuration (ResolvedConfigBuilder)",
      precedenceNotes: "YAML > default",
    });
  }

  rows.sort((a, b) => {
    const aKey = a.yamlKey || a.sysprop || "~";
    const bKey = b.yamlKey || b.sysprop || "~";
    return aKey.localeCompare(bKey);
  });

  return {
    generatedAt: new Date().toISOString(),
    repoRoot,
    envRegistryPath: path.relative(repoRoot, envRegistryPath).replaceAll("\\", "/"),
    configKeyPath: path.relative(repoRoot, configKeyPath).replaceAll("\\", "/"),
    builderPath: path.relative(repoRoot, builderPath).replaceAll("\\", "/"),
    yamlKeyCount: yamlContrib.yamlKeys.length,
    envSyspropPairCount: envRegistry.entries.length,
    configKeyCount: configKeys.entries.length,
    applyScopeCount: configApply.entries.length,
    rows,
  };
}

function mdCell(value) {
  const normalized = value && value.trim() ? value : "-";
  return normalized.replaceAll("|", "\\|");
}

export function renderMatrixMarkdown(model) {
  const date = model.generatedAt.slice(0, 10);
  const lines = [];
  lines.push("---");
  lines.push('title: Runtime Config Ownership Matrix');
  lines.push("type: reference");
  lines.push("status: stable");
  lines.push(
    'description: "Canonical YAML/env/sysprop ownership and precedence map."',
  );
  lines.push("---");
  lines.push("");
  lines.push("# Runtime Config Ownership Matrix");
  lines.push("");
  lines.push(
    `Generated from \`${model.envRegistryPath}\`, \`${model.configKeyPath}\`, and \`${model.builderPath}\` on ${date}.`,
  );
  lines.push("");
  lines.push("Precedence note:");
  lines.push("1. `YAML > sysprop > env > default` where a YAML key and env/sysprop fallback both exist.");
  lines.push("2. `YAML > default` for YAML-only keys (ConfigKey entries, no env var override).");
  lines.push("3. `sysprop > env > default` for env/sysprop-only runtime knobs.");
  lines.push("4. Every declaration explicitly carries `permanent`, `experimental`, or `deprecated`; non-permanent rows require joined review metadata in `governance/config-lifecycle.v1.json`.");
  lines.push("");
  lines.push("Apply scopes are classified in `governance/config-apply.v1.json`. Its entry count is projected as `applyScopeCount` and ratcheted by the `config-surface` gate's `apply_scope` metric. `ConfigApplyRegisterTest` validates schema, canonical declaration parity and scope classification. Classification does not establish runtime dispatch or complete generation fingerprint binding; remaining integration is tracked in the Lane F D1 design.");
  lines.push("");
  lines.push(
    "The per-row notes above cover only the sources this table can derive from `EnvRegistry` /" +
      " `ConfigKey`. The full ordinal chain in `ResolvedConfigBuilder` has more: `jvm_arg` 500 >" +
      " `worker_snapshot` 450 > `env_var` 400 > `ci_profile` 350 > `settings.json` 300 > `yaml`" +
      " 200 > `auto_detected` 150 > `default` 100. Two of those contributors are invisible here" +
      " because they are written by callers rather than declared as keys:",
  );
  lines.push("");
  lines.push(
    "- **`settings.json` (300)** — `ConfigStoreRebuilder.contributeUiSettings` forwards a handful" +
      " of `UiSettings` fields, including `justsearch.gpu.layers`, `justsearch.context.size`," +
      " `justsearch.server.exe`, `justsearch.ui.exclude_patterns`, `justsearch.index.base_path`" +
      " and `justsearch.llm.model_path`.",
  );
  lines.push(
    "- **`auto_detected` (150, detail `hardware_probe`)** — the Head's startup probe contributes" +
      " GPU detection results (including the VRAM-tier `justsearch.gpu.layers`) and, since" +
      " tempdoc 883, the DERIVED `justsearch.context.size` window rung.",
  );
  lines.push("");
  lines.push(
    "Tempdoc 883 decision 4 deleted the settings-to-sysprop promotions for" +
      " `justsearch.context.size` (slice 1) and `justsearch.gpu.layers`," +
      " `justsearch.server.exe`, `justsearch.ui.exclude_patterns` (slice 2), and its §C.5c residue" +
      " deleted the last two, `justsearch.index.base_path` and `justsearch.llm.model_path`, along" +
      " with every `*.source=ui_settings` marker property. Each of those keys now resolves" +
      " `settings.json` when the user set one and `auto_detected` / `default` otherwise — never" +
      " `jvm_arg` merely because the value came from the GUI, which is what the promotions used to" +
      " make them report. The runtime GPU executable switch also uses resolver provenance:" +
      " boot auto-detection at150, accepted settings at300, and environment/JVM sources at400/500." +
      " Its `justsearch.server.exe.source` marker is retired. The model-path marker has no current" +
      " writer; its compatibility reader remains for the separately designed profile persistence path.",
  );
  lines.push("");
  lines.push("| Declaration | Lifecycle | YAML key | Env var | System property | EnvRegistry constant | Owner module | Precedence notes |");
  lines.push("| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |");
  for (const row of model.rows) {
    lines.push(
      `| ${mdCell(row.declaration)} | ${mdCell(row.lifecycleStage)} | ${mdCell(row.yamlKey)} | ${mdCell(row.envVar)} | ${mdCell(row.sysprop)} | ${mdCell(row.envRegistryConstant)} | ${mdCell(row.ownerModule)} | ${mdCell(row.precedenceNotes)} |`,
    );
  }
  lines.push("");
  return lines.join("\n");
}
