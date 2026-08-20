#!/usr/bin/env bash
# Egress firewall for the Secman shielded dev container.
# =============================================================================
# Runs as root inside the container VM, before any agent or build process
# starts. There is no proxy: iptables is the whole enforcement layer.
#
# Model
#   OUTPUT policy DROP. The only ways out are
#     1. loopback,
#     2. replies on connections the container already established,
#     3. DNS to the resolvers in /etc/resolv.conf,
#     4. TCP to an address in the allow-set, on $EGRESS_PORTS,
#     5. with `--db host`, TCP to exactly one address on exactly one port: the
#        MariaDB installed on the Mac. Not part of the allow-set — that set is
#        only ever matched on $EGRESS_PORTS, and a database is not on those.
#   INPUT policy DROP, opened only for the published dev ports and for replies.
#
# The allow-set is built from
#   * every hostname in the rendered allowlist, resolved to its A records,
#   * every address/CIDR literal in the rendered allowlist,
#   * GitHub's published ranges from https://api.github.com/meta, because a
#     service spread over that many addresses cannot be covered by resolving a
#     handful of names.
#
# What this cannot do, and there is no way around it at this layer: addresses
# are not names. Permitting api.anthropic.com permits whatever else answers on
# the same CDN address, and an address that rotates out of DNS stays permitted
# until the next refresh while the new one is not. That is why refresh-egress
# exists and why the entrypoint runs it on a timer.
# =============================================================================
set -euo pipefail

ALLOWLIST_RENDERED=${ALLOWLIST_RENDERED:-/run/secman-dev/egress/allowlist.rendered}
STATE_DIR=$(dirname "$ALLOWLIST_RENDERED")
SET_NAME=secman-egress
CHAIN=SECMAN-EGRESS
# 22 is here so `git push` over SSH works to an allowlisted host; drop it from
# SECMAN_EGRESS_PORTS if you only ever use HTTPS remotes.
EGRESS_PORTS=${SECMAN_EGRESS_PORTS:-80,443,22}
INBOUND_PORTS=${SECMAN_EGRESS_INBOUND_PORTS:-443,3306,4321,8080}
GITHUB_META=${SECMAN_EGRESS_GITHUB_META:-1}

# The database selection, as the entrypoint settled it. Read from the file
# rather than the environment because `refresh-egress` rebuilds this ruleset
# later, from a root shell that inherited none of the entrypoint's variables —
# and a refresh that quietly dropped the database rule would take the stack down
# half an hour after it started working.
DB_ENV=${SECMAN_DEV_DB_ENV:-/run/secman-dev/db/env}
DB_MODE=""; DB_HOST=""; DB_PORT=""
if [ -r "$DB_ENV" ]; then
    DB_MODE=$(sed -n "s/^SECMAN_DEV_DB_MODE='\\(.*\\)'\$/\\1/p" "$DB_ENV")
    DB_HOST=$(sed -n "s/^SECMAN_DEV_DB_HOST='\\(.*\\)'\$/\\1/p" "$DB_ENV")
    DB_PORT=$(sed -n "s/^SECMAN_DEV_DB_PORT='\\(.*\\)'\$/\\1/p" "$DB_ENV")
fi

log() { printf '[egress] %s\n' "$*"; }
die() { printf '[egress] FATAL: %s\n' "$*" >&2; exit 1; }

[ "$(id -u)" -eq 0 ] || die "must run as root"
mkdir -p "$STATE_DIR"

# Re-validated here even though the entrypoint already checked: this is where
# the values reach `iptables`, and a rule built from an unchecked string is the
# one that ends up permitting something nobody asked for (CLAUDE.md §A03).
if [ "$DB_MODE" = "host" ]; then
    printf '%s\n' "$DB_HOST" | grep -qE '^([0-9]{1,3}\.){3}[0-9]{1,3}$' \
        || die "database host '$DB_HOST' in $DB_ENV is not an IPv4 address"
    printf '%s\n' "$DB_PORT" | grep -qE '^[0-9]{1,5}$' \
        || die "database port '$DB_PORT' in $DB_ENV is not a TCP port"
