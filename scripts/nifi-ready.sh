#!/usr/bin/env bash

set -euo pipefail

resolve_nifi_host() {
  local host="${NIFI_WEB_HTTPS_HOST:-${HOSTNAME:-nifi}}"

  case "$host" in
    ""|"0.0.0.0"|"::"|"[::]")
      host="${HOSTNAME:-nifi}"
      ;;
  esac

  printf '%s' "$host"
}

default_host="$(resolve_nifi_host)"
default_port="${NIFI_WEB_HTTPS_PORT:-8443}"
default_base_url="${NIFI_BASE_URL:-https://${default_host}:${default_port}/nifi-api}"
base_url="${NIFI_HEALTHCHECK_URL:-$default_base_url}"
base_url="${base_url%/}"

username="${SINGLE_USER_CREDENTIALS_USERNAME:-${NIFI_USERNAME:-}}"
password="${SINGLE_USER_CREDENTIALS_PASSWORD:-${NIFI_PASSWORD:-}}"

if [[ -z "$username" || -z "$password" ]]; then
  echo "NiFi readiness check requires credentials." >&2
  exit 1
fi

token="$(
  curl --silent --show-error --fail --insecure \
    --connect-timeout 5 \
    --max-time 20 \
    --request POST \
    --header "Content-Type: application/x-www-form-urlencoded" \
    --data-urlencode "username=${username}" \
    --data-urlencode "password=${password}" \
    "${base_url}/access/token" |
    tr -d '\r\n'
)"

[[ -n "$token" ]]

curl --silent --show-error --fail --insecure \
  --connect-timeout 5 \
  --max-time 20 \
  --header "Accept: application/json" \
  --header "Authorization: Bearer ${token}" \
  "${base_url}/flow/current-user" |
  jq -e '(.identity // "") | length > 0' >/dev/null
