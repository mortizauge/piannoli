#!/bin/sh
# Fetch the public RetroArch joypad autoconfig library
# into app/src/main/assets/autoconfig/android/.
#
# Run before building if the assets dir is empty or you want to refresh
# against the latest upstream state. The files are not tracked in git; this
# script is the source of truth for populating them.

set -e

SOURCE_URL="https://github.com/libretro/retroarch-joypad-autoconfig.git"
SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
REPO_ROOT=$(cd "$SCRIPT_DIR/.." && pwd)
ASSETS_DIR="$REPO_ROOT/app/src/main/assets/autoconfig/android"
SOURCE_DIR="$SCRIPT_DIR/.autoconfig-source"

if [ -e "$SOURCE_DIR" ]; then
  echo "Refusing to overwrite existing $SOURCE_DIR; remove it after inspecting its contents." >&2
  exit 1
fi

trap 'rm -rf "$SOURCE_DIR"' EXIT

echo "Cloning $SOURCE_URL into $SOURCE_DIR..."
git clone --depth 1 "$SOURCE_URL" "$SOURCE_DIR"

SOURCE_SHA=$(git -C "$SOURCE_DIR" rev-parse --short HEAD)
SOURCE_COUNT=$(find "$SOURCE_DIR/android" -name '*.cfg' | wc -l | tr -d ' ')
if [ "$SOURCE_COUNT" -eq 0 ]; then
  echo "No Android controller profiles found in upstream autoconfig." >&2
  exit 1
fi

echo "Upstream commit: $SOURCE_SHA"
echo "Source cfg count: $SOURCE_COUNT"

mkdir -p "$ASSETS_DIR"
rm -f "$ASSETS_DIR"/*.cfg
cp "$SOURCE_DIR/android"/*.cfg "$ASSETS_DIR/"
cp "$SOURCE_DIR/COPYING" "$ASSETS_DIR/COPYING"

INSTALLED_COUNT=$(ls "$ASSETS_DIR"/*.cfg 2>/dev/null | wc -l | tr -d ' ')
echo "Installed $INSTALLED_COUNT cfgs into $ASSETS_DIR"
