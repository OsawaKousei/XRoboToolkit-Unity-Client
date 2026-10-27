#!/usr/bin/env bash
set -euo pipefail

project_dir=$(cd "$(dirname "$0")/../../.." && pwd)
android_jar="$project_dir/../Unity/Hub/Editor/2022.3.16f1/Editor/Data/PlaybackEngines/AndroidPlayer/SDK/platforms/android-32/android.jar"
javac_bin="$project_dir/../Unity/Hub/Editor/2022.3.16f1/Editor/Data/PlaybackEngines/AndroidPlayer/OpenJDK/bin/javac"
jar_bin="$project_dir/../Unity/Hub/Editor/2022.3.16f1/Editor/Data/PlaybackEngines/AndroidPlayer/OpenJDK/bin/jar"
source_file="$project_dir/Native/fbolibrary/src/main/java/com/picoxr/fbolibrary/FBOPlugin.java"
target_aar="$project_dir/Assets/Plugins/Android/fbolibrary.aar"
build_dir=$(mktemp -d)
trap 'rm -rf "$build_dir"' EXIT

unzip -q "$target_aar" -d "$build_dir/aar"
mkdir -p "$build_dir/classes"
"$javac_bin" -source 8 -target 8 -classpath "$android_jar" \
  -d "$build_dir/classes" "$source_file"
"$jar_bin" cf "$build_dir/aar/classes.jar" -C "$build_dir/classes" .
(
  cd "$build_dir/aar"
  zip -q -r "$build_dir/fbolibrary.aar" .
)
cp "$build_dir/fbolibrary.aar" "$target_aar"
echo "Rebuilt $target_aar"
