#!/bin/sh
# Downloads (and checks) what the build needs: ONNX Runtime's official Android release and soniqo's DeepFilterNet3
# graph; with --official also DeepFilterNet's own three graphs (the benchmark's reference).
set -e
cd "$(dirname "$0")"
get() { # url sha256 out
  if [ -f "$3" ] && echo "$2  $3" | sha256sum -c --status; then return; fi
  mkdir -p "$(dirname "$3")"
  curl -fsSL -o "$3.part" "$1"
  echo "$2  $3.part" | sha256sum -c --status || { echo "checksum mismatch: $1" >&2; rm -f "$3.part"; exit 1; }
  mv "$3.part" "$3"
}
ORT=1.32.0
get "https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/$ORT/onnxruntime-android-$ORT.aar" \
  8e8dbb412ed4939675a157702f0c1e3621b436a645af7f7244536ac20bfad628 ort-android/ort.aar
[ -f ort-android/aar/jni/arm64-v8a/libonnxruntime.so ] || (cd ort-android && unzip -q -o ort.aar 'headers/*' 'jni/*' -d aar)
# soniqo/DeepFilterNet3-ONNX (MIT): verified export of the official DeepFilterNet3 v0.5.6 checkpoint
get "https://huggingface.co/soniqo/DeepFilterNet3-ONNX/resolve/63d8ba442ba900143c468b798e94a04009b2f0c9/deepfilter.onnx" \
  e1157049059434ae0d5857e32c812abea227b975e946b2eb64d001abbce156d3 models/soniqo/deepfilter.onnx
if [ "$1" = "--official" ]; then
  get "https://github.com/Rikorose/DeepFilterNet/raw/v0.5.6/models/DeepFilterNet3_onnx.tar.gz" \
    c94d91f70911001c946e0fabb4aa9adc37045f45a03b56008cb0c8244cb63616 models/DeepFilterNet3_onnx.tar.gz
  mkdir -p models/official
  tar xzf models/DeepFilterNet3_onnx.tar.gz -C models/official --strip-components=2 tmp/export/enc.onnx tmp/export/erb_dec.onnx tmp/export/df_dec.onnx
fi
