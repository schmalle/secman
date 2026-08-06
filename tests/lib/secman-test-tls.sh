#!/usr/bin/env bash

# Test scripts target local/dev SecMan environments that may use self-signed
# certificates. Set SECMAN_INSECURE=false to require normal TLS verification.
: "${SECMAN_INSECURE:=true}"
export SECMAN_INSECURE

secman_test_tls_insecure_enabled() {
    local value
    value="$(printf '%s' "${SECMAN_INSECURE:-}" | tr '[:upper:]' '[:lower:]')"
    case "$value" in
        true|1|yes) return 0 ;;
        *) return 1 ;;
    esac
}

if secman_test_tls_insecure_enabled; then
    export NODE_TLS_REJECT_UNAUTHORIZED=0
fi

if type -P curl >/dev/null 2>&1; then
    curl() {
        if secman_test_tls_insecure_enabled; then
            command curl -k "$@"
        else
            command curl "$@"
        fi
    }
fi
