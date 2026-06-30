use anyhow::Result;
#[cfg(any(test, feature = "test-helpers"))]
use std::sync::Mutex;

#[cfg(any(test, feature = "test-helpers"))]
use super::STORE_READ_COUNT;
use super::ENV_BACKEND;

#[cfg(target_os = "macos")]
use crate::secrets::store::{delete_from_default_store, write_to_default_store};

#[cfg(target_os = "macos")]
const PROTECTED_PROBE_KEY: &str = "__safedb_entitlement_probe__";

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum BackendMode {
    Disabled,
    #[cfg(target_os = "macos")]
    Protected,
    #[cfg(target_os = "windows")]
    Windows,
    #[cfg(target_os = "linux")]
    LinuxKeyutils,
    #[cfg(not(any(target_os = "macos", target_os = "windows", target_os = "linux")))]
    Mock,
}

static ACTIVE_BACKEND: std::sync::OnceLock<BackendMode> = std::sync::OnceLock::new();

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RequestedBackend {
    Auto,
    Disabled,
    #[cfg(target_os = "macos")]
    Protected,
}

pub fn parse_requested_backend_from(raw: Option<&str>) -> RequestedBackend {
    let Some(raw) = raw else {
        return RequestedBackend::Auto;
    };
    match raw.to_ascii_lowercase().as_str() {
        "disabled" => RequestedBackend::Disabled,
        #[cfg(target_os = "macos")]
        "protected" => RequestedBackend::Protected,
        #[cfg(not(target_os = "macos"))]
        "protected" => {
            log::warn!(
                "{ENV_BACKEND}=protected is only supported on macOS; using platform default backend"
            );
            RequestedBackend::Auto
        }
        "keychain" | "legacy" => {
            log::warn!(
                "legacy macOS Keychain is not supported; use {ENV_BACKEND}=protected, \
                 {ENV_BACKEND}=disabled, or {ENV_BACKEND}=auto"
            );
            RequestedBackend::Auto
        }
        "auto" | "" => RequestedBackend::Auto,
        other => {
            log::warn!(
                "unknown {ENV_BACKEND}={other}; supported values: auto, disabled \
                 (macOS: protected)"
            );
            RequestedBackend::Auto
        }
    }
}

fn parse_requested_backend() -> RequestedBackend {
    let raw = std::env::var(ENV_BACKEND).ok();
    parse_requested_backend_from(raw.as_deref())
}

fn active_backend() -> BackendMode {
    *ACTIVE_BACKEND.get().expect("secrets store not initialized")
}

fn set_active_backend(mode: BackendMode) {
    let _ = ACTIVE_BACKEND.set(mode);
}

pub fn active_backend_label() -> &'static str {
    match active_backend() {
        BackendMode::Disabled => "disabled",
        #[cfg(target_os = "macos")]
        BackendMode::Protected => "protected",
        #[cfg(target_os = "windows")]
        BackendMode::Windows => "windows",
        #[cfg(target_os = "linux")]
        BackendMode::LinuxKeyutils => "linux-keyutils",
        #[cfg(not(any(target_os = "macos", target_os = "windows", target_os = "linux")))]
        BackendMode::Mock => "mock",
    }
}

fn init_disabled() -> Result<()> {
    log::warn!(
        "{ENV_BACKEND}=disabled: credentials are held in process memory only and will not \
         survive an app restart. Do not ship this configuration."
    );
    keyring_core::set_default_store(super::store::DisabledMemoryStore::arc());
    set_active_backend(BackendMode::Disabled);
    Ok(())
}

#[cfg(target_os = "macos")]
fn setup_macos_protected_store() -> Result<()> {
    let store = apple_native_keyring_store::protected::Store::new().map_err(|e| {
        anyhow::anyhow!(
            "failed to init macOS Protected Data store ({e}). Build a signed app bundle with \
             entitlements (see src-tauri/Entitlements.plist), or set {ENV_BACKEND}=disabled \
             for local development."
        )
    })?;
    keyring_core::set_default_store(store);
    Ok(())
}

#[cfg(target_os = "macos")]
fn finish_macos_protected_init() {
    set_active_backend(BackendMode::Protected);
    log::info!("credential backend: macOS Protected Data");
}

#[cfg(target_os = "macos")]
fn probe_protected_store() -> Result<()> {
    write_to_default_store(PROTECTED_PROBE_KEY, "probe")?;
    delete_from_default_store(PROTECTED_PROBE_KEY)?;
    Ok(())
}

#[cfg(target_os = "macos")]
fn init_macos_protected() -> Result<()> {
    setup_macos_protected_store()?;
    probe_protected_store()?;
    finish_macos_protected_init();
    Ok(())
}

#[cfg(target_os = "macos")]
fn init_macos_auto() -> Result<()> {
    match setup_macos_protected_store() {
        Ok(()) => match probe_protected_store() {
            Ok(()) => {
                finish_macos_protected_init();
                Ok(())
            }
            Err(probe_err) => {
                if cfg!(debug_assertions) {
                    log::warn!(
                        "Protected Data write probe failed in debug build ({probe_err}); \
                         unsigned pnpm tauri dev builds lack keychain entitlements — using \
                         in-memory disabled backend for this session"
                    );
                    init_disabled()
                } else {
                    Err(probe_err)
                }
            }
        },
        Err(protected_err) => {
            if cfg!(debug_assertions) {
                log::warn!(
                    "Protected Data unavailable in debug build ({protected_err}); using \
                     in-memory disabled backend for this session"
                );
                init_disabled()
            } else {
                Err(protected_err)
            }
        }
    }
}

pub fn init_store() -> Result<()> {
    #[cfg(any(test, feature = "test-helpers"))]
    let _ = STORE_READ_COUNT.set(Mutex::new(0));

    match parse_requested_backend() {
        RequestedBackend::Disabled => init_disabled(),
        #[cfg(target_os = "macos")]
        RequestedBackend::Protected => init_macos_protected(),
        RequestedBackend::Auto => {
            #[cfg(target_os = "macos")]
            {
                init_macos_auto()
            }
            #[cfg(target_os = "windows")]
            {
                let store = windows_native_keyring_store::Store::new()
                    .map_err(|e| anyhow::anyhow!("failed to init Windows credential store: {e}"))?;
                keyring_core::set_default_store(store);
                set_active_backend(BackendMode::Windows);
                Ok(())
            }
            #[cfg(target_os = "linux")]
            {
                let store = linux_keyutils_keyring_store::Store::new()
                    .map_err(|e| anyhow::anyhow!("failed to init linux-keyutils store: {e}"))?;
                keyring_core::set_default_store(store);
                set_active_backend(BackendMode::LinuxKeyutils);
                Ok(())
            }
            #[cfg(not(any(target_os = "macos", target_os = "windows", target_os = "linux")))]
            {
                let store = keyring_core::mock::Store::new()
                    .map_err(|e| anyhow::anyhow!("failed to init keyring mock store: {e}"))?;
                keyring_core::set_default_store(store);
                set_active_backend(BackendMode::Mock);
                Ok(())
            }
        }
    }
}
