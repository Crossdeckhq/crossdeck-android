#!/usr/bin/env node
/**
 * Emit `crossdeck/src/main/resources/crossdeck/contracts.json` —
 * the JAR-resource sidecar consumed by the public `CrossdeckContracts`
 * API. Files under `src/main/resources/` are packaged verbatim into
 * the AAR/JAR and loadable at runtime via the class loader.
 *
 * Invoked by the `emitContracts` gradle task before every build,
 * and by CI's `contract-audit` to keep the snapshot in lockstep
 * with `contracts/**\/*.json` at the monorepo root.
 *
 * Filters by `appliesTo` containing "android" and stamps `bundledIn`
 * from the version literal in `crossdeck/build.gradle.kts`.
 */
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const sdkRoot = path.resolve(__dirname, "..");
const resourcesDir = path.join(
  sdkRoot,
  "crossdeck",
  "src",
  "main",
  "resources",
  "crossdeck",
);
const repoRoot = path.resolve(sdkRoot, "../..");
const contractsRoot = path.join(repoRoot, "contracts");
const target = path.join(resourcesDir, "contracts.json");

const SDK_IDENTIFIER = "android";

function readSdkVersion() {
  const buildFile = path.join(sdkRoot, "crossdeck", "build.gradle.kts");
  const src = fs.readFileSync(buildFile, "utf8");
  const match = src.match(/version\s*=\s*"([^"]+)"/);
  if (!match) {
    console.error(`[emit-contracts] could not parse version from build.gradle.kts`);
    process.exit(1);
  }
  return match[1];
}

function collectContracts(dir) {
  const found = [];
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) found.push(...collectContracts(full));
    else if (entry.isFile() && entry.name.endsWith(".json")) found.push(full);
  }
  return found;
}

const sdkVersion = readSdkVersion();
const bundledIn = `com.crossdeck:crossdeck:${sdkVersion}`;

if (!fs.existsSync(resourcesDir)) {
  fs.mkdirSync(resourcesDir, { recursive: true });
}

const matching = [];
for (const file of collectContracts(contractsRoot)) {
  const parsed = JSON.parse(fs.readFileSync(file, "utf8"));
  if (!Array.isArray(parsed?.appliesTo)) {
    console.error(`[emit-contracts] ${file} missing appliesTo`);
    process.exit(1);
  }
  if (parsed.appliesTo.includes(SDK_IDENTIFIER)) {
    matching.push({ ...parsed, bundledIn });
  }
}
matching.sort((a, b) => a.id.localeCompare(b.id));

const payload = {
  $schema: "https://json-schema.org/draft/2020-12/schema",
  generatedAt: new Date().toISOString(),
  sdk: "com.crossdeck:crossdeck",
  sdkVersion,
  bundledIn,
  count: matching.length,
  contracts: matching,
};

fs.writeFileSync(target, JSON.stringify(payload, null, 2) + "\n", "utf8");
console.log(
  `[emit-contracts] wrote ${matching.length} contracts (appliesTo includes "${SDK_IDENTIFIER}") to crossdeck/src/main/resources/crossdeck/contracts.json`,
);
