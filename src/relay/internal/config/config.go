// Package config loads and validates the relay's runtime configuration from
// the environment.
//
// Two rules drive everything here:
//
//   - Fail closed. A missing or weak security-relevant value aborts the boot
//     rather than falling back to a permissive default. This mirrors secman's
//     JwtSigningValidator / DatabaseCredentialValidator behaviour: a relay that
//     starts with a placeholder ingest token is worse than a relay that does not
//     start.
//   - Nothing secret is ever rendered. Config carries the ingest token, the
//     ingest HMAC key and the token signing key; String() and Redacted() exist so
//     that a startup log or a panic dump cannot leak them.
package config

import (
	"errors"
	"fmt"
	"net"
	"net/url"
	"os"
	"strconv"
	"strings"
	"time"
)

// TLSMode selects how the public listener terminates TLS.
type TLSMode string

const (
	// TLSModeACME obtains and renews a certificate from an ACME CA
	// (Let's Encrypt by default) using the HTTP-01 challenge.
	TLSModeACME TLSMode = "acme"
	// TLSModeFile serves a certificate/key pair from disk, reloaded on change.
	// Use this with an external ACME client, a corporate PKI, or a cert
	// delivered by the platform.
	TLSModeFile TLSMode = "file"
	// TLSModeOff serves plaintext HTTP. Only correct when something else
	// terminates TLS immediately in front of the relay (an AWS ALB, an ingress
	// controller, a local reverse proxy) and the hop between them cannot be
	// observed. Requires RELAY_PLAINTEXT_ACK=true.
	TLSModeOff TLSMode = "off"
)

// MinSecretLength is the minimum accepted length for every shared secret.
// 32 characters of the kind `openssl rand -base64 32` produces is ~192 bits of
// entropy, which is the floor for an HMAC key that is reachable from the
// internet-facing side of the deployment.
const MinSecretLength = 32

// placeholderSecrets are values that appear in example files and tutorials.
// Rejecting them by name turns "we forgot to set it in prod" from a silent
// compromise into a failed boot.
var placeholderSecrets = map[string]struct{}{
	"changeme":                          {},
	"change-me":                         {},
	"secret":                            {},
	"password":                          {},
	"replace-me":                        {},
	"replace_this_with_a_real_secret_1": {},
	"00000000000000000000000000000000":  {},
}

// Config is the fully validated relay configuration.
type Config struct {
	// Public (mobile) plane.
	ListenAddr string

	// Ingest plane. When IngestListenAddr is non-empty the ingest routes are
	// served by a *second* listener, which lets an operator bind them to a
	// private interface (e.g. "10.0.1.7:9443") that the internet cannot reach
	// while the mobile plane stays public. When empty the ingest routes are
	// mounted on the public listener under /ingest and rely on token + HMAC +
	// optional CIDR allowlist alone.
	IngestListenAddr string

	TLS    TLSConfig
	Ingest IngestConfig
	Device DeviceConfig
	Limits LimitsConfig

	// StateDir holds the device registry. Created with 0700 if absent.
	StateDir string

	// TrustedProxyCIDRs are the only peers whose X-Forwarded-For header is
	// believed. Empty means "trust nobody", i.e. always use the TCP peer
	// address. Getting this wrong turns every rate limit and audit log into
	// fiction, so it is opt-in.
	TrustedProxyCIDRs []*net.IPNet

	LogLevel string
}

// TLSConfig describes how the public listener is protected.
type TLSConfig struct {
	Mode TLSMode

	// File mode.
	CertFile string
	KeyFile  string

	// ACME mode.
	ACMEDirectoryURL string
	ACMEDomains      []string
	ACMEEmail        string
	ACMECacheDir     string
	ACMEHTTP01Addr   string
	ACMEAcceptTOS    bool

	// PlaintextAck records the explicit operator acknowledgement required by
	// TLSModeOff.
	PlaintextAck bool
}

// IngestConfig covers the secman -> relay direction.
type IngestConfig struct {
	// Token is the bearer credential secman presents. Compared in constant time.
	Token string
	// HMACKey signs (timestamp, nonce, body-digest). A stolen bearer token
	// alone cannot forge a snapshot without it.
	HMACKey []byte
	// MaxClockSkew bounds how far a request timestamp may be from now. Also the
	// lifetime of the replay-nonce cache.
	MaxClockSkew time.Duration
	// AllowedCIDRs optionally restricts which peers may reach the ingest plane
	// at all. Empty means no network restriction — authentication still applies.
	AllowedCIDRs []*net.IPNet
}

