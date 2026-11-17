//! JNI bridge between the Android app and the Gnosis VPN client core, loaded as `libgnosisvpn.so`
//! (`System.loadLibrary("gnosisvpn")`, Kotlin object `org.gnosisvpn.stub.GnosisNative`).
//!
//! On desktop the core runs in an unprivileged worker that asks a root process for the TUN
//! device, routes, kill switch and an in-tunnel ping. Here this bridge plays root:
//! - the kill switch is a no-op (Android's "Always-on VPN + block connections without VPN");
//! - `SetupTunnel` becomes an event for Kotlin, which builds the tun with `VpnService.Builder`
//!   and hands the fd back through [`tunReady`], over the same fd-passing socket the desktop
//!   worker uses;
//! - the in-tunnel ping is answered as skipped: the app is excluded from its own VPN (so the
//!   HOPR traffic does not loop), and a ping from it would bypass the tunnel.
//!
//! All calls return at once except `command`, which waits for the core's answer (call it off
//! the main thread). Logs go to logcat, tag `gnosisvpn`.

use std::collections::VecDeque;
use std::ffi::{CStr, CString};
use std::net::{IpAddr, ToSocketAddrs};
use std::os::fd::BorrowedFd;
use std::os::unix::net::UnixStream;
use std::path::PathBuf;
use std::ptr;
use std::sync::{Mutex, OnceLock};
use std::time::Duration;

use gnosis_vpn_ipc::dirs::UserDirs;
use gnosis_vpn_lib::command::WorkerCommand;
use gnosis_vpn_lib::core::Core;
use gnosis_vpn_lib::event::{CoreToWorker, RequestToRoot, ResponseFromRoot, WorkerToCore};
use gnosis_vpn_lib::socket;
use gnosis_vpn_lib::worker_params::{AllowFlags, ConfigFileMode, WorkerParams};
use jni_sys::{jclass, jint, jlong, jstring, JNIEnv};
use serde_json::json;
use tokio::runtime::Runtime;
use tokio::sync::{mpsc, oneshot};

const COMMAND_TIMEOUT: Duration = Duration::from_secs(60);

/// One runtime for the process; the core runs on it.
static RUNTIME: OnceLock<Runtime> = OnceLock::new();
/// Sender into the running core; None until `Core::init` succeeded.
static TO_CORE: Mutex<Option<mpsc::Sender<WorkerToCore>>> = Mutex::new(None);
/// Our end of the TUN fd-passing socket; the core's end is registered with `set_tun_fd_socket`.
static TUN_SOCKET: OnceLock<UnixStream> = OnceLock::new();
/// Events for Kotlin, as JSON lines, drained by `pollEvent`.
static EVENTS: Mutex<VecDeque<String>> = Mutex::new(VecDeque::new());

fn runtime() -> &'static Runtime {
    RUNTIME.get_or_init(|| {
        tokio::runtime::Builder::new_multi_thread().enable_all().thread_name("gnosisvpn").build().expect("tokio runtime")
    })
}

fn push_event(event: serde_json::Value) {
    tracing::info!(%event, "event for app");
    EVENTS.lock().unwrap().push_back(event.to_string());
}

fn to_core() -> Option<mpsc::Sender<WorkerToCore>> {
    TO_CORE.lock().unwrap().clone()
}

/// `GnosisNative.initAndroid(context: Context)` — once per process, before `startCore`: gives the
/// TLS certificate verifier (rustls-platform-verifier, used by reqwest for Blokli) the JVM and a
/// Context. Without it the first HTTPS request aborts the process.
#[jni::jni_mangle("org.gnosisvpn.stub.GnosisNative")]
pub fn initAndroid<'caller>(
    mut unowned_env: jni::EnvUnowned<'caller>,
    _class: jni::objects::JClass<'caller>,
    context: jni::objects::JObject<'caller>,
) {
    logcat::init();
    unowned_env
        .with_env(|env| rustls_platform_verifier::android::init_with_env(env, context))
        .resolve::<jni::errors::ThrowRuntimeExAndDefault>();
    tracing::info!("android TLS verifier initialised");
}

/// `GnosisNative.version(): String`
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_gnosisvpn_stub_GnosisNative_version(env: *mut JNIEnv, _class: jclass) -> jstring {
    new_string(env, &format!("gnosis_vpn-lib {} ({})", env!("CARGO_PKG_VERSION"), gnosis_vpn_lib::IDENTIFIER))
}

