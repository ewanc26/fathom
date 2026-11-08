#!/usr/bin/env node
// Publish a Fathom release the way Inkwell does it: the APK/IPA are built
// and signed on the publisher's machine, then attached to one unified
// GitHub Release tagged v<version>. GitHub Actions never receives a signing
// key; .github/workflows/release.yml only adds SBOMs, a hashed manifest and
// build-provenance attestations afterwards. See RELEASE_PROCESS.md.
//
// Usage:
//   node tools/release/publish.mjs status
//     Read-only. Shows whether the APK/IPA for the source version are on
//     the GitHub Release.
//
//   node tools/release/publish.mjs android [--yes] [--notes-file <path>]
//     Builds a signed release APK through scripts/release-android.sh,
//     verifies it is not debug-signed, and publishes it as
//     Fathom-<version>.apk.
//
//   node tools/release/publish.mjs ios --ipa <path> [--yes] [--notes-file <path>]
//     Publishes an already-exported App Store Connect IPA as
//     Fathom-<version>.ipa. Xcode archiving needs an interactive keychain,
//     so this command deliberately does not build the IPA itself.
//
// Flags:
//   --yes             Actually build/sign/publish. Without it the command is
//                     a dry run and changes nothing.
//   --notes-file      Markdown for a newly created release. Without it the
//                     publisher drafts a starting point from commit subjects.
//   --skip-existing   Succeed without doing anything when this exact asset is
//                     already attached to the release.
//
// The Android build reads FATHOM_KEYSTORE_FILE, FATHOM_KEYSTORE_PASSWORD,
// FATHOM_KEY_ALIAS and FATHOM_KEY_PASSWORD, or Android/keystore.properties.

import {
  cpSync,
  existsSync,
  mkdirSync,
  readFileSync,
  readdirSync,
  rmSync,
  writeFileSync,
} from "node:fs";
import { execFileSync, execSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import path from "node:path";

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, "..", "..");
const repo = "ewanc26/fathom";

const args = process.argv.slice(2);
const command = args[0];
const flag = (name) => args.includes(`--${name}`);
const option = (name) => {
  const index = args.indexOf(`--${name}`);
  return index === -1 ? undefined : args[index + 1];
};

const YES = flag("yes");
const SKIP_EXISTING = flag("skip-existing");

if (YES && process.env.CI) {
  fail("Refusing to publish from CI; release signing belongs on the publisher's machine.");
}

function fail(message) {
  console.error(`\n✗ ${message}\n`);
  process.exit(1);
}

function run(cmd, cmdArgs, opts = {}) {
  console.log(`$ ${cmd} ${cmdArgs.join(" ")}`);
  return execFileSync(cmd, cmdArgs, {
    cwd: opts.cwd ?? root,
    stdio: opts.capture ? ["ignore", "pipe", "inherit"] : "inherit",
    encoding: opts.capture ? "utf8" : undefined,
  });
}

function quiet(cmd) {
  try {
    return execSync(cmd, {
      cwd: root,
      encoding: "utf8",
      stdio: ["ignore", "pipe", "ignore"],
    }).trim();
  } catch {
    return null;
  }
}

function sha256File(filePath) {
  return execFileSync("shasum", ["-a", "256", filePath], {
    cwd: root,
    encoding: "utf8",
  })
    .trim()
    .split(/\s+/)[0];
}

// ── Version and release state ─────────────────────────────────────

function readVersion() {
  const file = path.join(root, "version.properties");
  const values = {};
  for (const raw of readFileSync(file, "utf8").split("\n")) {
    const line = raw.trim();
    if (!line || line.startsWith("#")) continue;
    const index = line.indexOf("=");
    if (index === -1) fail(`Malformed line in version.properties: ${raw}`);
    values[line.slice(0, index).trim()] = line.slice(index + 1).trim();
  }
  const name = values.versionName;
  const code = Number(values.versionCode);
  if (!/^\d+\.\d+\.\d+$/.test(name ?? "")) {
    fail("versionName must be three dot-separated integers, for example 0.2.0.");
  }
  if (!Number.isInteger(code) || code < 1) {
    fail("versionCode must be a positive integer.");
  }
  return { name, code };
}

