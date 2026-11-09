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
//   node tools/release/publish.mjs android [--yes] [--push] [--notes-file <path>]
//     Builds a signed release APK, publishes it to the self-hosted F-Droid
//     repo (metadata, signed index and website mirror), and attaches it to
//     the GitHub Release as Fathom-<version>.apk.
//
//   node tools/release/publish.mjs ios [--ipa <path>] [--yes] [--push]
//     Builds an unsigned AltStore Classic IPA when --ipa is omitted, publishes
//     it to the AltStore source and website, and attaches it to the GitHub
//     Release as Fathom-<version>.ipa. --ipa can supply an already-built
//     unsigned AltStore IPA; App Store exports use a separate handoff.
//
// Flags:
//   --yes             Actually build/sign/publish. Without it the command is
//                     a dry run and changes nothing.
//   --push            Push the channel commit to origin/main after publishing.
//                     Without it the channel files are committed locally only.
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
  statSync,
  writeFileSync,
} from "node:fs";
import { execFileSync, execSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import path from "node:path";

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, "..", "..");
const repo = "ewanc26/fathom";
const SITE_BASE = (process.env.FATHOM_SITE_BASE ?? "https://ewanc26.github.io/fathom").replace(
  /\/+$/,
  "",
);
const FDROID_DIR = path.join(root, "Android", "fdroid-repo");
const ALTSTORE_DIR = path.join(root, "iOS", "altstore");
const WEBSITE_ALTSTORE = path.join(root, "website", "altstore");

const args = process.argv.slice(2);
const command = args[0];
const flag = (name) => args.includes(`--${name}`);
const option = (name) => {
  const index = args.indexOf(`--${name}`);
  return index === -1 ? undefined : args[index + 1];
};

const YES = flag("yes");
const PUSH = flag("push");
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

