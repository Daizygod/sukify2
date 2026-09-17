// Окно живёт в трее, консоль на Windows не нужна.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod discord;

use discord::{start_seconds, DiscordIpc, DiscordUser, NowPlaying};
use serde::Serialize;
use serde_json::Value;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};
use tauri::menu::{CheckMenuItem, Menu, MenuItem, PredefinedMenuItem};
use tauri::tray::TrayIconBuilder;
use tauri::{Manager, WindowEvent};
use tauri_plugin_autostart::MacosLauncher;

/// Discord принимает не больше нескольких SET_ACTIVITY за 20 секунд.
/// Четыре секунды — запас, при котором смена трека всё ещё выглядит мгновенной.
const MIN_INTERVAL: Duration = Duration::from_secs(4);

/// Discord не запущен — не долбимся в сокет чаще, чем раз в десять секунд.
const RECONNECT_DELAY: Duration = Duration::from_secs(10);

/// Вкладка вещает состояние каждые 5 секунд. Замолчала надолго — браузер
/// закрыли без прощания, и статус пора снимать. Страховка живёт здесь, а не в
/// webview: у скрытого окна таймеры притормаживают, у фонового потока — нет.
const STALE_AFTER: Duration = Duration::from_secs(25);

#[derive(Default)]
struct Shared {
    /// Что хотим показывать (последнее, что прислал webview).
    desired: Option<NowPlaying>,
    /// Когда webview присылал состояние в последний раз.
    desired_at: Option<Instant>,
    /// Что реально ушло в Discord, и с каким start.
    sent: Option<NowPlaying>,
    sent_start: i64,
    last_sent_at: Option<Instant>,
    /// Выключатель из трея — глушит трансляцию, не трогая соединение.
    muted: bool,
    connected: bool,
    user: Option<DiscordUser>,
    error: Option<String>,
}

#[derive(Serialize, Clone)]
struct Status {
    connected: bool,
    muted: bool,
    user: Option<DiscordUser>,
    error: Option<String>,
}

type SharedState = Arc<Mutex<Shared>>;

// --- Команды для webview ----------------------------------------------------

/// Состояние плеера из Centrifugo. `None` — ничего не играет, статус снимаем.
#[tauri::command]
fn set_now_playing(state: tauri::State<'_, SharedState>, now: Option<NowPlaying>) {
    let mut shared = state.lock().unwrap();
    shared.desired = now;
    shared.desired_at = Some(Instant::now());
}

#[tauri::command]
fn get_status(state: tauri::State<'_, SharedState>) -> Status {
    let shared = state.lock().unwrap();
    Status {
        connected: shared.connected,
        muted: shared.muted,
        user: shared.user.clone(),
        error: shared.error.clone(),
    }
}

/// Конфиг (адрес сервера, токен, id устройства) — простой JSON в папке приложения.
/// Отдельный плагин-хранилище ради одного файла не подключаем.
#[tauri::command]
fn config_load(app: tauri::AppHandle) -> Value {
    let Ok(path) = config_path(&app) else {
        return Value::Null;
    };

    std::fs::read_to_string(path)
        .ok()
        .and_then(|raw| serde_json::from_str(&raw).ok())
        .unwrap_or(Value::Null)
}

#[tauri::command]
fn config_save(app: tauri::AppHandle, value: Value) -> Result<(), String> {
    let path = config_path(&app)?;

    if let Some(dir) = path.parent() {
        std::fs::create_dir_all(dir).map_err(|e| e.to_string())?;
    }

    let body = serde_json::to_string_pretty(&value).map_err(|e| e.to_string())?;
    std::fs::write(path, body).map_err(|e| e.to_string())
}

/// Открыть ссылку в системном браузере — для экрана подтверждения привязки.
#[tauri::command]
fn open_url(url: String) -> Result<(), String> {
    // Пускаем только http(s): команда уходит в оболочку ОС.
    if !url.starts_with("http://") && !url.starts_with("https://") {
        return Err("Ссылку такого вида не открываем".into());
    }

    #[cfg(target_os = "windows")]
    let result = std::process::Command::new("rundll32")
        .args(["url.dll,FileProtocolHandler", &url])
        .spawn();

    #[cfg(target_os = "macos")]
    let result = std::process::Command::new("open").arg(&url).spawn();

    #[cfg(all(unix, not(target_os = "macos")))]
    let result = std::process::Command::new("xdg-open").arg(&url).spawn();

    result.map(|_| ()).map_err(|e| e.to_string())
}

fn config_path(app: &tauri::AppHandle) -> Result<std::path::PathBuf, String> {
    app.path()
        .app_config_dir()
        .map(|dir| dir.join("config.json"))
        .map_err(|e| e.to_string())
}

// --- Фоновый поток: единственный, кто говорит с Discord ---------------------

