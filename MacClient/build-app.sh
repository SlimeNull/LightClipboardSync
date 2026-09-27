#!/bin/sh
set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
if [ -z "${DEVELOPER_DIR:-}" ] && [ -d /Applications/Xcode.app/Contents/Developer ]; then
    export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
fi
xcodebuild -quiet -project "$root/LightClipboardSync.xcodeproj" \
    -scheme LightClipboardSync -configuration Release \
    -derivedDataPath "$root/.build/xcode" CODE_SIGNING_ALLOWED=NO build

bundle="$root/dist/LightClipboardSync.app"
mkdir -p "$root/dist"
ditto "$root/.build/xcode/Build/Products/Release/LightClipboardSync.app" "$bundle"
echo "$bundle"
