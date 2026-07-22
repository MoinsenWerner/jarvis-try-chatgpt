#!/usr/bin/env bash
set -Eeuo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}"
GRADLE_VERSION="8.11.1"
CMDLINE_TOOLS_VERSION="11076708"

if [[ $EUID -eq 0 ]]; then
  SUDO=""
elif command -v sudo >/dev/null 2>&1; then
  SUDO="sudo"
else
  echo "Root-Rechte oder sudo werden benötigt." >&2
  exit 1
fi

$SUDO apt-get update
$SUDO apt-get install -y --no-install-recommends openjdk-17-jdk curl unzip python3 ca-certificates

mkdir -p "$SDK/cmdline-tools" "$ROOT/.tooling"
if [[ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]]; then
  curl -fL "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip" \
    -o "$ROOT/.tooling/commandline-tools.zip"
  rm -rf "$ROOT/.tooling/cmdline-unpack" "$SDK/cmdline-tools/latest"
  mkdir -p "$ROOT/.tooling/cmdline-unpack"
  unzip -q "$ROOT/.tooling/commandline-tools.zip" -d "$ROOT/.tooling/cmdline-unpack"
  mv "$ROOT/.tooling/cmdline-unpack/cmdline-tools" "$SDK/cmdline-tools/latest"
fi

export ANDROID_SDK_ROOT="$SDK"
export ANDROID_HOME="$SDK"
export PATH="$SDK/cmdline-tools/latest/bin:$SDK/platform-tools:$PATH"

yes | sdkmanager --licenses >/dev/null || true
sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"
printf 'sdk.dir=%s\n' "$SDK" > "$ROOT/local.properties"

python3 "$ROOT/ml/train_model.py" \
  --dataset "$ROOT/ml/dataset.jsonl" \
  --output "$ROOT/app/src/main/assets/model/local_model.json" \
  --min-accuracy 0.80

GRADLE_HOME="$ROOT/.tooling/gradle-$GRADLE_VERSION"
if [[ ! -x "$GRADLE_HOME/bin/gradle" ]]; then
  curl -fL "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip" \
    -o "$ROOT/.tooling/gradle.zip"
  unzip -q -o "$ROOT/.tooling/gradle.zip" -d "$ROOT/.tooling"
fi

cd "$ROOT"
"$GRADLE_HOME/bin/gradle" wrapper --gradle-version "$GRADLE_VERSION" --distribution-type bin
chmod +x gradlew
./gradlew --no-daemon clean assembleDebug

echo "APK: $ROOT/app/build/outputs/apk/debug/app-debug.apk"
