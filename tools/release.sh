#!/bin/bash
# Publish a release of the app: the phones running it update themselves (update/Updater.kt).
#
#   tools/release.sh patch "What changed, in a sentence or two"
#
# The first argument is the step: patch (0.3.0 to 0.3.1) for fixes and small features, the
# usual one; minor or major only when the owner decides a release is that big; or an exact
# version (1.2.3).
#
# Needs keystore.properties (the release key, never committed), a clean checkout of main that is
# up to date, and gh signed in with push access. It bumps app/version.properties, tests and builds
# the signed APK, commits and tags the version, and creates a GitHub release with the APK and
# latest.json. Once published, a release can't be taken back from phones that installed it:
# publish only what you would run yourself.
set -euo pipefail
cd "$(dirname "$0")/.."
step="${1:?usage: tools/release.sh patch|minor|major|<1.2.3> "<notes>"}"
notes="${2:?give release notes as the second argument}"
current=$(sed -n 's/^name=//p' app/version.properties)
IFS=. read -r major minor patch <<< "$current"
case "$step" in
    patch) name="$major.$minor.$((patch + 1))" ;;
    minor) name="$major.$((minor + 1)).0" ;;
    major) name="$((major + 1)).0.0" ;;
    *) name="$step" ;;
esac
repo="railspotting/Railspotting-Android"

[[ "$name" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "version must look like 1.2.3" >&2; exit 1; }
[ -f keystore.properties ] || { echo "keystore.properties is missing (README, Release signing)" >&2; exit 1; }
[ "$(git rev-parse --abbrev-ref HEAD)" = main ] || { echo "release from main" >&2; exit 1; }
[ -z "$(git status --porcelain)" ] || { echo "commit or stash your changes first" >&2; exit 1; }
git fetch -q origin main
[ "$(git rev-parse HEAD)" = "$(git rev-parse origin/main)" ] || { echo "main is not up to date with origin" >&2; exit 1; }
git rev-parse -q --verify "refs/tags/v$name" >/dev/null && { echo "v$name exists already" >&2; exit 1; }

code=$(( $(sed -n 's/^code=//p' app/version.properties) + 1 ))
sed -i '' -e "s/^code=.*/code=$code/" -e "s/^name=.*/name=$name/" app/version.properties

./gradlew -q testDebugUnitTest lintDebug assembleRelease
apk="app/build/outputs/apk/release/app-release.apk"
out="build/release"
mkdir -p "$out"
file="railspotting-$name.apk"
cp "$apk" "$out/$file"
sha=$(shasum -a 256 "$out/$file" | cut -d' ' -f1)
python3 - "$out/latest.json" "$code" "$name" "$repo" "$file" "$sha" "$notes" <<'PY'
import json, sys
path, code, name, repo, file, sha, notes = sys.argv[1:]
with open(path, "w") as f:
    json.dump({"version_code": int(code), "version_name": name,
               "apk": f"https://github.com/{repo}/releases/download/v{name}/{file}",
               "sha256": sha, "notes": notes}, f, indent=2)
    f.write("\n")
PY

git commit -q -m "Release $name" app/version.properties
git tag -a "v$name" -m "Release $name"
git push -q origin main "v$name"
gh release create "v$name" "$out/$file" "$out/latest.json" --repo "$repo" --title "RailSpotting $name" --notes "$notes"
echo "Released $name (version code $code). Phones pick it up within a day."
