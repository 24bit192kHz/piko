#!/bin/sh
# Builds libpikodf.so for arm64 Android and puts it, with ONNX Runtime's libonnxruntime.so, where the
# "Voice notes without pauses" patch picks them up. Needs Rust (rustup target add aarch64-linux-android) and an NDK.
set -e
cd "$(dirname "$0")"
./fetch.sh
NDK=${ANDROID_NDK_HOME:-${ANDROID_HOME:-$HOME/Android/Sdk}/ndk/$(ls "${ANDROID_HOME:-$HOME/Android/Sdk}/ndk" 2>/dev/null | sort -V | tail -1)}
TC=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin
[ -x "$TC/aarch64-linux-android26-clang" ] || { echo "no NDK found: set ANDROID_NDK_HOME" >&2; exit 1; }
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER=$TC/aarch64-linux-android26-clang
export CC_aarch64_linux_android=$TC/aarch64-linux-android26-clang AR_aarch64_linux_android=$TC/llvm-ar
cargo build --release --target aarch64-linux-android --lib --no-default-features --features ort
DEST=../../patches/src/main/resources/twitter/voice/arm64-v8a
mkdir -p $DEST
cp target/aarch64-linux-android/release/libpikodf.so ort-android/aar/jni/arm64-v8a/libonnxruntime.so $DEST/
sha256sum $DEST/*.so
