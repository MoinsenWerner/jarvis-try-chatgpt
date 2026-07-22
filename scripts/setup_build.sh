#!/usr/bin/env bash
set -Eeuo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}"
GRADLE_VERSION="8.11.1"
CMDLINE_TOOLS_VERSION="11076708"

mkdir -p "$ROOT/.tooling"

if [[ $EUID -eq 0 ]]; then
  SUDO=""
elif command -v sudo >/dev/null 2>&1; then
  SUDO="sudo"
else
  echo "Root-Rechte oder sudo werden benötigt." >&2
  exit 1
fi

run_root() {
  if [[ -n "$SUDO" ]]; then
    "$SUDO" "$@"
  else
    "$@"
  fi
}

create_isolated_apt_sources() {
  if [[ ! -r /etc/os-release ]]; then
    echo "/etc/os-release fehlt; Debian oder Ubuntu konnte nicht erkannt werden." >&2
    return 1
  fi

  # shellcheck disable=SC1091
  source /etc/os-release

  local distro_id="${ID:-}"
  local codename="${VERSION_CODENAME:-}"
  local architecture
  architecture="$(dpkg --print-architecture)"

  if [[ -z "$codename" ]]; then
    echo "VERSION_CODENAME konnte aus /etc/os-release nicht ermittelt werden." >&2
    return 1
  fi

  APT_SOURCE_FILE="$(mktemp /tmp/jarvis-apt-sources.XXXXXX.list)"

  case "$distro_id" in
    debian)
      cat > "$APT_SOURCE_FILE" <<EOF
deb https://deb.debian.org/debian ${codename} main
deb https://deb.debian.org/debian ${codename}-updates main
deb https://security.debian.org/debian-security ${codename}-security main
EOF
      ;;

    ubuntu)
      local ubuntu_archive="https://archive.ubuntu.com/ubuntu"
      if [[ "$architecture" != "amd64" && "$architecture" != "i386" ]]; then
        ubuntu_archive="https://ports.ubuntu.com/ubuntu-ports"
      fi

      cat > "$APT_SOURCE_FILE" <<EOF
deb ${ubuntu_archive} ${codename} main universe
deb ${ubuntu_archive} ${codename}-updates main universe
deb https://security.ubuntu.com/ubuntu ${codename}-security main universe
EOF
      ;;

    *)
      echo "Nicht unterstützte Distribution: ${distro_id:-unbekannt}. Unterstützt werden Debian und Ubuntu." >&2
      return 1
      ;;
  esac

  echo "Verwende für Jarvis temporär ausschließlich offizielle ${distro_id^}-Paketquellen (${codename})."
}

cleanup() {
  if [[ -n "${APT_SOURCE_FILE:-}" && -f "$APT_SOURCE_FILE" ]]; then
    rm -f "$APT_SOURCE_FILE"
  fi
}
trap cleanup EXIT

install_system_dependencies() {
  if [[ "${JARVIS_USE_SYSTEM_APT_SOURCES:-0}" == "1" ]]; then
    echo "JARVIS_USE_SYSTEM_APT_SOURCES=1: Verwende die systemweit konfigurierten APT-Quellen."
    run_root apt-get update
    run_root apt-get install -y --no-install-recommends \
      openjdk-17-jdk curl unzip python3 ca-certificates
    return
  fi

  create_isolated_apt_sources

  local apt_options=(
    -o "Dir::Etc::sourcelist=$APT_SOURCE_FILE"
    -o "Dir::Etc::sourceparts=-"
    -o "APT::Get::List-Cleanup=0"
  )

  run_root apt-get "${apt_options[@]}" update
  run_root apt-get "${apt_options[@]}" install -y --no-install-recommends \
    openjdk-17-jdk curl unzip python3 ca-certificates
}

install_system_dependencies

mkdir -p "$SDK/cmdline-tools" "$ROOT/.tooling"
if [[ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]]; then
  curl --fail --location --retry 3 --retry-delay 2 \
    "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip" \
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
  curl --fail --location --retry 3 --retry-delay 2 \
    "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip" \
    -o "$ROOT/.tooling/gradle.zip"
  unzip -q -o "$ROOT/.tooling/gradle.zip" -d "$ROOT/.tooling"
fi

cd "$ROOT"
"$GRADLE_HOME/bin/gradle" wrapper \
  --gradle-version "$GRADLE_VERSION" \
  --distribution-type bin
chmod +x gradlew
./gradlew --no-daemon clean assembleDebug

echo "APK: $ROOT/app/build/outputs/apk/debug/app-debug.apk"