// DeviceConfig covers the mobile-app side.
type DeviceConfig struct {
	// TokenSigningKey signs the short-lived access tokens the relay issues to
	// enrolled devices. Must differ from IngestConfig.HMACKey (key separation).
	TokenSigningKey []byte
	// TokenTTL is how long an issued access token stays valid.
	TokenTTL time.Duration
	// ChallengeTTL bounds the device authentication challenge window.
	ChallengeTTL time.Duration
	// EnrollmentTTL is the default lifetime of an enrollment code when secman
	// does not pin one.
	EnrollmentTTL time.Duration
	// MaxDevices caps registry growth. Enrollment fails closed once reached.
	MaxDevices int
}

// LimitsConfig holds the request-shaping limits.
type LimitsConfig struct {
	MaxBodyBytes    int64
	RateLimitRPS    float64
	RateLimitBurst  int
	SnapshotMaxAge  time.Duration
	ReadTimeout     time.Duration
	WriteTimeout    time.Duration
	IdleTimeout     time.Duration
	ShutdownTimeout time.Duration
}

// Getenv matches os.Getenv and exists so tests can supply an environment
// without touching the process.
type Getenv func(string) string

// OSGetenv reads the real process environment.
func OSGetenv(key string) string { return os.Getenv(key) }

// Load reads, defaults and validates the configuration. The returned error is
// safe to print: it never contains a secret value, only the name of the
// variable at fault.
func Load(getenv Getenv) (*Config, error) {
	e := &envReader{getenv: getenv}

	cfg := &Config{
		ListenAddr:       e.str("RELAY_LISTEN_ADDR", ":8443"),
		IngestListenAddr: e.str("RELAY_INGEST_LISTEN_ADDR", ""),
		StateDir:         e.str("RELAY_STATE_DIR", "/var/lib/secman-relay"),
		LogLevel:         strings.ToLower(e.str("RELAY_LOG_LEVEL", "info")),
	}

	cfg.TLS = TLSConfig{
		Mode:             TLSMode(strings.ToLower(e.str("RELAY_TLS_MODE", string(TLSModeACME)))),
		CertFile:         e.str("RELAY_TLS_CERT_FILE", ""),
		KeyFile:          e.str("RELAY_TLS_KEY_FILE", ""),
		ACMEDirectoryURL: e.str("RELAY_ACME_DIRECTORY_URL", "https://acme-v02.api.letsencrypt.org/directory"),
		ACMEDomains:      splitList(e.str("RELAY_ACME_DOMAINS", "")),
		ACMEEmail:        e.str("RELAY_ACME_EMAIL", ""),
		ACMECacheDir:     e.str("RELAY_ACME_CACHE_DIR", ""),
		ACMEHTTP01Addr:   e.str("RELAY_ACME_HTTP01_ADDR", ":80"),
		ACMEAcceptTOS:    e.boolean("RELAY_ACME_ACCEPT_TOS", false),
		PlaintextAck:     e.boolean("RELAY_PLAINTEXT_ACK", false),
	}

	cfg.Ingest = IngestConfig{
		Token:        e.str("RELAY_INGEST_TOKEN", ""),
		HMACKey:      []byte(e.str("RELAY_INGEST_HMAC_KEY", "")),
		MaxClockSkew: e.duration("RELAY_INGEST_MAX_CLOCK_SKEW", 5*time.Minute),
		AllowedCIDRs: e.cidrs("RELAY_INGEST_ALLOWED_CIDRS"),
	}

	cfg.Device = DeviceConfig{
		TokenSigningKey: []byte(e.str("RELAY_TOKEN_SIGNING_KEY", "")),
		TokenTTL:        e.duration("RELAY_TOKEN_TTL", 15*time.Minute),
		ChallengeTTL:    e.duration("RELAY_CHALLENGE_TTL", 2*time.Minute),
		EnrollmentTTL:   e.duration("RELAY_ENROLLMENT_TTL", 15*time.Minute),
		MaxDevices:      e.integer("RELAY_MAX_DEVICES", 500),
	}

	cfg.Limits = LimitsConfig{
		MaxBodyBytes:    int64(e.integer("RELAY_MAX_BODY_BYTES", 4<<20)),
		RateLimitRPS:    e.float("RELAY_RATE_LIMIT_RPS", 5),
		RateLimitBurst:  e.integer("RELAY_RATE_LIMIT_BURST", 20),
		SnapshotMaxAge:  e.duration("RELAY_SNAPSHOT_MAX_AGE", 15*time.Minute),
		ReadTimeout:     e.duration("RELAY_READ_TIMEOUT", 15*time.Second),
		WriteTimeout:    e.duration("RELAY_WRITE_TIMEOUT", 30*time.Second),
		IdleTimeout:     e.duration("RELAY_IDLE_TIMEOUT", 90*time.Second),
		ShutdownTimeout: e.duration("RELAY_SHUTDOWN_TIMEOUT", 20*time.Second),
	}

	cfg.TrustedProxyCIDRs = e.cidrs("RELAY_TRUSTED_PROXY_CIDRS")

	if err := e.err(); err != nil {
		return nil, err
	}
	if err := cfg.validate(); err != nil {
		return nil, err
	}
	return cfg, nil
}