fn spawn_worker(shared: SharedState, client_id: String) {
    std::thread::spawn(move || {
        let mut ipc: Option<DiscordIpc> = None;
        let mut retry_at = Instant::now();

        loop {
            std::thread::sleep(Duration::from_millis(500));

            if ipc.is_none() {
                if Instant::now() < retry_at {
                    continue;
                }

                match DiscordIpc::connect(&client_id) {
                    Ok(client) => {
                        let mut state = shared.lock().unwrap();
                        state.connected = true;
                        state.user = Some(client.user.clone());
                        state.error = None;
                        // После переподключения Discord ничего о нас не помнит —
                        // отправляем статус заново, даже если он не менялся.
                        state.sent = None;
                        state.last_sent_at = None;
                        drop(state);

                        ipc = Some(client);
                    }
                    Err(message) => {
                        let mut state = shared.lock().unwrap();
                        state.connected = false;
                        state.user = None;
                        state.error = Some(message);
                        drop(state);

                        retry_at = Instant::now() + RECONNECT_DELAY;
                        continue;
                    }
                }
            }

            let target = {
                let state = shared.lock().unwrap();

                let stale = state
                    .desired_at
                    .map_or(true, |at| at.elapsed() > STALE_AFTER);

                let target = if state.muted || stale {
                    None
                } else {
                    state.desired.clone()
                };

                let ready = state
                    .last_sent_at
                    .map_or(true, |sent_at| sent_at.elapsed() >= MIN_INTERVAL);

                if ready && needs_update(&state.sent, &target, state.sent_start) {
                    Some(target)
                } else {
                    None
                }
            };

            let Some(target) = target else { continue };

            let outcome = ipc
                .as_mut()
                .expect("client is connected")
                .set_activity(target.as_ref());

            let mut state = shared.lock().unwrap();
            match outcome {
                Ok(()) => {
                    state.sent_start = target.as_ref().map_or(0, start_seconds);
                    state.sent = target;
                    state.last_sent_at = Some(Instant::now());
                }
                Err(message) => {
                    // Сокет умер (Discord закрыли) — роняем и переподключаемся.
                    state.connected = false;
                    state.user = None;
                    state.error = Some(message);
                    state.sent = None;
                    state.last_sent_at = None;
                    drop(state);

                    ipc = None;
                    retry_at = Instant::now() + RECONNECT_DELAY;
                }
            }
        }
    });
}

/// Стоит ли вообще дёргать Discord. Позиция трека сама по себе не повод:
/// полосу он отсчитывает от start, и пересылка того же start ничего не меняет.
fn needs_update(sent: &Option<NowPlaying>, target: &Option<NowPlaying>, sent_start: i64) -> bool {
    match (sent, target) {
        (None, None) => false,
        (None, Some(_)) | (Some(_), None) => true,
        (Some(old), Some(new)) => {
            old.title != new.title
                || old.artists != new.artists
                || old.album != new.album
                || old.cover_url != new.cover_url
                || old.track_url != new.track_url
                || old.playing != new.playing
                || old.show_button != new.show_button
                || old.show_cover != new.show_cover
                // Перемотка: расчётное начало трека уехало больше чем на 2 с.
                || (new.playing && (start_seconds(new) - sent_start).abs() > 2)
        }
    }
}

// --- Приложение -------------------------------------------------------------

fn main() {
    let shared: SharedState = Arc::new(Mutex::new(Shared::default()));

    tauri::Builder::default()
        .plugin(tauri_plugin_autostart::init(
            MacosLauncher::LaunchAgent,
            Some(vec!["--minimized"]),
        ))
        .manage(shared.clone())
        .invoke_handler(tauri::generate_handler![
            set_now_playing,
            get_status,
            config_load,
            config_save,
            open_url
        ])
        .setup(move |app| {
            // client_id приложения Discord — публичное значение, не секрет:
            // прошиваем на сборке, а для `tauri dev` разрешаем ту же
            // переменную окружения в рантайме.
            let client_id = option_env!("SUKIFY_DISCORD_CLIENT_ID")
                .map(str::to_string)
                .filter(|id| !id.is_empty())
                .or_else(|| std::env::var("SUKIFY_DISCORD_CLIENT_ID").ok())
                .unwrap_or_default();

            if client_id.is_empty() {
                let mut state = shared.lock().unwrap();
                state.error = Some(
                    "Не задан SUKIFY_DISCORD_CLIENT_ID на сборке — статус ставить не от чьего имени"
                        .into(),
                );
            } else {
                spawn_worker(shared.clone(), client_id);
            }

            build_tray(app.handle(), shared.clone())?;

            // Запуск из автозагрузки — сразу в трей, без окна.
            if std::env::args().any(|arg| arg == "--minimized") {
                if let Some(window) = app.get_webview_window("main") {
                    let _ = window.hide();
                }
            }

            Ok(())
        })
        .on_window_event(|window, event| {
            // Крестик прячет окно: приложение должно продолжать работать,
            // иначе статус пропадёт вместе с ним.
            if let WindowEvent::CloseRequested { api, .. } = event {
                api.prevent_close();
                let _ = window.hide();
            }
        })
        .run(tauri::generate_context!())
        .expect("не удалось запустить Sukify Desktop");
}

fn build_tray(app: &tauri::AppHandle, shared: SharedState) -> tauri::Result<()> {
    let open = MenuItem::with_id(app, "open", "Открыть", true, None::<&str>)?;
    let broadcast = CheckMenuItem::with_id(
        app,
        "broadcast",
        "Транслировать в Discord",
        true,
        true,
        None::<&str>,
    )?;
    let separator = PredefinedMenuItem::separator(app)?;
    let quit = MenuItem::with_id(app, "quit", "Выход", true, None::<&str>)?;

    let menu = Menu::with_items(app, &[&open, &broadcast, &separator, &quit])?;

    TrayIconBuilder::with_id("sukify")
        .icon(app.default_window_icon().cloned().expect("есть иконка окна"))
        .tooltip("Sukify")
        .menu(&menu)
        .show_menu_on_left_click(false)
        .on_menu_event(move |app, event| match event.id.as_ref() {
            "open" => {
                if let Some(window) = app.get_webview_window("main") {
                    let _ = window.show();
                    let _ = window.set_focus();
                }
            }
            "broadcast" => {
                let mut state = shared.lock().unwrap();
                state.muted = !state.muted;
            }
            "quit" => app.exit(0),
            _ => {}
        })
        .build(app)?;

    Ok(())
}
