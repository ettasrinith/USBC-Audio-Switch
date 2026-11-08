#!/usr/bin/env bash
# Builds the APK with plain SDK tools, no gradle needed.
# Requires: build-tools (aapt2), platform android.jar, d8 (from r8 jar or build-tools), a JDK 17+.
set -e

SDK="${SDK:-$HOME/Android/Sdk}"          # android.jar lives at $SDK/platforms/android-34/
BT="${BT:-$SDK/build-tools/34.0.0}"      # aapt2 lives here
OUT=build

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes"

"$BT/aapt2" compile --dir app/res -o "$OUT/res.zip"
"$BT/aapt2" link -o "$OUT/base.apk" -I "$SDK/platforms/android-34/android.jar" \
    --manifest app/AndroidManifest.xml "$OUT/res.zip" \
    --min-sdk-version 26 --target-sdk-version 34 --java "$OUT/gen"

javac --release 8 -encoding UTF-8 -nowarn -cp "$SDK/platforms/android-34/android.jar" \
    -d "$OUT/classes" "$OUT"/gen/com/lasm/usb/R.java app/src/com/lasm/usb/*.java

# d8 from build-tools or a standalone r8 jar:
#   java -cp r8.jar com.android.tools.r8.D8 ...
"$BT/d8" --release --min-api 26 --lib "$SDK/platforms/android-34/android.jar" \
    --output "$OUT" $(find "$OUT/classes" -name '*.class')

cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT" && jar -uf unsigned.apk classes.dex)

"$BT/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
"$BT/apksigner" sign --ks "$KS" --ks-key-alias "$KS_ALIAS" --out USB-Audio-Router.apk "$OUT/aligned.apk"
echo "done: USB-Audio-Router.apk"
