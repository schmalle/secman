# Environment for every shell in the Secman shielded dev container.
# Sourced from /etc/profile.d, so `container exec ... bash -l` picks it up.
#
# Note what is NOT here: no HTTP_PROXY, no HTTPS_PROXY, no -Dhttps.proxyHost.
# Egress is filtered by the kernel, not by a proxy, so every tool connects
# directly and needs no proxy awareness — which also means no JAVA_TOOL_OPTIONS
# banner on every JVM start, and no tool that quietly ignores the variables and
# behaves differently from the rest.

# --- Toolchains ---------------------------------------------------------------
export JAVA_HOME=/opt/java
export PATH="$JAVA_HOME/bin:/usr/local/go/bin:$HOME/.local/bin:$PATH"
export GRADLE_USER_HOME="$HOME/.gradle"

# --- pass-cli -----------------------------------------------------------------
# There is no OS keyring in this container, so the default keyring provider has
# nothing to talk to. `file` keeps the session key in the session directory,
# which is a named volume — it never touches the Mac's filesystem and it
# survives container restarts, so `pass-cli login` is a one-time step.
export PROTON_PASS_SESSION_DIR="$HOME/.local/share/proton-pass-cli/.session"
export PROTON_PASS_KEY_PROVIDER="${PROTON_PASS_KEY_PROVIDER:-file}"

# --- Database -----------------------------------------------------------------
# Which database the stack talks to was chosen on the Mac (`up --db ...`) and
# settled by the entrypoint, which wrote the result to this file. Turning it
# into DB_CONNECT here — rather than leaving it as a hint the developer has to
# act on — is what makes the flag mean something: `startbackenddev.sh` and every
# other script honour a DB_CONNECT that is already set and fall back to pass-cli
# when it is not.
#
# In 'none' mode the file carries no URL and nothing below fires, so pass-cli
# stays the only source. An explicit DB_CONNECT in the environment always wins.
if [ -r /run/secman-dev/db/env ]; then
    . /run/secman-dev/db/env
    if [ -n "${SECMAN_DEV_DB_URL:-}" ] && [ -z "${DB_CONNECT:-}" ]; then
        export DB_CONNECT="$SECMAN_DEV_DB_URL"
    fi
    export SECMAN_DEV_DB_MODE SECMAN_DEV_DB_HOST SECMAN_DEV_DB_PORT SECMAN_DEV_DB_NAME SECMAN_DEV_DB_URL
    # Credentials only for the container's own throwaway database, whose user and
    # password devctl creates and which are a local development default, never a
    # secret and never read from pass-cli. In 'host' mode the Mac's database has
    # its own credentials, so they are left to pass-cli or to your shell — the
    # same as on the Mac.
    if [ "${SECMAN_DEV_DB_MODE:-}" = "container" ]; then
        export DB_USERNAME="${DB_USERNAME:-${SECMAN_DEV_DB_USER:-secman}}"
        export DB_PASSWORD="${DB_PASSWORD:-${SECMAN_DEV_DB_PASSWORD:-secman}}"
    fi
fi

# --- Agents -------------------------------------------------------------------
export CLAUDE_CONFIG_DIR="$HOME/.claude"

# A visible marker: it should never be ambiguous whether a shell is inside the
# shielded container or on the Mac, because the two have very different reach.
export SECMAN_DEV_CONTAINER=1
if [ -n "${PS1:-}" ]; then
    PS1='\[\e[38;5;39m\](secman-box)\[\e[0m\] \u@\h:\w\$ '
fi