function ghRelease(tag) {
  const out = quiet(
    `gh release view ${tag} --repo ${repo} --json tagName,assets,isDraft,url 2>/dev/null`,
  );
  if (!out) return null;
  try {
    return JSON.parse(out);
  } catch {
    return null;
  }
}

function assetsFor(tag) {
  return new Set((ghRelease(tag)?.assets ?? []).map((asset) => asset.name));
}

function hasAsset(tag, name) {
  return assetsFor(tag).has(name);
}

function assertCleanMain() {
  const dirty = quiet("git status --porcelain");
  if (dirty) fail("The working tree has uncommitted changes. Commit or stash them first.");
  const branch = quiet("git rev-parse --abbrev-ref HEAD");
  if (branch !== "main") fail(`Publish from main, not ${branch ?? "a detached HEAD"}.`);
  quiet("git fetch --quiet origin main");
  const head = quiet("git rev-parse HEAD");
  const origin = quiet("git rev-parse origin/main");
  if (!head || !origin || head !== origin) {
    fail("HEAD is not pushed to origin/main. Push the release commit first.");
  }
}

// ── Notes ─────────────────────────────────────────────────────────

function previousReleaseTag(currentTag) {
  const tags = (quiet("git tag --list 'v*' --sort=-v:refname") ?? "")
    .split("\n")
    .filter(Boolean)
    .filter((tag) => tag !== currentTag);
  return tags[0] ?? null;
}

function resolveNotes(currentTag, version) {
  const notesFile = option("notes-file");
  if (notesFile) {
    const notesPath = path.resolve(notesFile);
    if (!existsSync(notesPath)) fail(`--notes-file ${notesFile} does not exist.`);
    return readFileSync(notesPath, "utf8");
  }

  console.log(
    "\n(No --notes-file given; drafting release notes from commit subjects.\n" +
      " This is only a starting point. Pass --notes-file for real notes.)\n",
  );
  const previous = previousReleaseTag(currentTag);
  const range = previous ? `${previous}..HEAD` : "HEAD";
  const subjects = (quiet(`git log --no-merges --format=%s ${range}`) ?? "")
    .split("\n")
    .filter(Boolean);
  if (subjects.length === 0) {
    return `No changes recorded since ${previous ?? "the start of history"} for Fathom ${version}.`;
  }
  return [
    `Fathom ${version}.`,
    "",
    "## What's changed",
    "",
    ...subjects.map((subject) => `- ${subject}`),
  ].join("\n");
}

// ── GitHub Release publication ────────────────────────────────────

function publishAsset(tag, version, assetPath) {
  const release = ghRelease(tag);
  if (release) {
    run("gh", ["release", "upload", tag, assetPath, "--repo", repo, "--clobber"]);
    return;
  }

  const notes = resolveNotes(tag, version);
  const notesDir = path.join(root, "build", "release", "github");
  const notesPath = path.join(notesDir, "release-notes.md");
  mkdirSync(notesDir, { recursive: true });
  writeFileSync(notesPath, notes.endsWith("\n") ? notes : `${notes}\n`);
  try {
    run("gh", [
      "release",
      "create",
      tag,
      assetPath,
      "--repo",
      repo,
      "--title",
      `Fathom ${version}`,
      "--notes-file",
      notesPath,
      "--target",
      quiet("git rev-parse HEAD"),
    ]);
  } finally {
    rmSync(notesPath, { force: true });
  }
}

function maybeQueueProvenance(tag, version) {
  const names = assetsFor(tag);
  if (
    !names.has(`Fathom-${version}.apk`) ||
    !names.has(`Fathom-${version}.ipa`)
  ) {
    return;
  }
  try {
    run("gh", [
      "workflow",
      "run",
      "release.yml",
      "--repo",
      repo,
      "--ref",
      "main",
      "-f",
      `tag=${tag}`,
    ]);
    console.log(`Queued release provenance for ${tag} after both binaries were attached.`);
  } catch {
    console.warn(
      `Could not queue release.yml for ${tag}; run it manually once both assets are attached.`,
    );
  }
}

