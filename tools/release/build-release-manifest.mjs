#!/usr/bin/env node
// Build the release manifest that is attested and attached by
// .github/workflows/release.yml. The APK/IPA are assembled and signed by
// tools/release/publish.mjs on the publisher's machine, never in Actions.
// The workflow therefore attests this manifest and the SBOMs -- files it
// genuinely produced -- not the binaries. A verifier checks the manifest's
// attestation, then compares their downloaded APK/IPA hash to the manifest.
//
// Usage:
//   node tools/release/build-release-manifest.mjs \
//     --tag vX.Y.Z --assets-dir <dir> --sbom-dir <dir> --out <path> \
//     [--commit <sha>] [--run-url <url>]

import { readFileSync, writeFileSync, readdirSync, existsSync } from "node:fs";
import { createHash } from "node:crypto";
import path from "node:path";

const args = process.argv.slice(2);
function opt(name, required = false) {
  const index = args.indexOf(`--${name}`);
  const value = index === -1 ? undefined : args[index + 1];
  if (required && !value) {
    console.error(`Missing required --${name}`);
    process.exit(1);
  }
  return value;
}

const tag = opt("tag", true);
const assetsDir = opt("assets-dir", true);
const sbomDir = opt("sbom-dir", true);
const outPath = opt("out", true);
const commit = opt("commit") ?? process.env.GITHUB_SHA ?? "unknown";
const runUrl =
  opt("run-url") ??
  (process.env.GITHUB_SERVER_URL && process.env.GITHUB_REPOSITORY && process.env.GITHUB_RUN_ID
    ? `${process.env.GITHUB_SERVER_URL}/${process.env.GITHUB_REPOSITORY}/actions/runs/${process.env.GITHUB_RUN_ID}`
    : "unknown");

if (!/^v\d+\.\d+\.\d+$/.test(tag)) {
  console.error(`Tag must match vX.Y.Z, got ${tag}`);
  process.exit(1);
}
const version = tag.slice(1);

function sha256File(filePath) {
  return createHash("sha256").update(readFileSync(filePath)).digest("hex");
}

function listFiles(dir, extension) {
  if (!existsSync(dir)) return [];
  return readdirSync(dir)
    .filter((name) => name.endsWith(extension))
    .sort()
    .map((name) => {
      const filePath = path.join(dir, name);
      return { name, sha256: sha256File(filePath), bytes: readFileSync(filePath).length };
    });
}

const apks = listFiles(assetsDir, ".apk");
const ipas = listFiles(assetsDir, ".ipa");
const sboms = listFiles(sbomDir, ".spdx.json");

if (apks.length === 0 && ipas.length === 0) {
  console.error(
    `No .apk or .ipa assets found in ${assetsDir} for ${tag}. ` +
      "The GitHub Release must already carry the locally built binaries before provenance runs.",
  );
  process.exit(1);
}
if (apks.length > 1 || ipas.length > 1) {
  console.error("Expected at most one APK and one IPA in the release assets.");
  process.exit(1);
}
if (sboms.length === 0) {
  console.error(`No .spdx.json SBOMs found in ${sbomDir}.`);
  process.exit(1);
}

const artifacts = {};
if (apks[0]) artifacts.apk = apks[0];
if (ipas[0]) artifacts.ipa = ipas[0];

const manifest = {
  schema: "fathom-release-manifest/1",
  version,
  tag,
  commit,
  workflowRun: runUrl,
  generatedAt: new Date().toISOString(),
  artifacts,
  sboms: sboms.map(({ name, sha256, bytes }) => ({ name, sha256, bytes })),
  provenance: {
    binariesBuiltInActions: false,
    note:
      "The APK/IPA are assembled and signed outside GitHub Actions by " +
      "tools/release/publish.mjs. This manifest and the SBOMs — not the " +
      "binaries — carry the GitHub Actions build-provenance attestation. " +
      "Verify the manifest's attestation, then confirm that a downloaded " +
      "APK/IPA's sha256 matches the corresponding entry above.",
  },
};

writeFileSync(outPath, `${JSON.stringify(manifest, null, 2)}\n`);
console.log(`Wrote ${outPath}`);
console.log(JSON.stringify(manifest, null, 2));