/// `GnosisNative.checkConfig(toml: String): String?` — null if the client accepts the config,
/// otherwise the error text.
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
/// otherwise the error text. Init failures later arrive as a `core_error` event.
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

/// `GnosisNative.command(json: String): String` — sends a `WorkerCommand` (serde JSON, e.g.
/// `"Status"` or `{"Connect":{"destination":"..."}}`) and returns the core's `Response` as JSON,
/// or `{"error":"..."}`. Blocks up to 60 s: never call it on the main thread.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_gnosisvpn_stub_GnosisNative_command(
    env: *mut JNIEnv,
    _class: jclass,
    json_cmd: jstring,
) -> jstring {
    let reply = match get_string(env, json_cmd) {
        Some(text) => command(&text).unwrap_or_else(|e| json!({ "error": e }).to_string()),
        None => json!({ "error": "command is null or not UTF-8" }).to_string(),
    };
    new_string(env, &reply)
}

fn command(text: &str) -> Result<String, String> {
    let cmd: WorkerCommand = serde_json::from_str(text).map_err(|e| format!("bad command {text}: {e}"))?;
    let sender = to_core().ok_or("core is not running")?;
    runtime().block_on(async move {
        let (resp, rx) = oneshot::channel();
        sender.send(WorkerToCore::WorkerCommand { cmd, resp }).await.map_err(|_| "core stopped".to_string())?;
        let response = tokio::time::timeout(COMMAND_TIMEOUT, rx)
            .await
            .map_err(|_| "core did not answer within 60 s".to_string())?
            .map_err(|_| "core dropped the command".to_string())?;
        serde_json::to_string(&response).map_err(|e| format!("response not serializable: {e}"))
    })
}

/// `GnosisNative.pollEvent(): String?` — the next event for the app as JSON, or null.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_gnosisvpn_stub_GnosisNative_pollEvent(env: *mut JNIEnv, _class: jclass) -> jstring {
    match EVENTS.lock().unwrap().pop_front() {
        Some(event) => new_string(env, &event),
        None => ptr::null_mut(),
    }
}

/// `GnosisNative.tunReady(requestId: Long, fd: Int): String?` — answers a `setup_tunnel` event
/// with the established VpnService tun fd. The fd is duplicated into the core; the caller keeps
/// (and later closes) its own copy. fd < 0 reports failure. Null on success, else the error.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_gnosisvpn_stub_GnosisNative_tunReady(
    env: *mut JNIEnv,
    _class: jclass,
    request_id: jlong,
    fd: jint,
) -> jstring {
    let request_id = request_id as u64;
    let result = (|| {
        if fd < 0 {
            return Err("the app could not establish the VPN tun".to_string());
        }
        let sock = TUN_SOCKET.get().ok_or("core not started")?;
        // SAFETY: Kotlin keeps the ParcelFileDescriptor open for the duration of this call.
        let borrowed = unsafe { BorrowedFd::borrow_raw(fd) };
        // The fd must be in the socket before the core reads TunnelReady and calls recv_tun_fd.
        socket::fd_passing::send_fd(sock, &borrowed).map_err(|e| format!("send tun fd: {e}"))?;
        Ok("tun0".to_string())
    })();
    let error = result.as_ref().err().cloned();
    respond(ResponseFromRoot::TunnelReady { request_id, res: result });
    match error {
        None => ptr::null_mut(),
        Some(e) => new_string(env, &e),
    }
}

/// `GnosisNative.stopCore()` — asks the core to shut down.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_gnosisvpn_stub_GnosisNative_stopCore(_env: *mut JNIEnv, _class: jclass) {
    if let Some(sender) = TO_CORE.lock().unwrap().take() {
        let _ = sender.try_send(WorkerToCore::Shutdown);
    }
}

fn respond(response: ResponseFromRoot) {
    match to_core() {
        Some(sender) => {
            runtime().spawn(async move {
                let _ = sender.send(WorkerToCore::ResponseFromRoot(response)).await;
            });
        }
        None => tracing::warn!(?response, "no core to deliver the response to"),
    }
}