fi

# Apple's container kernel has no usable nftables backend, so the nft-flavoured
# wrappers fail in confusing ways. Pin xtables explicitly — a silent
# fall-through to a backend that cannot install these rules would fail *open*.
if command -v iptables-legacy >/dev/null 2>&1; then IPT=iptables-legacy; else IPT=iptables; fi
"$IPT" -L -n >/dev/null 2>&1 \
    || die "$IPT cannot talk to the kernel — is NET_ADMIN granted? (container run --cap-add NET_ADMIN)"

# -----------------------------------------------------------------------------
# IPv6
# -----------------------------------------------------------------------------
# The default kernel's IPv6 support is incomplete. Half-working IPv6 is worse
# than none: it opens egress paths the v4 ruleset below does not cover.
sysctl -qw net.ipv6.conf.all.disable_ipv6=1 2>/dev/null || log "could not disable IPv6 via sysctl (continuing)"
sysctl -qw net.ipv6.conf.default.disable_ipv6=1 2>/dev/null || true
if command -v ip6tables-legacy >/dev/null 2>&1; then IP6T=ip6tables-legacy; else IP6T=ip6tables; fi
if "$IP6T" -L -n >/dev/null 2>&1; then
    "$IP6T" -F; "$IP6T" -X
    for chain in INPUT OUTPUT FORWARD; do "$IP6T" -P "$chain" DROP; done
    log "IPv6 disabled and all ip6tables chains set to DROP"
fi

# -----------------------------------------------------------------------------
# Allow-set backend: ipset if the kernel has it, plain rules otherwise
# -----------------------------------------------------------------------------
# ipset matters at this size. The allow-set is a few hundred entries once
# GitHub's ranges are in, and ipset matches them in one hash lookup where plain
# rules walk the chain linearly on every packet. Apple's kernel is trimmed
# though, so probe rather than assume.
USE_IPSET=0
if command -v ipset >/dev/null 2>&1; then
    if ipset create "${SET_NAME}-probe" hash:net family inet 2>/dev/null; then
        ipset destroy "${SET_NAME}-probe" 2>/dev/null || true
        if "$IPT" -N "${CHAIN}-probe" 2>/dev/null; then
            ipset create "${SET_NAME}-probe" hash:net family inet 2>/dev/null || true
            if "$IPT" -A "${CHAIN}-probe" -m set --match-set "${SET_NAME}-probe" dst -j ACCEPT 2>/dev/null; then
                USE_IPSET=1
            fi
            "$IPT" -F "${CHAIN}-probe" 2>/dev/null || true
            "$IPT" -X "${CHAIN}-probe" 2>/dev/null || true
            ipset destroy "${SET_NAME}-probe" 2>/dev/null || true
        fi
    fi
fi

# -----------------------------------------------------------------------------
# Collect the addresses to permit
# -----------------------------------------------------------------------------
[ -r "$ALLOWLIST_RENDERED" ] || die "no rendered allowlist at $ALLOWLIST_RENDERED"

TARGETS=$(mktemp)
UNRESOLVED=$(mktemp)
trap 'rm -f "$TARGETS" "$UNRESOLVED"' EXIT

names=0; literals=0
while read -r entry; do
    [ -n "$entry" ] || continue
    case "$entry" in
        # An address or CIDR goes in verbatim.
        [0-9]*.[0-9]*.[0-9]*.[0-9]*)
            printf '%s\n' "$entry" >> "$TARGETS"; literals=$((literals + 1)); continue ;;
    esac
    names=$((names + 1))
    # `dig +short` interleaves CNAME targets with addresses; keep addresses only.
    if ! dig +short +time=3 +tries=2 A "$entry" 2>/dev/null \
         | grep -E '^[0-9]+(\.[0-9]+){3}$' >> "$TARGETS"; then
        printf '%s\n' "$entry" >> "$UNRESOLVED"
    fi
done < "$ALLOWLIST_RENDERED"

