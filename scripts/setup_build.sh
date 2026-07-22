#!/usr/bin/env bash
set -Eeuo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}"
GRADLE_VERSION="8.11.1"
CMDLINE_TOOLS_VERSION="15859902"
CMDLINE_TOOLS_SHA256="4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583"
JDK_MAJOR="17"
JDK_DIR="$ROOT/.tooling/jdk-$JDK_MAJOR"
CMDLINE_TOOLS_DIR="$SDK/cmdline-tools/jarvis-$CMDLINE_TOOLS_VERSION"

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
      cat > "$APT_SOURCE_FILE" <<APT_EOF
deb https://deb.debian.org/debian ${codename} main
deb https://deb.debian.org/debian ${codename}-updates main
deb https://security.debian.org/debian-security ${codename}-security main
APT_EOF
      ;;

    ubuntu)
      local ubuntu_archive="https://archive.ubuntu.com/ubuntu"
      local ubuntu_security="https://security.ubuntu.com/ubuntu"

      if [[ "$architecture" != "amd64" && "$architecture" != "i386" ]]; then
        ubuntu_archive="https://ports.ubuntu.com/ubuntu-ports"
        ubuntu_security="$ubuntu_archive"
      fi

      cat > "$APT_SOURCE_FILE" <<APT_EOF
deb ${ubuntu_archive} ${codename} main universe
deb ${ubuntu_archive} ${codename}-updates main universe
deb ${ubuntu_security} ${codename}-security main universe
APT_EOF
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
  if [[ -n "${JDK_EXTRACT_DIR:-}" && -d "$JDK_EXTRACT_DIR" ]]; then
    rm -rf "$JDK_EXTRACT_DIR"
  fi
}
trap cleanup EXIT

install_system_dependencies() {
  local packages=(curl unzip python3 ca-certificates tar gzip)

  if [[ "${JARVIS_USE_SYSTEM_APT_SOURCES:-0}" == "1" ]]; then
    echo "JARVIS_USE_SYSTEM_APT_SOURCES=1: Verwende die systemweit konfigurierten APT-Quellen."
    run_root apt-get update
    run_root apt-get install -y --no-install-recommends "${packages[@]}"
    return
  fi

  create_isolated_apt_sources

  local apt_options=(
    -o "Dir::Etc::sourcelist=$APT_SOURCE_FILE"
    -o "Dir::Etc::sourceparts=-"
    -o "APT::Get::List-Cleanup=0"
  )

  run_root apt-get "${apt_options[@]}" update
  run_root apt-get "${apt_options[@]}" install -y --no-install-recommends "${packages[@]}"
}

map_adoptium_architecture() {
  case "$(uname -m)" in
    x86_64|amd64) echo "x64" ;;
    aarch64|arm64) echo "aarch64" ;;
    armv7l|armhf) echo "arm" ;;
    ppc64le) echo "ppc64le" ;;
    s390x) echo "s390x" ;;
    *)
      echo "Nicht unterstützte CPU-Architektur für das JDK: $(uname -m)" >&2
      return 1
      ;;
  esac
}

java_major_from_binary() {
  local java_binary="$1"
  local java_version java_major
  java_version="$($java_binary -version 2>&1 | awk -F '"' '/version/ { print $2; exit }')"
  java_major="${java_version%%.*}"

  if [[ "$java_major" == "1" ]]; then
    java_major="$(printf '%s' "$java_version" | cut -d. -f2)"
  fi

  printf '%s' "$java_major"
}

