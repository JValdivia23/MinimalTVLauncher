#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk}"
ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
BUILD_TOOLS="$ANDROID_HOME/build-tools/34.0.0"
ANDROID_JAR="$ANDROID_HOME/platforms/android-34/android.jar"

BUILD_DIR="$PROJECT_DIR/build"
GEN_DIR="$BUILD_DIR/gen"
OBJ_DIR="$BUILD_DIR/obj"
DEX_DIR="$BUILD_DIR/dex"
KEYSTORE="$PROJECT_DIR/ktc-launcher.keystore"

export PATH="$JAVA_HOME/bin:$BUILD_TOOLS:$PATH"

rm -rf "$BUILD_DIR"
mkdir -p "$GEN_DIR" "$OBJ_DIR" "$DEX_DIR"

echo "==> [1/6] Compiling resources with aapt2..."
aapt2 compile --dir "$PROJECT_DIR/res" -o "$BUILD_DIR/res.zip"

echo "==> [2/6] Linking resources & generating R.java..."
aapt2 link \
    -o "$BUILD_DIR/unaligned.apk" \
    -I "$ANDROID_JAR" \
    --manifest "$PROJECT_DIR/AndroidManifest.xml" \
    --java "$GEN_DIR" \
    --min-sdk-version 28 \
    --target-sdk-version 30 \
    "$BUILD_DIR/res.zip"

echo "==> [3/6] Compiling Java sources..."
javac -source 11 -target 11 \
    -classpath "$ANDROID_JAR" \
    -d "$OBJ_DIR" \
    "$GEN_DIR"/com/ktc/launcher/R.java \
    "$PROJECT_DIR"/src/com/ktc/launcher/*.java

echo "==> [4/6] Converting bytecode to DEX with d8..."
d8 --min-api 28 \
    --lib "$ANDROID_JAR" \
    --output "$DEX_DIR" \
    $(find "$OBJ_DIR" -name "*.class")

echo "==> [5/6] Packaging & zipaligning APK..."
cp "$BUILD_DIR/unaligned.apk" "$BUILD_DIR/packaged.apk"
(cd "$DEX_DIR" && zip -q -u "$BUILD_DIR/packaged.apk" classes.dex)
zipalign -f 4 "$BUILD_DIR/packaged.apk" "$BUILD_DIR/ktc-launcher-aligned.apk"

echo "==> [6/6] Signing APK..."
if [[ ! -f "$KEYSTORE" ]]; then
    keytool -genkeypair -v \
        -keystore "$KEYSTORE" \
        -alias ktclauncher \
        -keyalg RSA \
        -keysize 2048 \
        -validity 10000 \
        -storepass ktclauncher \
        -keypass ktclauncher \
        -dname "CN=KTC Launcher, O=Local, C=US" >/dev/null 2>&1
fi

apksigner sign \
    --ks "$KEYSTORE" \
    --ks-key-alias ktclauncher \
    --ks-pass pass:ktclauncher \
    --key-pass pass:ktclauncher \
    --out "$BUILD_DIR/ktc-launcher.apk" \
    "$BUILD_DIR/ktc-launcher-aligned.apk"

ls -lh "$BUILD_DIR/ktc-launcher.apk"

if [[ "${1:-}" == "--install" ]]; then
    TARGET="${2:-10.0.0.144:5555}"
    echo "==> Installing to $TARGET via ADB..."
    adb connect "$TARGET"
    adb -s "$TARGET" install -r "$BUILD_DIR/ktc-launcher.apk"
fi
