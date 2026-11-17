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
use std::sync::atomic::{AtomicBool, Ordering};
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
/// Set while a core is starting or running; guards against a second core in the process.
static STARTED: AtomicBool = AtomicBool::new(false);
/// Set by `stopCore`; a core whose init is still running is dropped instead of started.
static STOP_REQUESTED: AtomicBool = AtomicBool::new(false);
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
#[cfg(target_os = "android")]
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

/// `GnosisNative.command(json: String): String` — sends a `WorkerCommand` as JSON (serde tag
/// "type", snake_case: `{"type":"status"}`, `{"type":"connect","destination":"<id>"}`) and returns
/// the core's `Response` as JSON, or `{"error":"..."}`. Blocks up to 60 s: never call it on the
/// main thread. tests/protocol.json pins the format on both sides.
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
    // Never echo the command: some carry secrets (FundingTool), and the reply ends up in logs.
    let cmd: WorkerCommand = serde_json::from_str(text)
        .map_err(|e| format!("bad command ({:?} error at line {} column {})", e.classify(), e.line(), e.column()))?;
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
    // Flag first: if init is still running, the init task sees it once init returns. If init
    // already handed over its sender, Shutdown reaches the running core.
    STOP_REQUESTED.store(true, Ordering::SeqCst);
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
    if STARTED.swap(true, Ordering::SeqCst) {
        return Err("core already running".into());
    }
    STOP_REQUESTED.store(false, Ordering::SeqCst);
    let result = prepare_and_spawn(data, cache, toml, blokli, network);
    if result.is_err() {
        STARTED.store(false, Ordering::SeqCst);
    }
    result
}

/// Sets a process environment variable the core reads, but only before the tokio runtime (and
/// with it any core thread) exists. Later starts may only repeat the same value.
fn set_env_before_runtime(name: &str, value: &std::ffi::OsStr) -> Result<(), String> {
    if std::env::var_os(name).as_deref() == Some(value) {
        return Ok(());
    }
    if RUNTIME.get().is_some() {
        return Err(format!("{name} cannot change while the app runs; restart the app"));
    }
    // SAFETY: no runtime thread exists yet and Kotlin serialises startCore calls, so no Rust
    // thread reads the environment concurrently.
    unsafe { std::env::set_var(name, value) };
    Ok(())
}

fn prepare_and_spawn(data: PathBuf, cache: PathBuf, toml: &str, blokli: &str, network: String) -> Result<(), String> {
    // Android's default temp dir (/data/local/tmp) is not writable by apps.
    set_env_before_runtime("TMPDIR", cache.as_os_str())?;
    let config = gnosis_vpn_lib::config::parse(toml).map_err(|e| format!("config: {e}"))?;
    // The PIX prover (curvy-witnesscalc) finds its proving files only through this variable; on
    // desktop root sets it when spawning the worker. Without it PIX payments fail and the exit
    // stalls inbound traffic until it is paid.
    set_env_before_runtime(gnosis_vpn_lib::hopr::CURVY_ZK_DIR_ENV, config.curvy_zk_dir.as_os_str())?;
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
                // Stopped while init ran (stopCore found no sender yet): never start this core.
                if STOP_REQUESTED.load(Ordering::SeqCst) {
                    TO_CORE.lock().unwrap().take();
                    drop(core);
                    STARTED.store(false, Ordering::SeqCst);
                    tracing::info!("core stopped before it started");
                    push_event(json!({ "type": "core_stopped" }));
                    return;
                }
                tokio::spawn(async move {
                    while let Some(CoreToWorker::RequestToRoot(request)) = from_core.recv().await {
                        handle_request(request);
                    }
                });
                push_event(json!({ "type": "core_started" }));
                core.start().await;
                TO_CORE.lock().unwrap().take();
                STARTED.store(false, Ordering::SeqCst);
                push_event(json!({ "type": "core_stopped" }));
            }
            Err(e) => {
                tracing::error!(error = %e, "core init failed");
                STARTED.store(false, Ordering::SeqCst);
                push_event(json!({ "type": "core_error", "error": e.to_string() }));
            }
        }
    });
    Ok(())
}

/// Routes `tracing` output to Android's log (`adb logcat -s gnosisvpn`) and to a ring buffer the
/// app drains with `pollLogs` (the phone has no logcat). The filter can be changed at runtime.
mod logcat {
    use std::collections::VecDeque;
    #[cfg(target_os = "android")]
    use std::ffi::CString;
    use std::io::{self, Write};
    use std::sync::{Mutex, Once, OnceLock};

    use tracing_subscriber::layer::SubscriberExt;
    use tracing_subscriber::util::SubscriberInitExt;
    use tracing_subscriber::{reload, EnvFilter, Registry};

    /// Path-selection and probe chatter floods the log (millions of lines) and evicts everything else.
    pub const DEFAULT_FILTER: &str = "info,hopr_transport::path=off,hopr_transport_probe=error,hopr_transport_p2p::swarm=warn";
    const APP_LOG_MAX: usize = 3000;

    #[cfg(target_os = "android")]
    unsafe extern "C" {
        fn __android_log_write(prio: i32, tag: *const std::ffi::c_char, text: *const std::ffi::c_char) -> i32;
    }