func (c *Config) validate() error {
	var errs []error

	if err := validateListenAddr("RELAY_LISTEN_ADDR", c.ListenAddr); err != nil {
		errs = append(errs, err)
	}
	if c.IngestListenAddr != "" {
		if err := validateListenAddr("RELAY_INGEST_LISTEN_ADDR", c.IngestListenAddr); err != nil {
			errs = append(errs, err)
		} else if c.IngestListenAddr == c.ListenAddr {
			errs = append(errs, errors.New("RELAY_INGEST_LISTEN_ADDR must differ from RELAY_LISTEN_ADDR"))
		}
	}

	errs = append(errs, c.validateTLS()...)

	if err := validateSecret("RELAY_INGEST_TOKEN", c.Ingest.Token); err != nil {
		errs = append(errs, err)
	}
	if err := validateSecret("RELAY_INGEST_HMAC_KEY", string(c.Ingest.HMACKey)); err != nil {
		errs = append(errs, err)
	}
	if err := validateSecret("RELAY_TOKEN_SIGNING_KEY", string(c.Device.TokenSigningKey)); err != nil {
		errs = append(errs, err)
	}
	// Key separation: reusing one secret across two protocols means a flaw in
	// either one compromises both.
	if len(c.Ingest.HMACKey) > 0 {
		if string(c.Ingest.HMACKey) == c.Ingest.Token {
			errs = append(errs, errors.New("RELAY_INGEST_HMAC_KEY must differ from RELAY_INGEST_TOKEN"))
		}
		if string(c.Ingest.HMACKey) == string(c.Device.TokenSigningKey) {
			errs = append(errs, errors.New("RELAY_TOKEN_SIGNING_KEY must differ from RELAY_INGEST_HMAC_KEY"))
		}
	}

	if c.Ingest.MaxClockSkew <= 0 || c.Ingest.MaxClockSkew > 15*time.Minute {
		errs = append(errs, errors.New("RELAY_INGEST_MAX_CLOCK_SKEW must be > 0 and <= 15m"))
	}
	if c.Device.TokenTTL <= 0 || c.Device.TokenTTL > 24*time.Hour {
		errs = append(errs, errors.New("RELAY_TOKEN_TTL must be > 0 and <= 24h"))
	}
	if c.Device.ChallengeTTL <= 0 || c.Device.ChallengeTTL > 15*time.Minute {
		errs = append(errs, errors.New("RELAY_CHALLENGE_TTL must be > 0 and <= 15m"))
	}
	if c.Device.EnrollmentTTL <= 0 || c.Device.EnrollmentTTL > 24*time.Hour {
		errs = append(errs, errors.New("RELAY_ENROLLMENT_TTL must be > 0 and <= 24h"))
	}
	if c.Device.MaxDevices <= 0 {
		errs = append(errs, errors.New("RELAY_MAX_DEVICES must be > 0"))
	}
	if c.Limits.MaxBodyBytes < 1024 {
		errs = append(errs, errors.New("RELAY_MAX_BODY_BYTES must be >= 1024"))
	}
	if c.Limits.RateLimitRPS <= 0 {
		errs = append(errs, errors.New("RELAY_RATE_LIMIT_RPS must be > 0"))
	}
	if c.Limits.RateLimitBurst <= 0 {
		errs = append(errs, errors.New("RELAY_RATE_LIMIT_BURST must be > 0"))
	}
	if c.Limits.SnapshotMaxAge <= 0 {
		errs = append(errs, errors.New("RELAY_SNAPSHOT_MAX_AGE must be > 0"))
	}
	if c.StateDir == "" {
		errs = append(errs, errors.New("RELAY_STATE_DIR must not be empty"))
	}
	switch c.LogLevel {
	case "debug", "info", "warn", "error":
	default:
		errs = append(errs, errors.New("RELAY_LOG_LEVEL must be one of debug, info, warn, error"))
	}

	return errors.Join(errs...)
}