install_pinned_jdk() {
  if [[ -x "$JDK_DIR/bin/java" && -x "$JDK_DIR/bin/javac" ]]; then
    local installed_major
    installed_major="$(java_major_from_binary "$JDK_DIR/bin/java")"
    if [[ "$installed_major" == "$JDK_MAJOR" ]]; then
      echo "Verwende bereits installiertes projektlokales JDK $JDK_MAJOR."
    else
      echo "Projektlokales JDK hat Version $installed_major statt $JDK_MAJOR und wird ersetzt."
      rm -rf "$JDK_DIR"
    fi
  fi

  if [[ ! -x "$JDK_DIR/bin/java" ]]; then
    local adoptium_arch archive extracted
    adoptium_arch="$(map_adoptium_architecture)"
    archive="$ROOT/.tooling/temurin-jdk-${JDK_MAJOR}-${adoptium_arch}.tar.gz"

    echo "Lade Eclipse Temurin JDK $JDK_MAJOR für ${adoptium_arch} herunter."
    curl --fail --location --retry 3 --retry-delay 2 \
      "https://api.adoptium.net/v3/binary/latest/${JDK_MAJOR}/ga/linux/${adoptium_arch}/jdk/hotspot/normal/eclipse?project=jdk" \
      -o "$archive"

    JDK_EXTRACT_DIR="$(mktemp -d "$ROOT/.tooling/jdk-extract.XXXXXX")"
    tar -xzf "$archive" -C "$JDK_EXTRACT_DIR"
    extracted="$(find "$JDK_EXTRACT_DIR" -mindepth 1 -maxdepth 1 -type d -print -quit)"

    if [[ -z "$extracted" || ! -x "$extracted/bin/java" ]]; then
      echo "Das heruntergeladene JDK-Archiv besitzt nicht die erwartete Struktur." >&2
      return 1
    fi

    rm -rf "$JDK_DIR"
    mv "$extracted" "$JDK_DIR"
    rm -f "$archive"
  fi

  export JAVA_HOME="$JDK_DIR"
  export PATH="$JAVA_HOME/bin:$PATH"
  hash -r

  local active_major
  active_major="$(java_major_from_binary "$JAVA_HOME/bin/java")"
  if [[ "$active_major" != "$JDK_MAJOR" ]]; then
    echo "Jarvis benötigt für AGP 8.9/Gradle 8.11 JDK $JDK_MAJOR; aktiv ist $active_major." >&2
    return 1
  fi

  echo "Verwende projektlokales Java: $($JAVA_HOME/bin/java -version 2>&1 | head -n 1)"
}

install_android_command_line_tools() {
  local archive="$ROOT/.tooling/command-line-tools-${CMDLINE_TOOLS_VERSION}.zip"
  local extract_dir="$ROOT/.tooling/cmdline-unpack-${CMDLINE_TOOLS_VERSION}"

  if [[ -x "$CMDLINE_TOOLS_DIR/bin/sdkmanager" ]]; then
    return
  fi

  echo "Lade Android Command-line Tools ${CMDLINE_TOOLS_VERSION} herunter."
  curl --fail --location --retry 3 --retry-delay 2 \
    "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip" \
    -o "$archive"

  echo "${CMDLINE_TOOLS_SHA256}  ${archive}" | sha256sum --check --status || {
    echo "Die Prüfsumme der Android Command-line Tools stimmt nicht." >&2
    return 1
  }

  rm -rf "$extract_dir" "$CMDLINE_TOOLS_DIR"
  mkdir -p "$extract_dir" "$CMDLINE_TOOLS_DIR"
  unzip -q "$archive" -d "$extract_dir"
  mv "$extract_dir/cmdline-tools"/* "$CMDLINE_TOOLS_DIR/"
  rm -rf "$extract_dir"
  rm -f "$archive"
}

install_system_dependencies
install_pinned_jdk

mkdir -p "$SDK/cmdline-tools" "$ROOT/.tooling"
install_android_command_line_tools

export ANDROID_SDK_ROOT="$SDK"
export ANDROID_HOME="$SDK"
export PATH="$CMDLINE_TOOLS_DIR/bin:$SDK/platform-tools:$PATH"

SDKMANAGER="$CMDLINE_TOOLS_DIR/bin/sdkmanager"
yes | "$SDKMANAGER" --sdk_root="$SDK" --licenses >/dev/null || true
"$SDKMANAGER" --sdk_root="$SDK" \
  "platform-tools" \
  "platforms;android-35" \
  "build-tools;35.0.0"
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
"$GRADLE_HOME/bin/gradle" --no-daemon wrapper \
  --gradle-version "$GRADLE_VERSION" \
  --distribution-type bin
chmod +x gradlew

JAVA_HOME="$JDK_DIR" PATH="$JDK_DIR/bin:$PATH" \
  ./gradlew --no-daemon --stacktrace clean assembleDebug

echo "APK: $ROOT/app/build/outputs/apk/debug/app-debug.apk"
