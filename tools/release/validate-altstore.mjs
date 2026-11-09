#!/usr/bin/env node
// Validate the AltStore Classic source and its website mirror.
//
//   node tools/release/validate-altstore.mjs [--allow-empty] [--hosted]
//
// --allow-empty  permit a source with no versions yet.
// --hosted       GET the newest downloadURL and compare its bytes and SHA-256.

import { existsSync, readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..", "..");
const sourcePath = path.join(root, "iOS", "altstore", "source.json");
const mirrorPath = path.join(root, "website", "altstore", "source.json");
const allowEmpty = process.argv.includes("--allow-empty");
const hosted = process.argv.includes("--hosted");

function fail(message) {
  console.error(`\n✗ ${message}\n`);
  process.exit(1);
}

for (const file of [sourcePath, mirrorPath]) {
  if (!existsSync(file)) fail(`Missing ${path.relative(root, file)}.`);
}

const sourceBytes = readFileSync(sourcePath);
const mirrorBytes = readFileSync(mirrorPath);
if (!sourceBytes.equals(mirrorBytes)) {
  fail("iOS/altstore/source.json and website/altstore/source.json are not byte-for-byte equal.");
}

let data;
try {
  data = JSON.parse(sourceBytes.toString("utf8"));
} catch (error) {
  fail(`source.json is not valid JSON: ${error.message}`);
}

const app = data.apps?.[0];
if (!app) fail("source.json has no apps[0].");
if (!data.name || !data.iconURL) fail("source.json needs a name and iconURL.");
if (app.bundleIdentifier !== "uk.ewancroft.fathom") {
  fail(`Unexpected bundle identifier: ${app.bundleIdentifier}`);
}
if (data.marketplaceID) {
  fail("marketplaceID belongs to AltStore PAL; this is a Classic source.");
}
if (!Array.isArray(app.versions)) fail("apps[0].versions must be an array.");
if (app.versions.length === 0 && !allowEmpty) {
  fail("apps[0].versions is empty; publish an IPA or pass --allow-empty.");
}

const hex = /^[a-f0-9]{64}$/;
for (const [index, version] of app.versions.entries()) {
  const label = `versions[${index}]`;
  for (const key of [
    "version",
    "buildVersion",
    "date",
    "localizedDescription",
    "downloadURL",
    "size",
    "sha256",
    "minOSVersion",
  ]) {
    if (version[key] === undefined || version[key] === "") {
      fail(`${label} is missing ${key}.`);
    }
  }
  if (!/^\d+\.\d+\.\d+$/.test(version.version)) {
    fail(`${label}.version is not X.Y.Z: ${version.version}`);
  }
  if (!/^\d+$/.test(String(version.buildVersion))) {
    fail(`${label}.buildVersion is not an integer.`);
  }
  if (!/^\d{4}-\d{2}-\d{2}$/.test(version.date)) {
    fail(`${label}.date is not YYYY-MM-DD.`);
  }
  if (!Number.isInteger(version.size) || version.size < 1) {
    fail(`${label}.size is not a positive integer.`);
  }
  if (!hex.test(version.sha256)) {
    fail(`${label}.sha256 is not a lowercase SHA-256.`);
  }
  if (!version.downloadURL.startsWith("https://")) {
    fail(`${label}.downloadURL is not HTTPS.`);
  }
}

if (app.versions.length > 0) {
  const latest = app.versions[0];
  for (const key of [
    "version",
    "buildVersion",
    "versionDate",
    "size",
    "sha256",
    "downloadURL",
    "versionDescription",
  ]) {
    if (app[key] === undefined || app[key] === "") {
      fail(`apps[0] is missing the latest-version field ${key}.`);
    }
  }
  if (
    app.version !== latest.version ||
    String(app.buildVersion) !== String(latest.buildVersion) ||
    app.sha256 !== latest.sha256 ||
    app.size !== latest.size ||
    app.downloadURL !== latest.downloadURL
  ) {
    fail("apps[0] latest fields do not match versions[0].");
  }
  if (
    data.version !== latest.version ||
    String(data.buildVersion) !== String(latest.buildVersion)
  ) {
    fail("Top-level version/buildVersion do not match versions[0].");
  }

  if (hosted) {
    const response = await fetch(latest.downloadURL);
    if (!response.ok) fail(`GET ${latest.downloadURL} returned ${response.status}.`);
    const bytes = Buffer.from(await response.arrayBuffer());
    if (bytes.length !== latest.size) {
      fail(`Hosted IPA is ${bytes.length} bytes; source.json says ${latest.size}.`);
    }
    const { createHash } = await import("node:crypto");
    const digest = createHash("sha256").update(bytes).digest("hex");
    if (digest !== latest.sha256) {
      fail(`Hosted IPA SHA-256 is ${digest}; source.json says ${latest.sha256}.`);
    }
  }
}

console.log(
  `AltStore source OK: ${app.versions.length} version${app.versions.length === 1 ? "" : "s"}` +
    (hosted ? ", hosted IPA checked" : "") +
    ".",
);
