# Sukify Desktop — статус в Discord

Маленькое приложение в трее. Слушает тот же канал Centrifugo, что и вкладки
браузера («Sukify Connect»), и показывает текущий трек в статусе Discord:

```
Слушает Sukify
Bulls On Parade            ← details
Rage Against the Machine   ← state
[обложка] ────●──── 1:47 / 3:52
[ Слушать в Sukify ]
```

## Зачем вообще отдельная программа

Discord меняет активность только через локальный IPC-сокет
(`\\.\pipe\discord-ipc-0` на Windows, `$XDG_RUNTIME_DIR/discord-ipc-N` на
Unix). REST-эндпоинта «поставить пользователю активность» не существует, и
OAuth его не даёт — поэтому ни SPA в браузере, ни бэкенд Laravel статус
поставить не могут. Нужен процесс рядом с запущенным клиентом Discord.

Зелёную карточку «Listening to Spotify» повторить нельзя: это первопартийная
интеграция Discord. Всё остальное — тип `Listening`, полоса прогресса,
обложка, кнопка — доступно обычному приложению.

## Что нужно

Всегда — **десктопный клиент Discord** (в браузерной версии IPC нет).

Всё остальное зависит от того, как собираешь: в облаке (вариант А ниже) не
нужно ничего, локально (вариант Б) — Rust, Node 20+ и системные зависимости
Tauri, https://tauri.app/start/prerequisites.

## Приложение в Discord

1. https://discord.com/developers/applications → **New Application**, имя
   `Sukify` (оно и будет видно в статусе как «Слушает **Sukify**»).
2. **General Information** → скопируй **Application ID**. Это `client_id`,
   значение публичное.
3. **OAuth2** → **Redirects** → добавь ровно тот адрес, что стоит в `.env`
   как `DISCORD_REDIRECT_URI`. Для прода это
   `https://sukify.nepalimsya.ru/api/discord/callback`; локальный
   `http://localhost:8088/api/discord/callback` нужен, только если будешь
   привязывать аккаунт на локальном стенде. Там же возьми **Client Secret**
   для `DISCORD_CLIENT_SECRET`.
4. **Rich Presence → Art Assets** → загрузи
   [`discord-assets/paused.png`](discord-assets/paused.png) под именем
   `paused`. Она показывается маленьким кружком, когда трек на паузе.
   Без неё ничего не сломается — Discord просто не нарисует значок.

В `backend/.env`:

```
DISCORD_CLIENT_ID=<Application ID>
DISCORD_CLIENT_SECRET=<Client Secret>
DISCORD_REDIRECT_URI=http://localhost:8088/api/discord/callback
```

## Вариант А: собрать в GitHub Actions (ничего ставить не надо)

Если не собираешься править код компаньона — не ставь ни Rust, ни C++ Build
Tools. Сборку делает workflow [`desktop.yml`](../.github/workflows/desktop.yml):

1. **Settings → Secrets and variables → Actions → New repository secret**:
   имя `SUKIFY_DISCORD_CLIENT_ID`, значение — Application ID.
2. **Actions → Desktop build → Run workflow**.
3. Через ~10 минут скачай артефакт `sukify-desktop-windows` — внутри установщик.

## Вариант Б: собрать локально

Нужны Rust, Node 20+ и системные зависимости Tauri (на Windows — MSVC Build
Tools + WebView2).

Первым делом сгенерируй набор иконок: в репозитории лежит только исходный PNG,
а установщику нужен `.ico`, и без него не заведётся даже `dev`.

```bash
cd desktop
npm install
npm run tauri icon src-tauri/icons/icon.png
```

`client_id` попадает в бинарник на этапе сборки, для `dev` хватит переменной
окружения в той же сессии терминала.

PowerShell:

```powershell
$env:SUKIFY_DISCORD_CLIENT_ID = "<Application ID>"; npm run tauri dev
```

bash:

```bash
SUKIFY_DISCORD_CLIENT_ID=<Application ID> npm run tauri dev
```

Установщик — `npm run tauri build` с той же переменной, результат в
`src-tauri/target/release/bundle/`.

## Подключение к боевому серверу

Локальный стек компаньону не нужен: в поле адреса при первом запуске уже
подставлен прод, локальный стенд вписывают руками. Но сервер должен пустить его
origin — Centrifugo проверяет `Origin` при апгрейде вебсокета, а
`centrifugo.prod.json` лежит вне git, только на сервере:

```bash
# на сервере, /home/deploy/sukify/centrifugo.prod.json
# в allowed_origins добавить:
#   "http://tauri.localhost", "tauri://localhost"
docker compose -f docker-compose.prod.yml restart centrifugo
```

На `sukify.nepalimsya.ru` это уже прописано.

Со стороны Laravel то же самое уже лежит в `config/cors.php` и приедет обычным
деплоем.

## Как привязывается аккаунт

Device code flow, как у приложений для телевизора:

1. Приложение просит у сервера пару кодов и показывает короткий: `SUKI-4F2A`.
2. Открывает браузер на `/link-device?code=SUKI-4F2A`.
3. Ты подтверждаешь на уже залогиненной странице Sukify.
4. Приложение меняет длинный код на токен Sanctum и сохраняет его в
   `config.json` в папке приложения.

Пароль приложение не видит. Отвязать можно с обеих сторон: кнопкой в самом
приложении или в **Настройки → Discord** в вебе (там же отзывается токен).

## Что стоит знать

- **Обложки скачивает CDN Discord, а не приложение.** Адрес должен быть
  доступен из интернета. В dev-окружении обложки отдаёт MinIO на
  `localhost:9000` — Discord туда не дойдёт, статус будет без картинки.
  Приложение это замечает и пишет об этом в окне.
- **Лимит обновлений.** Discord принимает всего несколько `SET_ACTIVITY` за
  20 секунд, поэтому статус обновляется не чаще раза в 4 секунды. Перемотка
  меньше чем на 2 секунды игнорируется — полосу всё равно рисует сам Discord
  по паре `start`/`end`.
- **Кнопка под статусом** у типа `Listening` в некоторых клиентах не
  рисуется. Если мешает — выключи её в настройках Sukify.
- **Статус исчезает**, если закрыть Discord, выключить трансляцию в трее или
  если вкладка Sukify замолчала дольше 25 секунд (браузер закрыли).
- **Приватность.** Трансляция целиком под твоим контролем: тумблеры в
  **Настройки → Discord** доезжают до приложения за минуту, а пункт
  «Транслировать в Discord» в меню трея выключает её мгновенно.

## Как это устроено

```
вкладка Sukify ──публикует state──► Centrifugo ──►  Sukify Desktop
  (player.js/devices.js)            user:playback#id   │
                                                       │ webview: centrifuge-js
                                                       ▼
                                                    Rust: IPC → Discord
```

- `src/main.js` — привязка, подписка на канал, отправка состояния в Rust.
- `src-tauri/src/discord.rs` — протокол IPC руками (кадры, рукопожатие,
  `SET_ACTIVITY`), сборка activity.
- `src-tauri/src/main.rs` — трей, фоновый поток с троттлингом и
  переподключением, хранение конфига.

Компаньон намеренно **не публикует** `hello`/`ping`: иначе он появился бы в
списке устройств Sukify Connect как пульт, на который нельзя перевести
воспроизведение.
