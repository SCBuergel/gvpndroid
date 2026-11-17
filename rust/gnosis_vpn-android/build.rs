fn main() {
    // __android_log_write lives in Android's liblog.
    if std::env::var("CARGO_CFG_TARGET_OS").as_deref() == Ok("android") {
        println!("cargo:rustc-link-lib=log");
    }
}