// ── Signature checks ──────────────────────────────────────────────

function androidSdkRoot() {
  const candidates = [
    process.env.ANDROID_SDK_ROOT,
    process.env.ANDROID_HOME,
    (() => {
      const local = path.join(root, "Android", "local.properties");
      if (!existsSync(local)) return null;
      const match = readFileSync(local, "utf8").match(/^sdk\.dir=(.+)$/m);
      return match?.[1]?.trim() ?? null;
    })(),
  ];
  return candidates.find((candidate) => candidate && existsSync(candidate)) ?? null;
}

function verifyApk(file) {
  const sdk = androidSdkRoot();
  if (!sdk) fail("Set ANDROID_SDK_ROOT/ANDROID_HOME or Android/local.properties sdk.dir.");
  const buildToolsRoot = path.join(sdk, "build-tools");
  const buildTools = readdirSync(buildToolsRoot)
    .filter((name) => /^\d/.test(name))
    .sort((a, b) => a.localeCompare(b, undefined, { numeric: true }))
    .at(-1);
  if (!buildTools) fail(`No Android build-tools found under ${buildToolsRoot}.`);
  const apksigner = path.join(buildToolsRoot, buildTools, "apksigner");
  if (!existsSync(apksigner)) fail(`apksigner not found at ${apksigner}.`);

  const output = run(apksigner, ["verify", "--print-certs", file], { capture: true });
  if (/CN=Android Debug/i.test(output)) {
    fail("The APK is signed with the Android debug key; refusing to publish it.");
  }
  console.log(output.split("\n").find((line) => line.includes("SHA-256")) ?? "APK signature verified.");
}

function verifyIpa(file, version, code) {
  const info = execFileSync("unzip", ["-p", file, "Payload/Fathom.app/Info.plist"], {
    cwd: root,
  });
  const temp = path.join(root, "build", "release", "github", ".ipa-info.plist");
  mkdirSync(path.dirname(temp), { recursive: true });
  writeFileSync(temp, info);
  try {
    const short = run("plutil", ["-extract", "CFBundleShortVersionString", "raw", "-o", "-", temp], {
      capture: true,
    }).trim();
    const build = run("plutil", ["-extract", "CFBundleVersion", "raw", "-o", "-", temp], {
      capture: true,
    }).trim();
    if (short !== version || build !== String(code)) {
      fail(`IPA reports ${short} (${build}), expected ${version} (${code}).`);
    }
  } finally {
    rmSync(temp, { force: true });
  }
}

// ── status ────────────────────────────────────────────────────────

function status() {
  const { name, code } = readVersion();
  const tag = `v${name}`;
  const release = ghRelease(tag);
  const names = new Set((release?.assets ?? []).map((asset) => asset.name));
  const expected = [
    ["Android APK", `Fathom-${name}.apk`],
    ["iOS IPA", `Fathom-${name}.ipa`],
  ];

  console.log(`Source version: ${name} (${code})`);
  console.log(`Release:        ${release ? release.url : `${tag} (missing)`}`);
  for (const [label, asset] of expected) {
    console.log(`  ${names.has(asset) ? "✓" : "✗"} ${label.padEnd(12)} ${asset}`);
  }
  const complete = expected.every(([, asset]) => names.has(asset));
  console.log(
    complete
      ? "\nBoth platform assets are attached. The provenance workflow can attest the release."
      : `\nRun \`node tools/release/publish.mjs android --yes\` and/or\n` +
          "`node tools/release/publish.mjs ios --ipa <path> --yes` to fill the gaps.\n" +
          "Signing material never enters GitHub Actions.",
  );
}

// ── android ───────────────────────────────────────────────────────

