# Environment for every shell in the Secman shielded dev container.
# Sourced from /etc/profile.d, so `container exec ... bash -l` picks it up.

# --- Egress ------------------------------------------------------------------
# Everything that speaks HTTP goes through the allowlisting proxy. The firewall
# refuses direct egress anyway; these variables are what turns that refusal into
# a working connection instead of a confusing one.
export HTTP_PROXY=http://127.0.0.1:3128
export HTTPS_PROXY=http://127.0.0.1:3128
export http_proxy=$HTTP_PROXY
export https_proxy=$HTTPS_PROXY
export NO_PROXY=localhost,127.0.0.1,::1,0.0.0.0
export no_proxy=$NO_PROXY

# JVMs do not read $HTTPS_PROXY. JAVA_TOOL_OPTIONS is the only setting that
# reaches every one of them — the Gradle daemon, the in-process Kotlin compiler,
# the forked `run` task and the test JVMs alike — which matters because
# dependency resolution and the backend's own outbound calls (EOL, CrowdStrike,
# S3, OpenRouter) all happen inside those JVMs. It costs one banner line on
# stderr per JVM start; set SECMAN_DEV_JAVA_PROXY=0 before login to opt out.
if [ "${SECMAN_DEV_JAVA_PROXY:-1}" = "1" ]; then
    export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:+$JAVA_TOOL_OPTIONS }-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=3128 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=3128 -Dhttp.nonProxyHosts=localhost|127.0.0.1|0.0.0.0"
fi

# Go's module fetcher honours the proxy variables above, but its default
# GOPROXY/GOSUMDB hosts must stay reachable; src/relay has no third-party
# dependencies, so this only matters for toolchain downloads.
export GOFLAGS="${GOFLAGS:-}"

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

# --- Agents -------------------------------------------------------------------
export CLAUDE_CONFIG_DIR="$HOME/.claude"

# A visible marker: it should never be ambiguous whether a shell is inside the
# shielded container or on the Mac, because the two have very different reach.
export SECMAN_DEV_CONTAINER=1
if [ -n "${PS1:-}" ]; then
    PS1='\[\e[38;5;39m\](secman-box)\[\e[0m\] \u@\h:\w\$ '
fi