func (c *Config) validateTLS() []error {
	var errs []error
	switch c.TLS.Mode {
	case TLSModeACME:
		if len(c.TLS.ACMEDomains) == 0 {
			errs = append(errs, errors.New("RELAY_ACME_DOMAINS must list at least one domain when RELAY_TLS_MODE=acme"))
		}
		for _, d := range c.TLS.ACMEDomains {
			if err := validateDomain(d); err != nil {
				errs = append(errs, err)
			}
		}
		if c.TLS.ACMEEmail == "" {
			errs = append(errs, errors.New("RELAY_ACME_EMAIL is required when RELAY_TLS_MODE=acme"))
		} else if !strings.Contains(c.TLS.ACMEEmail, "@") || strings.ContainsAny(c.TLS.ACMEEmail, " \r\n,<>") {
			errs = append(errs, errors.New("RELAY_ACME_EMAIL is not a plausible single email address"))
		}
		if !c.TLS.ACMEAcceptTOS {
			errs = append(errs, errors.New("RELAY_ACME_ACCEPT_TOS must be true to request certificates from the ACME CA"))
		}
		if c.TLS.ACMECacheDir == "" {
			// Default under the state dir rather than failing: the account key
			// and certificate must persist, but the operator rarely cares where.
			c.TLS.ACMECacheDir = c.StateDir + "/acme"
		}
		if err := validateACMEDirectoryURL(c.TLS.ACMEDirectoryURL); err != nil {
			errs = append(errs, err)
		}
		if err := validateListenAddr("RELAY_ACME_HTTP01_ADDR", c.TLS.ACMEHTTP01Addr); err != nil {
			errs = append(errs, err)
		}
	case TLSModeFile:
		if c.TLS.CertFile == "" || c.TLS.KeyFile == "" {
			errs = append(errs, errors.New("RELAY_TLS_CERT_FILE and RELAY_TLS_KEY_FILE are required when RELAY_TLS_MODE=file"))
		}
	case TLSModeOff:
		if !c.TLS.PlaintextAck {
			errs = append(errs, errors.New(
				"RELAY_TLS_MODE=off serves plaintext HTTP; set RELAY_PLAINTEXT_ACK=true to confirm TLS is terminated in front of the relay"))
		}
	default:
		errs = append(errs, fmt.Errorf("RELAY_TLS_MODE must be one of acme, file, off (got %q)", sanitizeForError(string(c.TLS.Mode))))
	}
	return errs
}

// PlaintextIngest reports whether ingest traffic arrives unencrypted, which is
// only acceptable behind a TLS terminator.
func (c *Config) PlaintextIngest() bool { return c.TLS.Mode == TLSModeOff }

// Redacted returns a map suitable for a startup log line: every secret is
// replaced by a fixed marker, never by a prefix or a length.
func (c *Config) Redacted() map[string]any {
	return map[string]any{
		"listenAddr":         c.ListenAddr,
		"ingestListenAddr":   orNone(c.IngestListenAddr),
		"tlsMode":            string(c.TLS.Mode),
		"acmeDomains":        c.TLS.ACMEDomains,
		"acmeDirectoryURL":   c.TLS.ACMEDirectoryURL,
		"stateDir":           c.StateDir,
		"trustedProxyCIDRs":  cidrsToStrings(c.TrustedProxyCIDRs),
		"ingestAllowedCIDRs": cidrsToStrings(c.Ingest.AllowedCIDRs),
		"tokenTTL":           c.Device.TokenTTL.String(),
		"snapshotMaxAge":     c.Limits.SnapshotMaxAge.String(),
		"maxBodyBytes":       c.Limits.MaxBodyBytes,
		"rateLimitRPS":       c.Limits.RateLimitRPS,
		"maxDevices":         c.Device.MaxDevices,
		"logLevel":           c.LogLevel,
		"ingestToken":        "***",
		"ingestHmacKey":      "***",
		"tokenSigningKey":    "***",
	}
}

