//! JNI entry points for the Gnosis VPN client on Android, loaded by the app as `libgnosisvpn.so`
//! (`System.loadLibrary("gnosisvpn")`, Kotlin class `org.gnosisvpn.stub.GnosisNative`).
//!
//! Phase 1: proves the client library cross-compiles and links for Android, and starts the
//! client core the way the desktop worker does. Connecting with the VpnService tun fd follows.

use std::ffi::{CStr, CString};
use std::net::{IpAddr, ToSocketAddrs};
use std::path::PathBuf;
use std::ptr;
use std::sync::OnceLock;

use gnosis_vpn_ipc::dirs::UserDirs;
use gnosis_vpn_lib::core::Core;
use gnosis_vpn_lib::worker_params::{AllowFlags, ConfigFileMode, WorkerParams};
use jni_sys::{jclass, jstring, JNIEnv};
use tokio::runtime::Runtime;
use tokio::sync::mpsc;

/// One runtime for the process; JNI calls return at once and the core runs on it.
static RUNTIME: OnceLock<Runtime> = OnceLock::new();

/// `GnosisNative.version(): String`
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_gnosisvpn_stub_GnosisNative_version(env: *mut JNIEnv, _class: jclass) -> jstring {
    new_string(env, &format!("gnosis_vpn-lib {} ({})", env!("CARGO_PKG_VERSION"), gnosis_vpn_lib::IDENTIFIER))
}

/// `GnosisNative.checkConfig(toml: String): String?` — null if the client accepts the config,
/// otherwise the error text. Exercises the client library's config parser from Kotlin.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_gnosisvpn_stub_GnosisNative_checkConfig(
    env: *mut JNIEnv,
    _class: jclass,
    toml: jstring,
) -> jstring {
    let Some(text) = get_string(env, toml) else {
        return new_string(env, "config is null or not UTF-8");
    };
    match gnosis_vpn_lib::config::parse(&text) {
        Ok(_) => ptr::null_mut(),
        Err(e) => new_string(env, &e.to_string()),
    }
}

/// `GnosisNative.startCore(dataDir, cacheDir, configToml, blokliUrl, network): String?` — starts
/// the client core in the background (identity, Blokli, HOPR node); null if it was started,
/// otherwise the error text. Progress goes to logcat (tag `gnosisvpn`).
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_gnosisvpn_stub_GnosisNative_startCore(
    env: *mut JNIEnv,
    _class: jclass,
    data_dir: jstring,
    cache_dir: jstring,
    config_toml: jstring,
    blokli_url: jstring,
    network: jstring,
) -> jstring {
    let args = (
        get_string(env, data_dir),
        get_string(env, cache_dir),
        get_string(env, config_toml),
        get_string(env, blokli_url),
        get_string(env, network),
    );
    let (Some(data), Some(cache), Some(toml), Some(blokli), Some(network)) = args else {
        return new_string(env, "startCore: a parameter is null or not UTF-8");
    };
    match start_core(PathBuf::from(data), PathBuf::from(cache), &toml, &blokli, network) {
        Ok(()) => ptr::null_mut(),
        Err(e) => new_string(env, &e),
    }
}

fn start_core(data: PathBuf, cache: PathBuf, toml: &str, blokli: &str, network: String) -> Result<(), String> {
    logcat::init();
    // Android's default temp dir (/data/local/tmp) is not writable by apps.
    // SAFETY: called before the runtime starts its worker threads.
    unsafe { std::env::set_var("TMPDIR", &cache) };
    let config = gnosis_vpn_lib::config::parse(toml).map_err(|e| format!("config: {e}"))?;
    let blokli_url = url::Url::parse(blokli).map_err(|e| format!("blokli url: {e}"))?;
    let host = blokli_url.host_str().ok_or("blokli url has no host")?;
    let port = blokli_url.port_or_known_default().unwrap_or(443);
    let blokli_ip = (host, port)
        .to_socket_addrs()
        .map_err(|e| format!("resolve {host}: {e}"))?
        .find_map(|a| match a.ip() {
            IpAddr::V4(v4) => Some(v4),
            IpAddr::V6(_) => None,
        })
        .ok_or_else(|| format!("{host} has no IPv4 address"))?;
    let dirs = UserDirs { data: data.clone(), cache, logs: data.join("logs") };
    let params = WorkerParams::new(ConfigFileMode::Generated, AllowFlags::default(), blokli_url, blokli_ip, dirs, network);
    params.ensure_dirs().map_err(|e| format!("state dirs: {e}"))?;

    let rt = RUNTIME.get_or_init(|| {
        tokio::runtime::Builder::new_multi_thread().enable_all().thread_name("gnosisvpn").build().expect("tokio runtime")
    });
    rt.spawn(async move {
        let (sender, mut from_core) = mpsc::channel(32);
        match Core::init(config, params, None, sender).await {
            Ok((core, _to_core)) => {
                tracing::info!("core initialised, starting");
                tokio::spawn(async move {
                    while let Some(msg) = from_core.recv().await {
                        // On desktop these go to root (tun setup, kill switch); the app handles them next.
                        tracing::info!(?msg, "core request");
                    }
                });
                core.start().await;
                tracing::info!("core stopped");
            }
            Err(e) => tracing::error!(error = %e, "core init failed"),
        }
    });
    Ok(())
}

/// Routes `tracing` output to Android's log (`adb logcat -s gnosisvpn`).
mod logcat {
    use std::ffi::CString;
    use std::io::{self, Write};
    use std::sync::Once;

    unsafe extern "C" {
        fn __android_log_write(prio: i32, tag: *const std::ffi::c_char, text: *const std::ffi::c_char) -> i32;
    }

    struct Logcat(Vec<u8>);

    impl Write for Logcat {
        fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
            self.0.extend_from_slice(buf);
            Ok(buf.len())
        }
        fn flush(&mut self) -> io::Result<()> {
            let text = String::from_utf8_lossy(&self.0).trim_end().replace('\0', " ");
            self.0.clear();
            if let Ok(c) = CString::new(text) {
                // SAFETY: both pointers are valid NUL-terminated strings for the duration of the call.
                unsafe { __android_log_write(4 /* INFO */, c"gnosisvpn".as_ptr(), c.as_ptr()) };
            }
            Ok(())
        }
    }

    impl Drop for Logcat {
        fn drop(&mut self) {
            let _ = self.flush();
        }
    }

    pub fn init() {
        static ONCE: Once = Once::new();
        ONCE.call_once(|| {
            let _ = tracing_subscriber::fmt()
                .with_ansi(false)
                .without_time()
                .with_writer(|| Logcat(Vec::new()))
                .try_init();
        });
    }
}

fn new_string(env: *mut JNIEnv, s: &str) -> jstring {
    let c = CString::new(s.replace('\0', " ")).expect("no interior NUL");
    // SAFETY: `env` is the valid JNIEnv the JVM passed to this native call.
    unsafe { ((**env).v1_1.NewStringUTF)(env, c.as_ptr()) }
}

fn get_string(env: *mut JNIEnv, s: jstring) -> Option<String> {
    if s.is_null() {
        return None;
    }
    // SAFETY: `env` and `s` are valid for the duration of this native call; the chars are released.
    unsafe {
        let chars = ((**env).v1_1.GetStringUTFChars)(env, s, ptr::null_mut());
        if chars.is_null() {
            return None;
        }
        let out = CStr::from_ptr(chars).to_str().ok().map(str::to_owned);
        ((**env).v1_1.ReleaseStringUTFChars)(env, s, chars);
        out
    }
}
