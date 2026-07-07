# Sukify — Spotify-style music streaming (MVP)

A Spotify clone built with **Laravel + Vue 3 + PostgreSQL**, fully dockerized for
Windows / Docker Desktop (WSL2 backend).

## Stack

| Layer | Tech |
|---|---|
| Backend | Laravel 13 (PHP 8.4, php-fpm) |
| DB | PostgreSQL 17 |
| Cache / queues / sessions | Redis 7 |
| Websockets | Centrifugo v5 (device sync + Jam) |
| Search | Laravel Scout + Meilisearch |
| Object storage | MinIO (dev) / R2 · B2 (prod, S3 API, `.env` only) |
| User frontend | Vue 3 SPA (Vite, Pinia, Vue Router) |
| Admin | Inertia.js + Vue inside Laravel |
| Audio | ffmpeg (backend) + Web Audio API (frontend) |
| Images | Intervention Image + league/color-extractor → WebP |

## Layout

```
backend/    Laravel app (API + admin via Inertia)
frontend/   Vue 3 SPA (user-facing player)
docker/     Container configs (php, nginx, centrifugo, minio)
_reference/ Original Spotify HTML dumps used for visual parity (git-ignored)
```

Dependencies (`vendor/`, `node_modules/`) live in **named Docker volumes**, not on
the host bind-mount, for filesystem speed on Windows.

## Quick start

```bash
cp .env.example .env          # root env (compose interpolation)
docker compose up -d          # boots the whole stack
```

The Laravel `backend/.env` is already wired to the compose service names.

### Ports (host)

| Service | URL |
|---|---|
| API / admin (nginx) | http://localhost:8088 |
| Vue SPA (Vite dev) | http://localhost:5173 |
| Centrifugo | ws://localhost:8000 |
| Meilisearch | http://localhost:7700 |
| MinIO API / console | http://localhost:9000 / :9001 |
| PostgreSQL | localhost:5432 |
| Redis | localhost:6379 |

### Common commands

```bash
docker compose exec app php artisan migrate
docker compose exec app php artisan tinker
docker compose logs -f queue-worker
```

## Storage prefix

All media is stored under `STORAGE_ROOT_PREFIX` inside the bucket
(`sukify-media/covers/...`, `sukify-media/audio/...`) so the whole app's tree can be
deleted independently of anything else in the bucket.

---

Built stage-by-stage — see the technical spec for scope. Currently: **Stage 1
(infrastructure + schema) complete.**