# GitHub: the published ranges, not a name lookup. api.github.com resolves to a
# handful of addresses while github.com, codeload and *.githubusercontent.com
# live across dozens of prefixes; meta is the only complete answer.
#
# This runs while the OUTPUT policy is still ACCEPT (see below), so there is no
# bootstrap problem — but a failure here must not be silent, because the result
# is a container that cannot reach GitHub at all.
if [ "$GITHUB_META" = "1" ]; then
    meta=$(curl -fsS --max-time 20 https://api.github.com/meta 2>/dev/null || true)
    if [ -n "$meta" ]; then
        gh=$(printf '%s' "$meta" \
             | grep -oE '"[0-9]+(\.[0-9]+){3}/[0-9]{1,2}"' \
             | tr -d '"' | sort -u)
        if [ -n "$gh" ]; then
            printf '%s\n' "$gh" >> "$TARGETS"
            log "GitHub ranges from api.github.com/meta: $(printf '%s\n' "$gh" | wc -l | tr -d ' ') prefixes"
        else
            log "WARNING: api.github.com/meta returned no IPv4 prefixes — GitHub may be unreachable"
        fi
    else
        log "WARNING: could not fetch api.github.com/meta — GitHub may be unreachable until 'devctl egress refresh'"
    fi
fi

sort -u "$TARGETS" -o "$TARGETS"
TOTAL=$(grep -c . "$TARGETS" || true)
[ "$TOTAL" -gt 0 ] || die "the allow-set is empty — refusing to install a policy that reaches nothing"

cp "$UNRESOLVED" "$STATE_DIR/unresolved" 2>/dev/null || true
FAILED=$(grep -c . "$UNRESOLVED" || true)
if [ "$FAILED" -gt 0 ]; then
    log "WARNING: $FAILED of $names allowlist name(s) resolved to nothing:"
    head -10 "$UNRESOLVED" | sed 's/^/[egress]            /'
    [ "$FAILED" -le 10 ] || log "            ... and $((FAILED - 10)) more ('egress-check --unresolved' lists them all)"
fi

# A handful of names failing is normal — a host is down, a CDN is slow. More than
# half failing is not an allowlist problem, it is DNS being broken, and the
# resulting policy would leave the container unable to reach the very services it
# exists to run. Failing here is far kinder than a `claude` that hangs.
if [ "$names" -gt 0 ] && [ "$((FAILED * 2))" -gt "$names" ] \
   && [ "${SECMAN_EGRESS_ALLOW_PARTIAL_DNS:-0}" != "1" ]; then
    die "$FAILED of $names names failed to resolve — DNS looks broken, refusing to install a policy that reaches almost nothing. Set SECMAN_EGRESS_ALLOW_PARTIAL_DNS=1 to override."
fi

# -----------------------------------------------------------------------------
# Install
# -----------------------------------------------------------------------------
"$IPT" -F
"$IPT" -X 2>/dev/null || true
"$IPT" -t nat -F 2>/dev/null || true
"$IPT" -t nat -X 2>/dev/null || true

# Policies stay open only while the rules are being installed, so a slow lookup
# above cannot deadlock this script against its own firewall. They are closed at
# the end, before this script returns and before anything else can run.
"$IPT" -P INPUT ACCEPT
"$IPT" -P OUTPUT ACCEPT
"$IPT" -P FORWARD DROP

# The allow-set itself.
if [ "$USE_IPSET" -eq 1 ]; then
    ipset create "$SET_NAME"     hash:net family inet -exist
    ipset create "${SET_NAME}-new" hash:net family inet -exist
    ipset flush  "${SET_NAME}-new"
    while read -r addr; do
        [ -n "$addr" ] || continue
        ipset add "${SET_NAME}-new" "$addr" -exist 2>/dev/null || true
    done < "$TARGETS"
    # Swap rather than flush-and-refill: the change is atomic, so a refresh never
    # leaves a window in which permitted traffic is denied.
    ipset swap "${SET_NAME}-new" "$SET_NAME"
    ipset destroy "${SET_NAME}-new" 2>/dev/null || true
    MODE=ipset
else
    "$IPT" -N "$CHAIN"
    while read -r addr; do
        [ -n "$addr" ] || continue
        "$IPT" -A "$CHAIN" -d "$addr" -j ACCEPT
    done < "$TARGETS"
    MODE=rules
fi

# --- Plumbing -----------------------------------------------------------------
"$IPT" -A INPUT  -i lo -j ACCEPT
"$IPT" -A OUTPUT -o lo -j ACCEPT
"$IPT" -A INPUT  -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT
"$IPT" -A OUTPUT -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT

# DHCP: the VM's lease is renewed while the container runs, and losing it takes
# the published ports down with it.
"$IPT" -A OUTPUT -p udp --sport 68 --dport 67 -j ACCEPT
"$IPT" -A INPUT  -p udp --sport 67 --dport 68 -j ACCEPT

# DNS, to the container's own resolvers only. Resolution on its own reaches
# nothing: the address it returns still has to be in the allow-set.
NAMESERVERS=$(awk '/^nameserver/ {print $2}' /etc/resolv.conf 2>/dev/null | grep -E '^[0-9]+(\.[0-9]+){3}$' || true)
if [ -z "$NAMESERVERS" ]; then
    log "WARNING: no IPv4 nameserver in /etc/resolv.conf — DNS will be blocked"
else
    for ns in $NAMESERVERS; do
        "$IPT" -A OUTPUT -p udp -d "$ns" --dport 53 -j ACCEPT
        "$IPT" -A OUTPUT -p tcp -d "$ns" --dport 53 -j ACCEPT
    done
    log "DNS permitted to: $(printf '%s ' $NAMESERVERS)"
fi

# Inbound: the published dev ports, so the Mac can reach the stack.
"$IPT" -A INPUT -p tcp -m multiport --dports "$INBOUND_PORTS" -j ACCEPT
log "inbound TCP permitted on $INBOUND_PORTS"

# The database on the Mac, when the container was started with `--db host`.
# One address, one port, and nothing else: this is deliberately not folded into
# the allow-set, so widening the allowlist can never widen database reach and
# vice versa.
if [ "$DB_MODE" = "host" ]; then
    "$IPT" -A OUTPUT -p tcp -d "$DB_HOST" --dport "$DB_PORT" -j ACCEPT
    log "host database permitted: $DB_HOST:$DB_PORT (--db host)"
fi

# --- The allow-set, applied ---------------------------------------------------
if [ "$USE_IPSET" -eq 1 ]; then
    "$IPT" -A OUTPUT -p tcp -m multiport --dports "$EGRESS_PORTS" \
           -m set --match-set "$SET_NAME" dst -j ACCEPT
else
    "$IPT" -A OUTPUT -p tcp -m multiport --dports "$EGRESS_PORTS" -j "$CHAIN"
fi

# --- Close the door -----------------------------------------------------------
# Log before rejecting, rate-limited: without a proxy access log this is the only
# record of what the agents and the build tried to reach (§A09), and it is what
# turns "the build failed" into "the build wanted files.pythonhosted.org".
# `devctl egress log` reads it back.
"$IPT" -A OUTPUT -m limit --limit 12/min --limit-burst 30 \
       -j LOG --log-prefix "secman-egress-deny: " --log-level 6 2>/dev/null || true
# REJECT, not DROP: a blocked tool gets an immediate "connection refused" rather
# than hanging for a two-minute timeout, which is the difference between an
# obvious allowlist gap and a mystery.
"$IPT" -A OUTPUT -j REJECT --reject-with icmp-port-unreachable
"$IPT" -A INPUT  -j DROP
"$IPT" -P OUTPUT DROP
"$IPT" -P INPUT  DROP

printf '%s\n' "$MODE" > "$STATE_DIR/mode"
printf '%s\n' "$TOTAL" > "$STATE_DIR/count"
log "firewall active — $TOTAL addresses/prefixes from $names names and $literals literals (backend=$MODE, ports=$EGRESS_PORTS)"
if [ "$DB_MODE" = "host" ]; then
    log "                 plus one database rule: $DB_HOST:$DB_PORT"
fi
