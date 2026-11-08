#!/usr/bin/env node
// Generate Fathom's iOS source SBOM. The iOS target has no Swift Package
// Manager dependencies: the shared Kotlin core is linked as a statically
// built FathomShared.xcframework, whose Gradle dependencies are covered by
// the Android SBOM. This document records that absence explicitly instead
// of leaving consumers to guess.
//
// Usage:
//   node tools/release/generate-ios-sbom.mjs <output.spdx.json> [--version <tag>]

import { mkdirSync, writeFileSync } from "node:fs";
import path from "node:path";

const args = process.argv.slice(2);
const outputPath = args.find((arg) => !arg.startsWith("--"));
const index = args.indexOf("--version");
const version = index === -1 ? "unknown" : args[index + 1] ?? "unknown";

if (!outputPath) {
  console.error("Usage: node tools/release/generate-ios-sbom.mjs <output.spdx.json> [--version <tag>]");
  process.exit(1);
}

const created = new Date().toISOString().replace(/\.\d+Z$/, "Z");
const document = {
  spdxVersion: "SPDX-2.3",
  dataLicense: "CC0-1.0",
  SPDXID: "SPDXRef-DOCUMENT",
  name: `fathom-ios-dependencies-${version}`,
  documentNamespace: `https://fathom.ewancroft.uk/sbom/ios/${version}-${Date.now()}`,
  creationInfo: {
    created,
    creators: ["Tool: fathom-generate-ios-sbom.mjs"],
  },
  packages: [],
  comment:
    "Fathom's iOS target has no Swift Package Manager dependencies. The shared " +
    "Kotlin core is built into FathomShared.xcframework; its Gradle dependency " +
    "graph is recorded by the Android SBOM for the same release.",
};

mkdirSync(path.dirname(path.resolve(outputPath)), { recursive: true });
writeFileSync(outputPath, `${JSON.stringify(document, null, 2)}\n`);
console.log(`Wrote the iOS SBOM (no SwiftPM packages) to ${outputPath}`);
