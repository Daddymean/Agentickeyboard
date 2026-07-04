#!/usr/bin/env sh

# Lightweight Gradle bootstrap script for environments where the binary
# gradle-wrapper.jar cannot be committed by the current authoring tool.
# It reads gradle/wrapper/gradle-wrapper.properties, downloads the configured
# Gradle distribution, and delegates all arguments to that Gradle executable.

set -eu

APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
PROPERTIES_FILE="$APP_HOME/gradle/wrapper/gradle-wrapper.properties"

if [ ! -f "$PROPERTIES_FILE" ]; then
  echo "Missing Gradle wrapper properties: $PROPERTIES_FILE" >&2
  exit 1
fi

DISTRIBUTION_URL=$(sed -n 's/^distributionUrl=//p' "$PROPERTIES_FILE" | tail -n 1 | sed 's#\\:#:#g')
if [ -z "$DISTRIBUTION_URL" ]; then
  DISTRIBUTION_URL="https://services.gradle.org/distributions/gradle-9.1.0-bin.zip"
fi

ZIP_NAME=$(basename "$DISTRIBUTION_URL")
GRADLE_DIR_NAME=$(printf '%s' "$ZIP_NAME" | sed 's/\.zip$//' | sed 's/-bin$//' | sed 's/-all$//')
BOOTSTRAP_ROOT="${GRADLE_USER_HOME:-$HOME/.gradle}/bootstrap-dists"
DIST_DIR="$BOOTSTRAP_ROOT/$GRADLE_DIR_NAME"
ZIP_PATH="$BOOTSTRAP_ROOT/$ZIP_NAME"
GRADLE_BIN="$DIST_DIR/bin/gradle"

if [ ! -x "$GRADLE_BIN" ]; then
  mkdir -p "$BOOTSTRAP_ROOT"
  echo "Downloading $DISTRIBUTION_URL" >&2
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL "$DISTRIBUTION_URL" -o "$ZIP_PATH"
  elif command -v wget >/dev/null 2>&1; then
    wget -q "$DISTRIBUTION_URL" -O "$ZIP_PATH"
  else
    echo "curl or wget is required to download Gradle." >&2
    exit 1
  fi

  if ! command -v unzip >/dev/null 2>&1; then
    echo "unzip is required to extract Gradle." >&2
    exit 1
  fi

  unzip -q -o "$ZIP_PATH" -d "$BOOTSTRAP_ROOT"
fi

exec "$GRADLE_BIN" "$@"