    /// Host builds (the crate's tests) have no liblog.
    #[cfg(target_os = "android")]
    fn write_logcat(text: &str) {
        if let Ok(c) = CString::new(text) {
            // SAFETY: both pointers are valid NUL-terminated strings for the call.
            unsafe { __android_log_write(4 /* INFO */, c"gnosisvpn".as_ptr(), c.as_ptr()) };
        }
    }
    #[cfg(not(target_os = "android"))]
    fn write_logcat(text: &str) {
        eprintln!("{text}");
    }

    static RELOAD: OnceLock<reload::Handle<EnvFilter, Registry>> = OnceLock::new();
    static APP_LOG: Mutex<VecDeque<String>> = Mutex::new(VecDeque::new());

    struct Logcat(Vec<u8>);

    impl Write for Logcat {
        fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
            self.0.extend_from_slice(buf);
            Ok(buf.len())
        }
        fn flush(&mut self) -> io::Result<()> {
            let text = String::from_utf8_lossy(&self.0).trim_end().replace('\0', " ");
            self.0.clear();
            if text.is_empty() {
                return Ok(());
            }
            write_logcat(&text);
            let mut log = APP_LOG.lock().unwrap();
            log.push_back(text);
            while log.len() > APP_LOG_MAX {
                log.pop_front();
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
            let wanted = std::env::var("RUST_LOG").unwrap_or_else(|_| DEFAULT_FILTER.into());
            let filter = EnvFilter::try_new(wanted).unwrap_or_else(|_| EnvFilter::new(DEFAULT_FILTER));
            let (filter, handle) = reload::Layer::new(filter);
            let fmt = tracing_subscriber::fmt::layer()
                .with_ansi(false)
                .without_time()
                .with_writer(|| Logcat(Vec::new()));
            if Registry::default().with(filter).with(fmt).try_init().is_ok() {
                let _ = RELOAD.set(handle);
            }
        });
    }

    /// Replaces the filter (EnvFilter syntax, e.g. "warn,gnosis_vpn_lib=info"). Err if invalid.
    pub fn set_filter(spec: &str) -> Result<(), String> {
        init();
        let filter = EnvFilter::try_new(spec).map_err(|e| e.to_string())?;
        RELOAD.get().ok_or("logging not initialised")?.reload(filter).map_err(|e| e.to_string())
    }

    /// Takes up to `max` buffered lines.
    pub fn drain(max: usize) -> Vec<String> {
        let mut log = APP_LOG.lock().unwrap();
        let n = log.len().min(max);
        log.drain(..n).collect()
    }
}

/// `GnosisNative.setLogFilter(filter: String): String?` — changes the core's log filter at runtime
/// (EnvFilter syntax); null on success, else the error.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_gnosisvpn_stub_GnosisNative_setLogFilter(
    env: *mut JNIEnv,
    _class: jclass,
    filter: jstring,
) -> jstring {
    let Some(spec) = get_string(env, filter) else {
        return new_string(env, "filter is null or not UTF-8");
    };
    match logcat::set_filter(&spec) {
        Ok(()) => ptr::null_mut(),
        Err(e) => new_string(env, &e),
    }
}

/// `GnosisNative.pollLogs(): String?` — buffered core log lines (newline-separated), or null.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_gnosisvpn_stub_GnosisNative_pollLogs(env: *mut JNIEnv, _class: jclass) -> jstring {
    let lines = logcat::drain(500);
    if lines.is_empty() { ptr::null_mut() } else { new_string(env, &lines.join("\n")) }
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

#[cfg(test)]
mod tests {
    use gnosis_vpn_ipc::Response;
    use gnosis_vpn_lib::command::WorkerCommand;
    use serde_json::Value;

    fn fixture() -> Value {
        serde_json::from_str(include_str!("../tests/protocol.json")).expect("tests/protocol.json is JSON")
    }

    /// The command strings the app sends are WorkerCommands the core accepts.
    #[test]
    fn app_commands_parse_as_worker_commands() {
        let f = fixture();
        for (name, text) in f["commands"].as_object().unwrap() {
            let parsed: Result<WorkerCommand, _> = serde_json::from_str(text.as_str().unwrap());
            assert!(parsed.is_ok(), "command {name} rejected: {parsed:?}");
        }
        let connect: WorkerCommand = serde_json::from_str(f["commands"]["connect"].as_str().unwrap()).unwrap();
        assert!(matches!(connect, WorkerCommand::Connect { .. }));
    }

    /// The status samples the app's parser is tested with are real Responses of this client.
    #[test]
    fn status_samples_are_responses() {
        let f = fixture();
        for key in ["status_running", "status_preparing_safe"] {
            let parsed: Result<Response, _> = serde_json::from_value(f[key].clone());
            assert!(parsed.is_ok(), "{key} is not a Response: {parsed:?}");
            // Round trip: what the bridge sends Kotlin keeps the fields the app reads.
            let back = serde_json::to_value(parsed.unwrap()).unwrap();
            assert_eq!(back["type"], "status");
            assert!(back["data"]["run_mode"]["type"].is_string());
        }
    }

    /// A rejected command must not echo its text (funding commands carry a secret).
    #[test]
    fn bad_command_error_does_not_echo_the_command() {
        let secret = r#"{"type":"funding_tool","secret":"TOP-SECRET-CODE","oops":"#;
        let err = super::command(secret).unwrap_err();
        assert!(!err.contains("TOP-SECRET-CODE"), "{err}");
    }
}
