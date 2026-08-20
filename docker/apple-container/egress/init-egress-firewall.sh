#!/usr/bin/env bash
# Egress firewall for the Secman shielded dev container.
# =============================================================================
# Runs as root inside the container VM, before any agent or build process starts.
#
# Model:
#   OUTPUT policy DROP. The only ways out are
#     1. loopback,
#     2. DNS to the resolvers in /etc/resolv.conf,
#     3. traffic owned by squid's uid (which enforces the *domain* allowlist).
#   INPUT policy DROP, opened only for the published dev ports and for replies to
#   connections the container itself established.
#
# Layer 2 exists because layer 1 (squid) is only as good as the environment:
# a process that ignores $HTTPS_PROXY, or a Java library that opens a raw socket,
# would otherwise walk straight past it. With OUTPUT DROP it cannot.
#
# Fallback: if the running kernel has no xt_owner match (Apple's default kernel is
# trimmed and this is exactly the kind of module it omits), the uid rule is not
# available. Rather than fail open, the script then resolves every allowlisted
# domain and permits only those addresses on 80/443 — weaker, because addresses
# are shared and change, but still a closed default. Which mode is active is
# printed at start and reported by `egress-check`.
# =============================================================================
set -euo pipefail

ALLOWLIST_RENDERED=${ALLOWLIST_RENDERED:-/run/secman-dev/egress/allowlist.rendered}
PROXY_USER=${PROXY_USER:-proxy}
PROXY_PORT=${PROXY_PORT:-3128}
# Ports the host publishes into this container (see secman-container.sh).
INBOUND_PORTS=${INBOUND_PORTS:-443,3306,4321,8080}

log() { printf '[egress] %s\n' "$*"; }
die() { printf '[egress] FATAL: %s\n' "$*" >&2; exit 1; }

# --- Preconditions ------------------------------------------------------------
[ "$(id -u)" -eq 0 ] || die "must run as root"

# Apple's container kernel does not carry a usable nftables backend, so the nft
# variants of these binaries fail in confusing ways. Pin xtables explicitly and
# verify it actually works before relying on it for anything.
if command -v iptables-legacy >/dev/null 2>&1; then
    IPT=iptables-legacy
else
    IPT=iptables
fi
"$IPT" -L -n >/dev/null 2>&1 || die "$IPT cannot talk to the kernel — is NET_ADMIN granted? (container run --cap-add NET_ADMIN)"

# --- IPv6 ---------------------------------------------------------------------
# The default kernel's IPv6 support is incomplete (see docs/APPLE_CONTAINER_DEV.md).
# Half-working IPv6 is worse than none here: it produces egress paths the v4
# ruleset below does not cover. Disable it, and belt-and-braces the tables too.
sysctl -qw net.ipv6.conf.all.disable_ipv6=1 2>/dev/null || log "could not disable IPv6 via sysctl (continuing)"
sysctl -qw net.ipv6.conf.default.disable_ipv6=1 2>/dev/null || true
if command -v ip6tables-legacy >/dev/null 2>&1; then IP6T=ip6tables-legacy; else IP6T=ip6tables; fi
if "$IP6T" -L -n >/dev/null 2>&1; then
    "$IP6T" -F; "$IP6T" -X
    for chain in INPUT OUTPUT FORWARD; do "$IP6T" -P "$chain" DROP; done
    log "IPv6 disabled and all ip6tables chains set to DROP"
fi

# --- Reset --------------------------------------------------------------------
"$IPT" -F
"$IPT" -X
"$IPT" -t nat -F 2>/dev/null || true
"$IPT" -t nat -X 2>/dev/null || true

# Open the policies while the rules are being installed, so a slow DNS lookup in
# the fallback path below cannot deadlock this script against its own firewall.
"$IPT" -P INPUT ACCEPT
"$IPT" -P OUTPUT ACCEPT
"$IPT" -P FORWARD DROP

# --- Always-allowed plumbing --------------------------------------------------
"$IPT" -A INPUT  -i lo -j ACCEPT
"$IPT" -A OUTPUT -o lo -j ACCEPT
"$IPT" -A INPUT  -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT
"$IPT" -A OUTPUT -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT

# DHCP: the VM's lease is renewed while the container runs; losing it would take
# the published ports down with it.
"$IPT" -A OUTPUT -p udp --sport 68 --dport 67 -j ACCEPT
"$IPT" -A INPUT  -p udp --sport 67 --dport 68 -j ACCEPT

