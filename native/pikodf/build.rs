// With the `ort` feature: compile the C shim against the official ONNX Runtime Android release (ort-android/aar,
// from Maven Central com.microsoft.onnxruntime:onnxruntime-android) and link its libonnxruntime.so.
fn main() {
    println!("cargo:rerun-if-changed=shim/ort_shim.c");
    if std::env::var_os("CARGO_FEATURE_ORT").is_none() { return; }
    let root = std::path::PathBuf::from(std::env::var("CARGO_MANIFEST_DIR").unwrap());
    let aar = root.join("ort-android/aar");
    cc::Build::new().file("shim/ort_shim.c").include(aar.join("headers")).opt_level(2).compile("pikodf_ort_shim");
    let arch = match std::env::var("CARGO_CFG_TARGET_ARCH").unwrap().as_str() { "aarch64" => "arm64-v8a", "x86_64" => "x86_64", a => panic!("no ONNX Runtime for {a}") };
    println!("cargo:rustc-link-search=native={}", aar.join("jni").join(arch).display());
    println!("cargo:rustc-link-lib=dylib=onnxruntime");
}
