import fs from "node:fs";
import path from "node:path";
import {
  buildMatrixModel,
  renderMatrixMarkdown,
} from "./runtime-config-matrix-lib.mjs";

function parseArgs(argv) {
  const args = {
    check: false,
    outJson:
      "tmp/agent-evidence/_summaries/runtime-config-ownership-matrix.generated.json",
    writeDoc: "",
  };

  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (arg === "--check") {
      args.check = true;
    } else if (arg === "--out-json") {
      args.outJson = argv[++i];
    } else if (arg === "--write-doc") {
      args.writeDoc = argv[++i];
    } else {
      throw new Error(`Unknown argument: ${arg}`);
    }
  }
  return args;
}

function ensureParentDir(filePath) {
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
}

function main() {
  const args = parseArgs(process.argv.slice(2));
  const model = buildMatrixModel();

  ensureParentDir(args.outJson);
  fs.writeFileSync(args.outJson, `${JSON.stringify(model, null, 2)}\n`, "utf8");

  if (args.writeDoc) {
    const markdown = renderMatrixMarkdown(model);
    ensureParentDir(args.writeDoc);
    fs.writeFileSync(args.writeDoc, markdown, "utf8");
  }

  if (args.check) {
    if (!model.yamlKeyCount || !model.envSyspropPairCount) {
      throw new Error(
        `Matrix extraction is unexpectedly empty (yaml=${model.yamlKeyCount}, pairs=${model.envSyspropPairCount})`,
      );
    }
  }

  console.log(
    `runtime-config-matrix: yaml_keys=${model.yamlKeyCount} env_sysprop_pairs=${model.envSyspropPairCount} config_keys=${model.configKeyCount ?? 0} apply_scope=${model.applyScopeCount} rows=${model.rows.length}`,
  );
  if (args.writeDoc) {
    console.log(`runtime-config-matrix: wrote ${args.writeDoc}`);
  }
  console.log(`runtime-config-matrix: wrote ${args.outJson}`);
}

main();
