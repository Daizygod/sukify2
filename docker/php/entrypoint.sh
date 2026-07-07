#!/bin/sh
set -e

# Ensure Laravel's writable dirs exist and are writable even when the host
# bind-mount brings in root-owned / restrictive permissions (Windows/WSL2).
mkdir -p \
    storage/framework/cache/data \
    storage/framework/sessions \
    storage/framework/views \
    storage/logs \
    storage/app/public \
    bootstrap/cache

chmod -R 777 storage bootstrap/cache 2>/dev/null || true

exec "$@"