function publishAndroid() {
  const { name, code } = readVersion();
  const tag = `v${name}`;
  const assetName = `Fathom-${name}.apk`;

  if (hasAsset(tag, assetName)) {
    if (SKIP_EXISTING) {
      console.log(`${tag} already contains ${assetName}; --skip-existing, nothing to do.`);
      return;
    }
    fail(`${tag} already contains ${assetName}. Bump the version or pass --skip-existing.`);
  }

  console.log(`Publishing Android ${name} (versionCode ${code})${YES ? "" : " [dry run]"}\n`);
  if (!YES) {
    console.log("Would run:");
    console.log("  1. ./scripts/release-android.sh       (tests, AAB, signed APK)");
    console.log("  2. verify the APK is signed with the release key");
    console.log(`  3. copy the APK to build/release/github/${assetName}`);
    console.log(`  4. create/upload ${assetName} on GitHub Release ${tag}`);
    console.log("\nRe-run with --yes to actually publish.");
    return;
  }

  assertCleanMain();
  run("./scripts/release-android.sh");

  const built = path.join(root, "build", "release", "android", `Fathom-${name}-${code}.apk`);
  if (!existsSync(built)) fail(`Expected ${path.relative(root, built)} but the build did not produce it.`);
  verifyApk(built);

  const outDir = path.join(root, "build", "release", "github");
  mkdirSync(outDir, { recursive: true });
  const asset = path.join(outDir, assetName);
  cpSync(built, asset);
  console.log(`SHA-256 ${assetName}: ${sha256File(asset)}`);

  publishAsset(tag, name, asset);
  maybeQueueProvenance(tag, name);
  console.log(`\n✓ Android ${name} published on ${tag}.`);
}

// ── ios ───────────────────────────────────────────────────────────

function publishIos() {
  const { name, code } = readVersion();
  const tag = `v${name}`;
  const assetName = `Fathom-${name}.ipa`;
  const ipaArg = option("ipa");

  if (!ipaArg) {
    fail(
      "iOS archiving needs an interactive Xcode keychain and is not built here.\n" +
        "  Export an App Store Connect IPA with scripts/release-ios.sh, then re-run:\n" +
        `  node tools/release/publish.mjs ios --ipa /path/to/Fathom-${name}-${code}.ipa --yes`,
    );
  }
  const ipaPath = path.resolve(ipaArg);
  if (!existsSync(ipaPath)) fail(`--ipa ${ipaArg} does not exist.`);

  if (hasAsset(tag, assetName)) {
    if (SKIP_EXISTING) {
      console.log(`${tag} already contains ${assetName}; --skip-existing, nothing to do.`);
      return;
    }
    fail(`${tag} already contains ${assetName}. Bump the version or pass --skip-existing.`);
  }

  console.log(`Publishing iOS ${name} (build ${code})${YES ? "" : " [dry run]"}\n`);
  if (!YES) {
    console.log("Would run:");
    console.log(`  1. verify ${path.basename(ipaPath)} reports ${name} (${code})`);
    console.log(`  2. copy the IPA to build/release/github/${assetName}`);
    console.log(`  3. create/upload ${assetName} on GitHub Release ${tag}`);
    console.log("\nRe-run with --yes to actually publish.");
    return;
  }

  assertCleanMain();
  verifyIpa(ipaPath, name, code);

  const outDir = path.join(root, "build", "release", "github");
  mkdirSync(outDir, { recursive: true });
  const asset = path.join(outDir, assetName);
  cpSync(ipaPath, asset);
  console.log(`SHA-256 ${assetName}: ${sha256File(asset)}`);

  publishAsset(tag, name, asset);
  maybeQueueProvenance(tag, name);
  console.log(`\n✓ iOS ${name} published on ${tag}.`);
}

// ── entry point ───────────────────────────────────────────────────

const commands = { status, android: publishAndroid, ios: publishIos };
if (!commands[command]) {
  console.error(
    "Usage: node tools/release/publish.mjs <status|android|ios> " +
      "[--yes] [--notes-file <path>] [--ipa <path>] [--skip-existing]",
  );
  process.exit(1);
}
await commands[command]();
