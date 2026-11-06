#!/bin/bash

if [ -z "$1" ]; then
  echo "Usage: $0 \"v[0-9].[0-9].[0-9]\""
  exit 1
fi

VERSION_STRING="$1"
if [[ ! $VERSION_STRING =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "Invalid version string format. Must be in the format 'v*.*.*' (e.g., v1.2.3)"
  exit 1
fi

VERSION_NAME="${VERSION_STRING#v}"
IFS='.' read -r MAJOR MINOR PATCH <<< "$VERSION_NAME"
VERSION_CODE=$((MAJOR * 100 + MINOR * 10 + PATCH))

echo "Releasing in 4s..."
echo "- versionName: $VERSION_NAME"
echo "- versionCode: $VERSION_CODE"
sleep 4

gsed -i "/def tagName =/c\def tagName = '$VERSION_NAME'" app/build.gradle
gsed -i "/versionCode/c\        versionCode $VERSION_CODE" app/build.gradle
gsed -i "/CurrentVersion: /c\CurrentVersion: $VERSION_NAME" .fdroid.yml
gsed -i "/CurrentVersionCode: /c\CurrentVersionCode: $VERSION_CODE" .fdroid.yml

git commit -am "bump version"
git tag $VERSION_STRING
git push
git push origin $VERSION_STRING
