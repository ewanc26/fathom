#!/usr/bin/env bash
#
# Links every Kotlin/Native slice and combines them into one XCFramework that
# Xcode can resolve for both simulator and device.
#
# The output is gitignored: it is a build product, and a checked-in binary
# would go stale the moment shared/ changed without anyone noticing.
#
#   ./scripts/build-xcframework.sh          # debug slices
#   JAVA_HOME=/path/to/jdk ./scripts/...   # when 17-23 is not your default
#
# Gradle 8.13 does not run on JDKs newer than 23, so set JAVA_HOME when the
# system default is newer.

set -euo pipefail

cd "$(dirname "$0")/.."

# Two slices, not three. Xcode refuses to combine a device slice with two
# simulator slices that represent the same platform — `ios-arm64-simulator`
# and `ios-x86_64-simulator` are "two equivalent library definitions" — so
# only the Apple-silicon simulator is included. An Intel Mac cannot run the
# shared core from this framework and would need iosX64 built on its own.
SLICES=(
  "iosArm64"
  "iosSimulatorArm64"
)

TASKS=()
for slice in "${SLICES[@]}"; do
  TASKS+=(":shared:linkDebugFramework${slice^}")
done

( cd Android && ./gradlew "${TASKS[@]}" --console=plain )

rm -rf shared/FathomShared.xcframework

ARGS=()
for slice in "${SLICES[@]}"; do
  ARGS+=("-framework" "shared/build/bin/${slice}/debugFramework/FathomShared.framework")
done

xcodebuild -create-xcframework "${ARGS[@]}" -output shared/FathomShared.xcframework

echo "Wrote shared/FathomShared.xcframework"
