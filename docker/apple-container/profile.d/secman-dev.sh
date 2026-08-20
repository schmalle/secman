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

# --- Agents -------------------------------------------------------------------
export CLAUDE_CONFIG_DIR="$HOME/.claude"

# A visible marker: it should never be ambiguous whether a shell is inside the
# shielded container or on the Mac, because the two have very different reach.
export SECMAN_DEV_CONTAINER=1
if [ -n "${PS1:-}" ]; then
    PS1='\[\e[38;5;39m\](secman-box)\[\e[0m\] \u@\h:\w\$ '
fi
