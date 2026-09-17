//! Клиент Discord IPC — ровно столько протокола, сколько нужно для статуса.
//!
//! Готовой библиотеки здесь нет намеренно: протокол занимает полторы сотни
//! строк, зато мы сами решаем, какие поля уходят в activity (`type`,
//! `status_display_type`), и не зависим от того, успела ли обёртка их завезти.
//!
//! Формат кадра: [opcode u32 LE][длина u32 LE][JSON]. Клиент Discord слушает
//! локальный сокет, поэтому всё это работает только рядом с запущенным
//! приложением Discord — из браузера или с сервера статус поставить нельзя.

use serde::Serialize;
use serde_json::{json, Value};
use std::io::{Read, Write};
use std::time::{SystemTime, UNIX_EPOCH};

#[cfg(unix)]
use std::os::unix::net::UnixStream;

const OP_HANDSHAKE: u32 = 0;
const OP_FRAME: u32 = 1;
const OP_CLOSE: u32 = 2;

/// Discord отдаёт активность типом «Listening» — тот же ряд, что у Spotify:
/// с полосой прогресса, когда заданы start и end.
const ACTIVITY_LISTENING: u8 = 2;

/// В списке участников показываем название трека, а не имя приложения.
const STATUS_DISPLAY_STATE: u8 = 1;

pub trait Transport: Read + Write + Send {}
impl<T: Read + Write + Send> Transport for T {}

#[derive(Serialize, Clone, Debug, Default)]
#[serde(rename_all = "camelCase")]
pub struct DiscordUser {
    pub id: String,
    pub username: String,
    pub display_name: String,
}

/// То, что приезжает из webview: уже разобранное состояние плеера.
#[derive(serde::Deserialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct NowPlaying {
    pub playing: bool,
    pub title: String,
    pub artists: String,
    #[serde(default)]
    pub album: Option<String>,
    #[serde(default)]
    pub cover_url: Option<String>,
    #[serde(default)]
    pub track_url: Option<String>,
    pub position_ms: i64,
    pub duration_ms: i64,
    #[serde(default)]
    pub show_button: bool,
    #[serde(default)]
    pub show_cover: bool,
}

pub struct DiscordIpc {
    conn: Box<dyn Transport>,
    nonce: u64,
    pub user: DiscordUser,
}

impl DiscordIpc {
    /// Найти запущенный клиент и представиться. Ошибка здесь — норма:
    /// Discord может быть просто не запущен, вызывающий повторит позже.
    pub fn connect(client_id: &str) -> Result<Self, String> {
        let conn = open_socket()?;
        let mut ipc = DiscordIpc {
            conn,
            nonce: 0,
            user: DiscordUser::default(),
        };

        ipc.send(OP_HANDSHAKE, &json!({ "v": 1, "client_id": client_id }))?;

        let (opcode, body) = ipc.recv()?;
        if opcode == OP_CLOSE {
            // Чаще всего — неверный client_id: приложение с таким ID не найдено.
            let message = body
                .pointer("/message")
                .and_then(Value::as_str)
                .unwrap_or("Discord отклонил подключение");
            return Err(message.to_string());
        }

        if let Some(user) = body.pointer("/data/user") {
            let username = user
                .get("username")
                .and_then(Value::as_str)
                .unwrap_or_default()
                .to_string();
            let display_name = user
                .get("global_name")
                .and_then(Value::as_str)
                .filter(|s| !s.is_empty())
                .unwrap_or(&username)
                .to_string();

            ipc.user = DiscordUser {
                id: user
                    .get("id")
                    .and_then(Value::as_str)
                    .unwrap_or_default()
                    .to_string(),
                username,
                display_name,
            };
        }

        Ok(ipc)
    }

    /// Поставить статус. `None` — убрать активность совсем.
    /// `start` — начало трека в unix-секундах, посчитанное там, где состояние
    /// пришло из вкладки: пересчитывать его здесь нельзя (см. `start_seconds`).
    pub fn set_activity(&mut self, now: Option<&NowPlaying>, start: i64) -> Result<(), String> {
        let activity = now.map(|now| build_activity(now, start));

        self.nonce += 1;
        let payload = json!({
            "cmd": "SET_ACTIVITY",
            "args": {
                // Discord снимает активность, когда процесс с этим pid умирает.
                "pid": std::process::id(),
                "activity": activity,
            },
            "nonce": format!("{}-{}", unix_millis(), self.nonce),
        });

        self.send(OP_FRAME, &payload)?;

        // Ответ читаем всегда: иначе он копится в буфере сокета и следующий
        // recv разбирает чужой кадр.
        let (opcode, body) = self.recv()?;
        if opcode == OP_CLOSE {
            return Err(format!("Discord закрыл соединение: {body}"));
        }

        Ok(())
    }

    fn send(&mut self, opcode: u32, payload: &Value) -> Result<(), String> {
        let data = serde_json::to_vec(payload).map_err(|e| e.to_string())?;

        let mut frame = Vec::with_capacity(8 + data.len());
        frame.extend_from_slice(&opcode.to_le_bytes());
        frame.extend_from_slice(&(data.len() as u32).to_le_bytes());
        frame.extend_from_slice(&data);

        self.conn.write_all(&frame).map_err(|e| e.to_string())?;
        self.conn.flush().map_err(|e| e.to_string())
    }

