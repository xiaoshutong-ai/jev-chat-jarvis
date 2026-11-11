#!/usr/bin/env bash
set -euo pipefail
# Source-only CI: reuse verified local assets, without networking/downloads.
expected="$EXPECTED_HEAD_SHA"
actual="$(git rev-parse HEAD)"
if [[ "$actual" != "$expected" ]]; then
  echo "JEV_CI_HEAD_MISMATCH expected=$expected actual=$actual" >&2
  exit 1
fi
image="sha256:c8fdb90ce00a66b4c0ba129d8dea684ba834fb93d5e53ae5557bd759e35a2bf1"
gradle="$HOME/.gradle/wrapper/dists/gradle-8.13-bin/5xuhj0ry160q40clulazy9h7d/gradle-8.13/bin/gradle"
android="$HOME/.android/sdk"
dep_cache="/mnt/c/Users/Administrator/.gradle/caches/modules-2"
test -x "$gradle" || { echo "JEV_CI_MISSING_CACHED_GRADLE_8_13" >&2; exit 1; }
test -f "$android/platforms/android-36/android.jar" || { echo "JEV_CI_MISSING_NATIVE_ANDROID36" >&2; exit 1; }
test -x "$android/build-tools/35.0.0/aapt2" || { echo "JEV_CI_MISSING_NATIVE_AAPT2_35" >&2; exit 1; }
test -d "$dep_cache" || { echo "JEV_CI_MISSING_READONLY_MAVEN_CACHE" >&2; exit 1; }
test -f gradle/wrapper/gradle-wrapper.properties || { echo "JEV_CI_WRAPPER_METADATA_MISSING" >&2; exit 1; }
docker image inspect "$image" >/dev/null || { echo "JEV_CI_CACHED_IMAGE_NOT_FOUND" >&2; exit 1; }
echo "JEV_CI_SOURCE_SHA=$actual"
echo "JEV_CI_TOOLCHAIN=Linux_JDK17_Android36_BuildTools35_Gradle8.13_CACHE"
echo "JEV_CI_WRAPPER_8_11_1=NOT_RUN"
echo "JEV_CI_NETWORK=OFFLINE_CONTAINER"
uid="$(id -u)"
gid="$(id -g)"
git archive --format=tar "$actual" | docker run --rm -i --pull never --network none \
  --user "$uid:$gid" \
  --tmpfs "/work:rw,exec,size=2048m,uid=$uid,gid=$gid" \
  --tmpfs "/home/githubrunner/.android:rw,size=64m,uid=$uid,gid=$gid" \
  -e HOME=/home/githubrunner \
  -e JAVA_TOOL_OPTIONS=-Duser.home=/home/githubrunner \
  -e ANDROID_USER_HOME=/home/githubrunner/.android \
  -e GRADLE_USER_HOME=/home/githubrunner/.gradle \
  -e GRADLE_RO_DEP_CACHE=/read-only-cache \
  -e ANDROID_HOME=/opt/android-sdk \
  -e ANDROID_SDK_ROOT=/opt/android-sdk \
  -v "$HOME/.gradle:/home/githubrunner/.gradle" \
  -v "$dep_cache:/read-only-cache/modules-2:ro" \
  -v "$android:/opt/android-sdk:ro" \
  -w /work --entrypoint /bin/sh "$image" -c '
    set -eu
    tar xf -
    gradle=/home/githubrunner/.gradle/wrapper/dists/gradle-8.13-bin/5xuhj0ry160q40clulazy9h7d/gradle-8.13/bin/gradle
    "$gradle" :app:testDebugUnitTest :app:assembleDebug \
      -Pandroid.aapt2FromMavenOverride=/opt/android-sdk/build-tools/35.0.0/aapt2 \
      --offline --no-daemon --max-workers=2 --console=plain
    apk=app/build/outputs/apk/debug/app-debug.apk
    test -s "$apk"
    sha256sum "$apk"
    badging="$(/opt/android-sdk/build-tools/35.0.0/aapt dump badging "$apk")"
    printf "%s\n" "$badging" | grep -F "package: name=" | grep -F "com.jev.probe.guofeng"
    printf "%s\n" "$badging" | grep -F "application-label:" | grep -F "小书童·知言"
    printf "%s\n" "$badging" | grep -F "targetSdkVersion:" | grep -F "36"
    printf "%s\n" "$badging" | grep -F "compileSdkVersion=" | grep -F "36"
    echo "JEV_SELFHOSTED_OFFLINE_SOURCE_GATE=PASS"
  '