function commitAndMaybePush(paths, message) {
  const status = execFileSync("git", ["status", "--porcelain", "--", ...paths], {
    cwd: root,
    encoding: "utf8",
  }).trim();
  if (!status) {
    console.log(`No channel changes to commit for ${message}.`);
    return;
  }
  run("git", ["add", "--", ...paths]);
  run("git", ["commit", "-m", message]);
  if (PUSH) {
    run("git", ["push", "origin", "main"]);
  } else {
    console.log("Channel changes committed locally; pass --push to publish the site update.");
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

function altstoreLatest() {
  const file = path.join(ALTSTORE_DIR, "source.json");
  if (!existsSync(file)) return null;
  try {
    return JSON.parse(readFileSync(file, "utf8")).apps?.[0]?.versions?.[0] ?? null;
  } catch {
    return null;
  }
}

function fdroidLatest() {
  const file = path.join(FDROID_DIR, "metadata", "uk.ewancroft.fathom.yml");
  if (!existsSync(file)) return null;
  const yaml = readFileSync(file, "utf8");
  const version = yaml.match(/CurrentVersion:\s*'?([^'\n]+)'?/);
  const code = yaml.match(/CurrentVersionCode:\s*(\d+)/);
  return version && code ? { name: version[1].trim(), code: Number(code[1]) } : null;
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

  const altstore = altstoreLatest();
  const fdroid = fdroidLatest();

  console.log(`Source version: ${name} (${code})`);
  console.log(`Release:        ${release ? release.url : `${tag} (missing)`}`);
  for (const [label, asset] of expected) {
    console.log(`  ${names.has(asset) ? "✓" : "✗"} ${label.padEnd(12)} ${asset}`);
  }
  console.log("Channels:");
  console.log(
    `  ${altstore?.version === name && altstore?.buildVersion === String(code) ? "✓" : "✗"} AltStore    ` +
      (altstore ? `${altstore.version} (${altstore.buildVersion})` : "unpublished"),
  );
  console.log(
    `  ${fdroid?.name === name && fdroid?.code === code ? "✓" : "✗"} F-Droid     ` +
      (fdroid ? `${fdroid.name} (${fdroid.code})` : "unpublished"),
  );
  const complete = expected.every(([, asset]) => names.has(asset));
  console.log(
    complete
      ? "\nBoth platform assets are attached. The provenance workflow can attest the release."
      : `\nRun \`node tools/release/publish.mjs android --yes --push\` and/or\n` +
          "`node tools/release/publish.mjs ios --yes --push` to fill the gaps.\n" +
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
    console.log(`  3. copy the APK to Android/fdroid-repo/repo/${assetName}`);
    console.log(`  4. set the metadata to ${name} (${code}) and run fdroid update --clean`);
    console.log("  5. mirror Android/fdroid-repo/repo to website/fdroid/repo");
    console.log(`  6. copy the APK to build/release/github/${assetName}`);
    console.log(`  7. create/upload ${assetName} on GitHub Release ${tag}`);
    console.log("  8. commit the channel files" + (PUSH ? " and push" : " (local only; pass --push)"));
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

  const configPath = path.join(FDROID_DIR, "config.yml");
  if (!existsSync(configPath)) {
    fail("Android/fdroid-repo/config.yml is missing. Run ./scripts/setup-release-keys.sh first.");
  }
  if (!quiet("command -v fdroid")) {
    fail("fdroidserver is not installed. See Android/fdroid-repo/README.md.");
  }

  const repoDir = path.join(FDROID_DIR, "repo");
  mkdirSync(repoDir, { recursive: true });
  cpSync(built, path.join(repoDir, assetName));

  const metadataPath = path.join(FDROID_DIR, "metadata", "uk.ewancroft.fathom.yml");
  if (!existsSync(metadataPath)) fail(`Missing ${path.relative(root, metadataPath)}.`);
  let metadata = readFileSync(metadataPath, "utf8");
  metadata = metadata
    .replace(/CurrentVersion:\s*'[^']*'/, `CurrentVersion: '${name}'`)
    .replace(/CurrentVersionCode:\s*\d+/, `CurrentVersionCode: ${code}`);
  writeFileSync(metadataPath, metadata);

  run("fdroid", ["update", "--clean"], { cwd: FDROID_DIR });

  const websiteRepo = path.join(root, "website", "fdroid", "repo");
  rmSync(websiteRepo, { recursive: true, force: true });
  mkdirSync(websiteRepo, { recursive: true });
  cpSync(path.join(FDROID_DIR, "repo"), websiteRepo, {
    recursive: true,
    filter: (src) => !src.endsWith(`${path.sep}status`),
  });

  publishAsset(tag, name, asset);
  commitAndMaybePush(
    [
      "Android/fdroid-repo/metadata/uk.ewancroft.fathom.yml",
      "Android/fdroid-repo/repo",
      "website/fdroid/repo",
    ],
    `chore(android,fdroid): release ${name} (versionCode ${code})`,
  );
  maybeQueueProvenance(tag, name);
  console.log(`\n✓ Android ${name} published: F-Droid repo updated, ${tag} released on GitHub.`);
}

// ── ios ───────────────────────────────────────────────────────────

function publishIos() {
  const { name, code } = readVersion();
  const tag = `v${name}`;
  const assetName = `Fathom-${name}.ipa`;
  const ipaArg = option("ipa");
  const builtIpa = path.join(
    root,
    "build",
    "release",
    "ios-altstore",
    `Fathom-${name}-${code}.ipa`,
  );
  const ipaPath = ipaArg ? path.resolve(ipaArg) : builtIpa;

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
    console.log(
      ipaArg
        ? `  1. verify ${path.basename(ipaPath)} reports ${name} (${code})`
        : "  1. ./scripts/build-altstore-ipa.sh  (unsigned AltStore IPA)",
    );
    console.log(`  2. copy the IPA to website/altstore/${assetName}`);
    console.log(`  3. prepend a versions[] entry to iOS/altstore/source.json`);
    console.log("  4. mirror the source to website/altstore/source.json");
    console.log(`  5. create/upload ${assetName} on GitHub Release ${tag}`);
    console.log("  6. commit the channel files" + (PUSH ? " and push" : " (local only; pass --push)"));
    console.log("\nRe-run with --yes to actually publish.");
    return;
  }

  assertCleanMain();
  if (!ipaArg) {
    run("./scripts/build-altstore-ipa.sh");
  }
  if (!existsSync(ipaPath)) fail(`IPA not found: ${ipaPath}`);
  verifyIpa(ipaPath, name, code);

  const outDir = path.join(root, "build", "release", "github");
  mkdirSync(outDir, { recursive: true });
  const asset = path.join(outDir, assetName);
  cpSync(ipaPath, asset);
  console.log(`SHA-256 ${assetName}: ${sha256File(asset)}`);

  mkdirSync(WEBSITE_ALTSTORE, { recursive: true });
  cpSync(asset, path.join(WEBSITE_ALTSTORE, assetName));
  const iconSource = path.join(root, "website", "icon.png");
  cpSync(iconSource, path.join(WEBSITE_ALTSTORE, "icon.png"));
  cpSync(iconSource, path.join(ALTSTORE_DIR, "icon.png"));

  const sourcePath = path.join(ALTSTORE_DIR, "source.json");
  const mirrorPath = path.join(WEBSITE_ALTSTORE, "source.json");
  if (!existsSync(sourcePath)) fail(`Missing ${path.relative(root, sourcePath)}.`);
  const data = JSON.parse(readFileSync(sourcePath, "utf8"));
  const app = data.apps?.[0];
  if (!app) fail("AltStore source has no apps[0].");

  const size = statSync(asset).size;
  const sha256 = sha256File(asset);
  const date = new Date().toISOString().slice(0, 10);
  const downloadURL = `${SITE_BASE}/altstore/${assetName}`;
  const notes = resolveNotes(tag, name).trim();

  app.version = name;
  app.buildVersion = String(code);
  app.versionDate = date;
  app.size = size;
  app.sha256 = sha256;
  app.downloadURL = downloadURL;
  app.versionDescription = notes;
  app.versions = (app.versions ?? []).filter(
    (entry) => !(entry.version === name && String(entry.buildVersion) === String(code)),
  );
  app.versions.unshift({
    version: name,
    buildVersion: String(code),
    date,
    localizedDescription: notes.slice(0, 4000),
    downloadURL,
    size,
    sha256,
    minOSVersion: "18.0",
  });
  data.version = name;
  data.buildVersion = String(code);

  for (const target of [sourcePath, mirrorPath]) {
    writeFileSync(target, `${JSON.stringify(data, null, 2)}\n`);
  }
  run("node", [path.join(here, "validate-altstore.mjs")]);

  publishAsset(tag, name, asset);
  commitAndMaybePush(
    ["iOS/altstore/source.json", "website/altstore"],
    `chore(ios,altstore): release ${name} (build ${code})`,
  );
  maybeQueueProvenance(tag, name);
  console.log(`\n✓ iOS ${name} published: AltStore source updated, ${tag} released on GitHub.`);
}

// ── entry point ───────────────────────────────────────────────────

const commands = { status, android: publishAndroid, ios: publishIos };
if (!commands[command]) {
  console.error(
    "Usage: node tools/release/publish.mjs <status|android|ios> " +
      "[--yes] [--push] [--notes-file <path>] [--ipa <path>] [--skip-existing]",
  );
  process.exit(1);
}
await commands[command]();