    fn recv(&mut self) -> Result<(u32, Value), String> {
        let mut header = [0u8; 8];
        self.conn.read_exact(&mut header).map_err(|e| e.to_string())?;

        let opcode = u32::from_le_bytes([header[0], header[1], header[2], header[3]]);
        let length = u32::from_le_bytes([header[4], header[5], header[6], header[7]]) as usize;

        let mut body = vec![0u8; length];
        if length > 0 {
            self.conn.read_exact(&mut body).map_err(|e| e.to_string())?;
        }

        Ok((opcode, serde_json::from_slice(&body).unwrap_or(Value::Null)))
    }
}

/// Собрать activity в том виде, в каком его ждёт Discord.
fn build_activity(now: &NowPlaying, start: i64) -> Value {
    let mut activity = json!({
        "type": ACTIVITY_LISTENING,
        "status_display_type": STATUS_DISPLAY_STATE,
        // details — верхняя строка, state — нижняя (так же, как у Spotify).
        "details": clamp(&now.title),
        "state": clamp(&now.artists),
    });

    let map = activity.as_object_mut().expect("activity is an object");

    // Полоса прогресса живёт на паре start/end в СЕКУНДАХ. На паузе её не
    // шлём вовсе: Discord умеет только «бегущую» полосу и застывшую покажет
    // как уезжающую в прошлое.
    if now.playing && now.duration_ms > 0 {
        map.insert(
            "timestamps".into(),
            json!({ "start": start, "end": start + now.duration_ms / 1000 }),
        );
    }

    let mut assets = serde_json::Map::new();

    if now.show_cover {
        if let Some(url) = now.cover_url.as_deref().filter(|u| !u.is_empty()) {
            // Внешний адрес разрешён напрямую — картинку скачивает CDN Discord,
            // поэтому localhost он не увидит.
            assets.insert("large_image".into(), json!(url));
            if let Some(album) = now.album.as_deref().filter(|a| !a.is_empty()) {
                assets.insert("large_text".into(), json!(clamp(album)));
            }
        }
    }

    if !now.playing {
        assets.insert("small_image".into(), json!("paused"));
        assets.insert("small_text".into(), json!("На паузе"));
    }

    if !assets.is_empty() {
        map.insert("assets".into(), Value::Object(assets));
    }

    if now.show_button {
        if let Some(url) = now.track_url.as_deref().filter(|u| u.starts_with("http")) {
            map.insert(
                "buttons".into(),
                json!([{ "label": "Слушать в Sukify", "url": url }]),
            );
        }
    }

    activity
}

/// Расчётное начало трека в unix-секундах: от него Discord рисует полосу.
///
/// Считать его нужно ровно там, где состояние пришло из вкладки — позиция в нём
/// измерена тогда же. Если пересчитать при отправке, start уедет вперёд на
/// возраст состояния (до MIN_INTERVAL), и полоса дёрнется назад.
pub fn start_seconds(now: &NowPlaying) -> i64 {
    unix_seconds() - (now.position_ms.max(0) / 1000)
}

/// Discord режет строки длиннее 128 байт и отвечает ошибкой на пустые.
fn clamp(value: &str) -> String {
    let trimmed = value.trim();
    let safe = if trimmed.is_empty() { "—" } else { trimmed };

    // Режем по границам символов, иначе кириллица рвётся посреди кодовой точки.
    safe.chars().take(120).collect()
}

fn unix_seconds() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or_default()
}

fn unix_millis() -> u128 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis())
        .unwrap_or_default()
}

#[cfg(windows)]
fn open_socket() -> Result<Box<dyn Transport>, String> {
    use std::fs::OpenOptions;

    for index in 0..10 {
        let path = format!(r"\\.\pipe\discord-ipc-{index}");
        if let Ok(pipe) = OpenOptions::new().read(true).write(true).open(&path) {
            return Ok(Box::new(pipe));
        }
    }

    Err("Discord не запущен (не нашли ни один discord-ipc сокет)".into())
}

#[cfg(unix)]
fn open_socket() -> Result<Box<dyn Transport>, String> {
    let base = ["XDG_RUNTIME_DIR", "TMPDIR", "TMP", "TEMP"]
        .iter()
        .find_map(|key| std::env::var(key).ok())
        .unwrap_or_else(|| "/tmp".to_string());
    let base = base.trim_end_matches('/').to_string();

    // Flatpak и Snap прячут сокет в своей поддиректории.
    let prefixes = [
        String::new(),
        "app/com.discordapp.Discord/".to_string(),
        "snap.discord/".to_string(),
    ];

    for prefix in &prefixes {
        for index in 0..10 {
            let path = format!("{base}/{prefix}discord-ipc-{index}");
            if let Ok(socket) = UnixStream::connect(&path) {
                return Ok(Box::new(socket));
            }
        }
    }

    Err("Discord не запущен (не нашли ни один discord-ipc сокет)".into())
}
