#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "usage: $0 /path/to/android.jar" >&2
  exit 2
fi

repo_root="$(cd "$(dirname "$0")/../../.." && pwd)"
android_jar="$1"
aar="$repo_root/Assets/Plugins/Android/robotassistant_lib-i18n-release.aar"
fbo_aar="$repo_root/Assets/Plugins/Android/fbolibrary.aar"
source_file="$repo_root/Native/robotassistant_lib/src/main/java/com/picovr/robotassistantlib/MediaDecoder.java"
work_dir="$(mktemp -d /tmp/xrobot-media-decoder.XXXXXX)"
trap 'rm -rf "$work_dir"' EXIT

unzip -q "$aar" -d "$work_dir/aar"
unzip -p "$fbo_aar" classes.jar > "$work_dir/fbolibrary.jar"
mkdir -p "$work_dir/classes"
javac -source 8 -target 8 \
  -classpath "$android_jar:$work_dir/fbolibrary.jar" \
  -d "$work_dir/classes" \
  "$source_file"
jar uf "$work_dir/aar/classes.jar" \
  -C "$work_dir/classes" com/picovr/robotassistantlib/MediaDecoder.class
(cd "$work_dir/aar" && zip -q -r "$work_dir/rebuilt.aar" .)
cp "$work_dir/rebuilt.aar" "$aar"