// String deliberately does not render secrets. Without this, a %v of Config in
// any log line would print three credentials.
func (c *Config) String() string {
	return fmt.Sprintf("Config{listen=%s tls=%s ingestListen=%s secrets=***}",
		c.ListenAddr, c.TLS.Mode, orNone(c.IngestListenAddr))
}

// --- helpers ---------------------------------------------------------------

type envReader struct {
	getenv Getenv
	errs   []error
}

func (e *envReader) err() error { return errors.Join(e.errs...) }

func (e *envReader) str(key, def string) string {
	v := strings.TrimSpace(e.getenv(key))
	if v == "" {
		return def
	}
	return v
}

func (e *envReader) boolean(key string, def bool) bool {
	raw := strings.TrimSpace(e.getenv(key))
	if raw == "" {
		return def
	}
	v, err := strconv.ParseBool(raw)
	if err != nil {
		e.errs = append(e.errs, fmt.Errorf("%s must be a boolean (true/false)", key))
		return def
	}
	return v
}

func (e *envReader) integer(key string, def int) int {
	raw := strings.TrimSpace(e.getenv(key))
	if raw == "" {
		return def
	}
	v, err := strconv.Atoi(raw)
	if err != nil {
		e.errs = append(e.errs, fmt.Errorf("%s must be an integer", key))
		return def
	}
	return v
}

func (e *envReader) float(key string, def float64) float64 {
	raw := strings.TrimSpace(e.getenv(key))
	if raw == "" {
		return def
	}
	v, err := strconv.ParseFloat(raw, 64)
	if err != nil {
		e.errs = append(e.errs, fmt.Errorf("%s must be a number", key))
		return def
	}
	return v
}

func (e *envReader) duration(key string, def time.Duration) time.Duration {
	raw := strings.TrimSpace(e.getenv(key))
	if raw == "" {
		return def
	}
	v, err := time.ParseDuration(raw)
	if err != nil {
		e.errs = append(e.errs, fmt.Errorf("%s must be a Go duration such as 30s, 5m, 12h", key))
		return def
	}
	return v
}

func (e *envReader) cidrs(key string) []*net.IPNet {
	raw := strings.TrimSpace(e.getenv(key))
	if raw == "" {
		return nil
	}
	var out []*net.IPNet
	for _, item := range splitList(raw) {
		// Accept both "10.0.0.0/8" and a bare address, which we treat as /32/128.
		if !strings.Contains(item, "/") {
			ip := net.ParseIP(item)
			if ip == nil {
				e.errs = append(e.errs, fmt.Errorf("%s contains an entry that is neither a CIDR nor an IP address", key))
				continue
			}
			bits := 32
			if ip.To4() == nil {
				bits = 128
			}
			item = fmt.Sprintf("%s/%d", ip.String(), bits)
		}
		_, network, err := net.ParseCIDR(item)
		if err != nil {
			e.errs = append(e.errs, fmt.Errorf("%s contains an invalid CIDR", key))
			continue
		}
		out = append(out, network)
	}
	return out
}

func splitList(raw string) []string {
	if strings.TrimSpace(raw) == "" {
		return nil
	}
	parts := strings.Split(raw, ",")
	out := make([]string, 0, len(parts))
	for _, p := range parts {
		p = strings.TrimSpace(p)
		if p != "" {
			out = append(out, p)
		}
	}
	return out
}

func validateListenAddr(key, addr string) error {
	host, portStr, err := net.SplitHostPort(addr)
	if err != nil {
		return fmt.Errorf("%s must be host:port (e.g. :8443 or 127.0.0.1:8443)", key)
	}
	port, err := strconv.Atoi(portStr)
	if err != nil || port < 1 || port > 65535 {
		return fmt.Errorf("%s must carry a port between 1 and 65535", key)
	}
	if host != "" && net.ParseIP(host) == nil {
		return fmt.Errorf("%s host part must be empty or an IP address", key)
	}
	return nil
}

func validateSecret(key, value string) error {
	if value == "" {
		return fmt.Errorf("%s is required", key)
	}
	if len(value) < MinSecretLength {
		return fmt.Errorf("%s must be at least %d characters (try: openssl rand -base64 32)", key, MinSecretLength)
	}
	if _, bad := placeholderSecrets[strings.ToLower(value)]; bad {
		return fmt.Errorf("%s is set to a well-known placeholder value", key)
	}
	return nil
}