/// Plays the desktop root process for the core's requests.
fn handle_request(request: RequestToRoot) {
    match request {
        RequestToRoot::KillswitchLockdown { request_id, .. } => {
            tracing::info!("kill switch: no-op on Android (use Always-on VPN)");
            respond(ResponseFromRoot::KillswitchLockdown { request_id, res: Ok(()) });
        }
        RequestToRoot::SetupTunnel { request_id, interface_address, mtu, dns, peer_ips } => push_event(json!({
            "type": "setup_tunnel",
            "request_id": request_id,
            "address": interface_address,
            "mtu": mtu,
            "dns": dns,
            "peer_ips": peer_ips.len(),
        })),
        RequestToRoot::TearDownWg => push_event(json!({ "type": "teardown" })),
        RequestToRoot::Ping { request_id, .. } => {
            static ONCE: std::sync::Once = std::sync::Once::new();
            ONCE.call_once(|| tracing::warn!("in-tunnel pings are skipped: the app is excluded from its own VPN"));
            tracing::debug!("in-tunnel ping skipped");
            respond(ResponseFromRoot::Ping { request_id, res: Ok(Duration::ZERO) });
        }
        RequestToRoot::UpdatePeerIps { peer_ips } => tracing::debug!(n = peer_ips.len(), "peer ips (not needed on Android)"),
        RequestToRoot::ProbeStopped => tracing::debug!("probe stopped"),
    }
}

fn start_core(data: PathBuf, cache: PathBuf, toml: &str, blokli: &str, network: String) -> Result<(), String> {
    logcat::init();
    if TO_CORE.lock().unwrap().is_some() {
        return Err("core already running".into());
    }
    // Android's default temp dir (/data/local/tmp) is not writable by apps.
    // SAFETY: set before the core starts using it; no other thread reads the environment concurrently.
    unsafe { std::env::set_var("TMPDIR", &cache) };
    let config = gnosis_vpn_lib::config::parse(toml).map_err(|e| format!("config: {e}"))?;
    // The PIX prover (curvy-witnesscalc) finds its proving files only through this variable; on
    // desktop root sets it when spawning the worker. Without it PIX payments fail and the exit
    // stalls inbound traffic until it is paid.
    // SAFETY: as above, before the core starts.
    unsafe { std::env::set_var(gnosis_vpn_lib::hopr::CURVY_ZK_DIR_ENV, &config.curvy_zk_dir) };
    let blokli_url = url::Url::parse(blokli).map_err(|e| format!("blokli url: {e}"))?;
    let host = blokli_url.host_str().ok_or("blokli url has no host")?.to_string();
    let port = blokli_url.port_or_known_default().unwrap_or(443);
    let blokli_ip = (host.as_str(), port)
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

    if TUN_SOCKET.get().is_none() {
        let (ours, cores) = UnixStream::pair().map_err(|e| format!("socketpair: {e}"))?;
        socket::worker::set_tun_fd_socket(cores);
        let _ = TUN_SOCKET.set(ours);
    }

    let (sender, mut from_core) = mpsc::channel(32);
    runtime().spawn(async move {
        tracing::info!(%blokli_ip, "core init");
        match Core::init(config, params, None, sender).await {
            Ok((core, to_core)) => {
                *TO_CORE.lock().unwrap() = Some(to_core);
                tokio::spawn(async move {
                    while let Some(CoreToWorker::RequestToRoot(request)) = from_core.recv().await {
                        handle_request(request);
                    }
                });
                push_event(json!({ "type": "core_started" }));
                core.start().await;
                TO_CORE.lock().unwrap().take();
                push_event(json!({ "type": "core_stopped" }));
            }
            Err(e) => {
                tracing::error!(error = %e, "core init failed");
                push_event(json!({ "type": "core_error", "error": e.to_string() }));
            }
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
            if !text.is_empty() {
                if let Ok(c) = CString::new(text) {
                    // SAFETY: both pointers are valid NUL-terminated strings for the call.
                    unsafe { __android_log_write(4 /* INFO */, c"gnosisvpn".as_ptr(), c.as_ptr()) };
                }
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
            let filter = tracing_subscriber::EnvFilter::try_new(
                // Path-selection and probe chatter floods logcat (millions of lines) and evicts
                // everything else; RUST_LOG still overrides this default.
                std::env::var("RUST_LOG").unwrap_or_else(|_| {
                    "info,hopr_transport::path=off,hopr_transport_probe=error,hopr_transport_p2p::swarm=warn".into()
                }),
            )
            .unwrap_or_else(|_| tracing_subscriber::EnvFilter::new("info"));
            let _ = tracing_subscriber::fmt()
                .with_env_filter(filter)
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
