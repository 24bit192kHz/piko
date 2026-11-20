# pikodf: DeepFilterNet3 for X Chat voice notes

Background-noise removal for the "Voice notes without pauses" patch: the notes you send, and the pause-free copy of
the notes you hear. `libpikodf.so` (arm64 Android) runs [DeepFilterNet3](https://github.com/Rikorose/DeepFilterNet)
v0.5.6 the way Python's `df.enhance` does:

- STFT, ERB / complex features, deep filtering and synthesis: libDF, DeepFilterNet's own Rust crate.
- The network: [soniqo/DeepFilterNet3-ONNX](https://huggingface.co/soniqo/DeepFilterNet3-ONNX) (MIT, a verified
  single-graph export of the official checkpoint) in ONNX Runtime's official Android build, through a small C shim
  (`shim/ort_shim.c`, no C++ runtime needed). The model is compiled into the library.
- Notes up to 20 s in one pass; longer ones in 15 s chunks with 2 s of context on both sides, two at a time.
- Resampling to 48 kHz and back: torchaudio's sinc resampler, bit-identical to the patch's Java `VoiceCut.resample`.

The app calls it from `DfNative.java`; any error, panic or time-budget overrun returns null and the note keeps its
noise. On x86 devices (where this arm64 X runs through binary translation) it stays off.

## Build

```sh
rustup target add aarch64-linux-android
ANDROID_NDK_HOME=/path/to/ndk ./build-android.sh
```

`fetch.sh` downloads ONNX Runtime 1.32.0 (Maven Central) and the model, checking SHA-256; the script then copies
`libpikodf.so` and `libonnxruntime.so` to `patches/src/main/resources/twitter/voice/arm64-v8a/` (not in git), where
the patch picks them up. X loads its libraries straight from the APK, so they must stay stored uncompressed in the
patched APK.

## Benchmark

```sh
./fetch.sh --official          # + DeepFilterNet's own three graphs, the reference
cargo run --release --features official --bin pikodf-bench -- note.f32   # mono float32 LE at 48 kHz
```

Measured on an arm64 phone: ONNX Runtime 0.015 s per second of audio vs tract 0.035; output
equal to DeepFilterNet's own graphs (SI-SDR 129 dB, float noise); the app's chunking is 36-47 dB from a whole-note
run on real speech (inaudible).