// validateDomain keeps a hostile RELAY_ACME_DOMAINS value out of the ACME
// order and out of the TLS SNI matching table. Wildcards are rejected because
// the HTTP-01 challenge cannot satisfy them.
func validateDomain(d string) error {
	if d == "" || len(d) > 253 {
		return fmt.Errorf("RELAY_ACME_DOMAINS entry has an invalid length")
	}
	if strings.HasPrefix(d, "*") {
		return fmt.Errorf("RELAY_ACME_DOMAINS must not contain wildcards: the HTTP-01 challenge cannot satisfy them")
	}
	for _, label := range strings.Split(d, ".") {
		if label == "" || len(label) > 63 {
			return fmt.Errorf("RELAY_ACME_DOMAINS entry %q has an empty or over-long label", sanitizeForError(d))
		}
		for _, r := range label {
			isAlnum := (r >= 'a' && r <= 'z') || (r >= 'A' && r <= 'Z') || (r >= '0' && r <= '9')
			if !isAlnum && r != '-' {
				return fmt.Errorf("RELAY_ACME_DOMAINS entry %q contains an invalid character", sanitizeForError(d))
			}
		}
		if strings.HasPrefix(label, "-") || strings.HasSuffix(label, "-") {
			return fmt.Errorf("RELAY_ACME_DOMAINS entry %q has a label starting or ending with '-'", sanitizeForError(d))
		}
	}
	if !strings.Contains(d, ".") {
		return fmt.Errorf("RELAY_ACME_DOMAINS entry %q is not a fully qualified domain name", sanitizeForError(d))
	}
	return nil
}

// validateACMEDirectoryURL is an SSRF guard: the directory URL is operator
// input that the relay fetches, and every later ACME URL is taken from that
// response. Restricting it to https and rejecting loopback / link-local /
// private targets keeps a mistyped or injected value from turning the relay
// into a request proxy for its own network.
func validateACMEDirectoryURL(raw string) error {
	u, err := url.Parse(raw)
	if err != nil {
		return errors.New("RELAY_ACME_DIRECTORY_URL is not a valid URL")
	}
	if !strings.EqualFold(u.Scheme, "https") {
		return errors.New("RELAY_ACME_DIRECTORY_URL must use https")
	}
	if u.User != nil {
		return errors.New("RELAY_ACME_DIRECTORY_URL must not contain credentials")
	}
	host := u.Hostname()
	if host == "" {
		return errors.New("RELAY_ACME_DIRECTORY_URL must contain a host")
	}
	if ip := net.ParseIP(host); ip != nil && IsBlockedIP(ip) {
		return errors.New("RELAY_ACME_DIRECTORY_URL must not point at a loopback, link-local, multicast or private address")
	}
	return nil
}

// IsBlockedIP reports whether an address is one that an outbound request
// derived from configuration must never reach. Shared with the ACME client so
// DNS results are re-checked, not just the literal in the URL.
func IsBlockedIP(ip net.IP) bool {
	if ip == nil {
		return true
	}
	if ip.IsLoopback() || ip.IsLinkLocalUnicast() || ip.IsLinkLocalMulticast() ||
		ip.IsInterfaceLocalMulticast() || ip.IsMulticast() || ip.IsUnspecified() {
		return true
	}
	if ip.IsPrivate() {
		return true
	}
	// 169.254.169.254 is already link-local, but call it out: cloud instance
	// metadata is the single highest-value SSRF target on an AWS host.
	if ip.Equal(net.IPv4(169, 254, 169, 254)) {
		return true
	}
	// Carrier-grade NAT 100.64.0.0/10 — routable-looking but internal.
	if ip4 := ip.To4(); ip4 != nil && ip4[0] == 100 && ip4[1] >= 64 && ip4[1] <= 127 {
		return true
	}
	return false
}

// sanitizeForError strips CR/LF and bounds the length of an echoed value, so a
// hostile environment variable cannot forge extra log lines (A09 log forging).
func sanitizeForError(v string) string {
	v = strings.NewReplacer("\r", "", "\n", "", "\t", " ").Replace(v)
	if len(v) > 64 {
		return v[:64] + "..."
	}
	return v
}

func cidrsToStrings(nets []*net.IPNet) []string {
	if len(nets) == 0 {
		return []string{}
	}
	out := make([]string, 0, len(nets))
	for _, n := range nets {
		out = append(out, n.String())
	}
	return out
}

func orNone(v string) string {
	if v == "" {
		return "(mounted on public listener)"
	}
	return v
}
