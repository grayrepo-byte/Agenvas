#!/bin/sh
set -eu

# Read mounted installation secrets before Java starts; never log their contents.
# Ordinary environment variables remain supported for deployments without file mounts.
read_secret() {
    secret_name=$1
    secret_file=$2
    secret_value=$3
    if [ -n "$secret_file" ]; then
        if [ -n "$secret_value" ]; then
            printf 'Set either %s or %s_FILE, not both.\n' "$secret_name" "$secret_name" >&2
            exit 1
        fi
        if [ ! -r "$secret_file" ] || [ ! -s "$secret_file" ]; then
            printf 'Missing or unreadable %s_FILE; restore the installation credentials volume.\n' "$secret_name" >&2
            exit 1
        fi
        secret_value=$(cat "$secret_file")
        if [ -z "$secret_value" ]; then
            printf '%s_FILE is empty.\n' "$secret_name" >&2
            exit 1
        fi
        export "$secret_name=$secret_value"
    fi
    unset secret_name secret_file secret_value
}

read_secret AGENVAS_DB_PASSWORD "${AGENVAS_DB_PASSWORD_FILE:-}" "${AGENVAS_DB_PASSWORD:-}"
read_secret AGENVAS_CREDENTIAL_MASTER_KEY "${AGENVAS_CREDENTIAL_MASTER_KEY_FILE:-}" "${AGENVAS_CREDENTIAL_MASTER_KEY:-}"

exec "$@"