# DNS, to the container's own resolvers only. Names still have to clear the
# allowlist before a connection is permitted; resolution alone reaches nothing.
NAMESERVERS=$(awk '/^nameserver/ {print $2}' /etc/resolv.conf 2>/dev/null | grep -E '^[0-9]+(\.[0-9]+){3}$' || true)
if [ -z "$NAMESERVERS" ]; then
    log "WARNING: no IPv4 nameserver in /etc/resolv.conf — DNS will be blocked"
else
    for ns in $NAMESERVERS; do
        "$IPT" -A OUTPUT -p udp -d "$ns" --dport 53 -j ACCEPT
        "$IPT" -A OUTPUT -p tcp -d "$ns" --dport 53 -j ACCEPT
        log "DNS permitted to $ns"
    done
fi

# Inbound: the published dev ports, so the Mac can reach the stack.
"$IPT" -A INPUT -p tcp -m multiport --dports "$INBOUND_PORTS" -j ACCEPT
log "inbound TCP permitted on $INBOUND_PORTS"

# --- Egress: preferred path (owner match) ------------------------------------
# Probe rather than assume: `-m owner` needs xt_owner, which a trimmed kernel may
# not have. Probing in a throwaway chain keeps a failure from leaving debris.
OWNER_MATCH=0
if "$IPT" -N secman-owner-probe 2>/dev/null; then
    if "$IPT" -A secman-owner-probe -m owner --uid-owner "$PROXY_USER" -j ACCEPT 2>/dev/null; then
        OWNER_MATCH=1
    fi
    "$IPT" -F secman-owner-probe 2>/dev/null || true
    "$IPT" -X secman-owner-probe 2>/dev/null || true
fi

MODE=
if [ "$OWNER_MATCH" -eq 1 ]; then
    MODE=owner
    # Only squid gets to leave the container. Everything else must go through it
    # on 127.0.0.1:3128, which the loopback rule above already allows.
    "$IPT" -A OUTPUT -p tcp -m owner --uid-owner "$PROXY_USER" -m multiport --dports 80,443 -j ACCEPT
    log "egress mode: owner — only uid '$PROXY_USER' (squid) may open outbound connections"
else
    MODE=address
    log "egress mode: address — this kernel has no xt_owner match; falling back to"
    log "                       an IP allowlist resolved from the domain allowlist."
    [ -r "$ALLOWLIST_RENDERED" ] || die "no rendered allowlist at $ALLOWLIST_RENDERED"
    addresses=$(mktemp)
    skipped=0
    while read -r domain; do
        [ -n "$domain" ] || continue
        # A leading dot is squid's "and all subdomains" marker; strip it for DNS.
        # Only the apex resolves — a wildcard cannot be enumerated, which is one
        # more reason this mode is the fallback and not the design.
        lookup=${domain#.}
        # `dig +short` interleaves CNAME targets with addresses; keep addresses only.
        before=$(wc -l < "$addresses")
        dig +short +time=3 +tries=2 A "$lookup" 2>/dev/null \
            | grep -E '^[0-9]+(\.[0-9]+){3}$' >> "$addresses" || true
        [ "$(wc -l < "$addresses")" -gt "$before" ] || skipped=$((skipped + 1))
    done < "$ALLOWLIST_RENDERED"
    # Sort unique before installing: allowlist entries share addresses constantly
    # (every *.githubusercontent.com name, every CDN-fronted host), and one rule
    # per duplicate would multiply the chain length for no added reach.
    resolved=0
    while read -r ip; do
        [ -n "$ip" ] || continue
        "$IPT" -A OUTPUT -p tcp -d "$ip" -m multiport --dports 80,443 -j ACCEPT
        resolved=$((resolved + 1))
    done < <(sort -u "$addresses")
    rm -f "$addresses"
    log "resolved $resolved distinct addresses ($skipped names produced none — rerun 'devctl egress refresh' if a build fails)"
fi

# --- Close the door -----------------------------------------------------------
# REJECT rather than DROP on the last hop out: a blocked build or agent then gets
# an immediate "connection refused" instead of hanging for a two-minute timeout,
# which is the difference between an obvious allowlist gap and a mystery.
"$IPT" -A OUTPUT -j REJECT --reject-with icmp-port-unreachable
"$IPT" -A INPUT  -j DROP
"$IPT" -P OUTPUT DROP
"$IPT" -P INPUT  DROP

printf '%s\n' "$MODE" > /run/secman-dev/egress/mode
log "firewall active (mode=$MODE)"
