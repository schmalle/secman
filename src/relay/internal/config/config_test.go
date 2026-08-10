package config

import (
	"net"
	"strings"
	"testing"
	"time"
)

// validEnv is a minimum viable configuration: plaintext mode with the
// acknowledgement set, and three distinct strong secrets.
func validEnv() map[string]string {
	return map[string]string{
		"RELAY_TLS_MODE":          "off",
		"RELAY_PLAINTEXT_ACK":     "true",
		"RELAY_INGEST_TOKEN":      "ingest-token-that-is-long-enough-01",
		"RELAY_INGEST_HMAC_KEY":   "ingest-hmac-key-that-is-long-enough",
		"RELAY_TOKEN_SIGNING_KEY": "token-signing-key-that-is-long-enough",
		// Apple is on because the default privileged-role rule (ADMIN needs a
		// strong provider) requires at least one strong provider to exist.
		"RELAY_APPLE_ENABLED":   "true",
		"RELAY_APPLE_AUDIENCES": "com.example.secman",
	}
}

func loadFrom(t *testing.T, env map[string]string) (*Config, error) {
	t.Helper()
	return Load(func(k string) string { return env[k] })
}

func mustLoad(t *testing.T, env map[string]string) *Config {
	t.Helper()
	cfg, err := loadFrom(t, env)
	if err != nil {
		t.Fatalf("expected a valid configuration, got: %v", err)
	}
	return cfg
}

func TestLoadAppliesDefaults(t *testing.T) {
	cfg := mustLoad(t, validEnv())

	if cfg.ListenAddr != ":8443" {
		t.Errorf("ListenAddr = %q, want :8443", cfg.ListenAddr)
	}
	if cfg.Device.TokenTTL != 15*time.Minute {
		t.Errorf("TokenTTL = %v, want 15m", cfg.Device.TokenTTL)
	}
	if cfg.Limits.SnapshotMaxAge != 15*time.Minute {
		t.Errorf("SnapshotMaxAge = %v, want 15m", cfg.Limits.SnapshotMaxAge)
	}
	if cfg.IngestListenAddr != "" {
		t.Errorf("IngestListenAddr should default to empty, got %q", cfg.IngestListenAddr)
	}
}

func TestListenPortIsSelectable(t *testing.T) {
	for _, addr := range []string{":9000", "127.0.0.1:8443", "0.0.0.0:65535"} {
		env := validEnv()
		env["RELAY_LISTEN_ADDR"] = addr
		cfg := mustLoad(t, env)
		if cfg.ListenAddr != addr {
			t.Errorf("ListenAddr = %q, want %q", cfg.ListenAddr, addr)
		}
	}
	for _, addr := range []string{"8443", ":0", ":70000", "not-an-ip:443", ":abc"} {
		env := validEnv()
		env["RELAY_LISTEN_ADDR"] = addr
		if _, err := loadFrom(t, env); err == nil {
			t.Errorf("listen addr %q should have been rejected", addr)
		}
	}
}

// Weak or missing secrets must abort the boot, not be papered over.
func TestSecretsFailClosed(t *testing.T) {
	cases := map[string]string{
		"RELAY_INGEST_TOKEN":      "",
		"RELAY_INGEST_HMAC_KEY":   "short",
		"RELAY_TOKEN_SIGNING_KEY": "changeme",
	}
	for key, value := range cases {
		env := validEnv()
		env[key] = value
		_, err := loadFrom(t, env)
		if err == nil {
			t.Errorf("%s=%q should have been rejected", key, value)
			continue
		}
		if !strings.Contains(err.Error(), key) {
			t.Errorf("error for %s should name the variable, got: %v", key, err)
		}
	}
}

func TestPlaceholderSecretRejected(t *testing.T) {
	env := validEnv()
	env["RELAY_INGEST_TOKEN"] = "00000000000000000000000000000000"
	if _, err := loadFrom(t, env); err == nil {
		t.Fatal("a well-known placeholder secret should be rejected")
	}
}

func TestSecretsMustDiffer(t *testing.T) {
	shared := "the-very-same-secret-used-twice-01"
	env := validEnv()
	env["RELAY_INGEST_HMAC_KEY"] = shared
	env["RELAY_TOKEN_SIGNING_KEY"] = shared
	if _, err := loadFrom(t, env); err == nil {
		t.Fatal("reusing one secret for two protocols should be rejected")
	}
}

// Plaintext is a supported deployment (ALB termination) but never an accident.
func TestPlaintextRequiresAcknowledgement(t *testing.T) {
	env := validEnv()
	delete(env, "RELAY_PLAINTEXT_ACK")
	_, err := loadFrom(t, env)
	if err == nil {
		t.Fatal("RELAY_TLS_MODE=off without RELAY_PLAINTEXT_ACK should be rejected")
	}
	if !strings.Contains(err.Error(), "RELAY_PLAINTEXT_ACK") {
		t.Errorf("error should point at the acknowledgement flag, got: %v", err)
	}
}

func TestACMEModeRequirements(t *testing.T) {
	base := func() map[string]string {
		env := validEnv()
		env["RELAY_TLS_MODE"] = "acme"
		delete(env, "RELAY_PLAINTEXT_ACK")
		env["RELAY_ACME_DOMAINS"] = "relay.example.com"
		env["RELAY_ACME_EMAIL"] = "ops@example.com"
		env["RELAY_ACME_ACCEPT_TOS"] = "true"
		return env
	}

	cfg := mustLoad(t, base())
	if cfg.TLS.ACMECacheDir == "" {
		t.Error("ACME cache dir should default under the state dir")
	}

	t.Run("terms must be accepted", func(t *testing.T) {
		env := base()
		env["RELAY_ACME_ACCEPT_TOS"] = "false"
		if _, err := loadFrom(t, env); err == nil {
			t.Fatal("issuing without accepting the CA terms should be rejected")
		}
	})

	t.Run("wildcards rejected", func(t *testing.T) {
		env := base()
		env["RELAY_ACME_DOMAINS"] = "*.example.com"
		if _, err := loadFrom(t, env); err == nil {
			t.Fatal("a wildcard domain cannot be satisfied by HTTP-01 and should be rejected")
		}
	})

	t.Run("domain syntax", func(t *testing.T) {
		for _, d := range []string{"no-dot", "bad_underscore.example.com", "-lead.example.com", "trail-.example.com"} {
			env := base()
			env["RELAY_ACME_DOMAINS"] = d
			if _, err := loadFrom(t, env); err == nil {
				t.Errorf("domain %q should have been rejected", d)
			}
		}
	})

	// The directory URL is fetched by the relay, so it is an SSRF surface.
	t.Run("directory url is ssrf-guarded", func(t *testing.T) {
		for _, u := range []string{
			"http://acme.example.com/directory",
			"https://127.0.0.1/directory",
			"https://169.254.169.254/latest/meta-data",
			"https://10.0.0.5/directory",
			"https://user:pass@acme.example.com/directory",
		} {
			env := base()
			env["RELAY_ACME_DIRECTORY_URL"] = u
			if _, err := loadFrom(t, env); err == nil {
				t.Errorf("directory URL %q should have been rejected", u)
			}
		}
	})
}

func TestFileModeRequiresBothPaths(t *testing.T) {
	env := validEnv()
	env["RELAY_TLS_MODE"] = "file"
	delete(env, "RELAY_PLAINTEXT_ACK")
	env["RELAY_TLS_CERT_FILE"] = "/etc/ssl/relay.crt"
	if _, err := loadFrom(t, env); err == nil {
		t.Fatal("file mode without a key path should be rejected")
	}
	env["RELAY_TLS_KEY_FILE"] = "/etc/ssl/relay.key"
	mustLoad(t, env)
}

func TestCIDRParsing(t *testing.T) {
	env := validEnv()
	env["RELAY_TRUSTED_PROXY_CIDRS"] = "10.0.0.0/8, 192.168.1.5"
	cfg := mustLoad(t, env)
	if len(cfg.TrustedProxyCIDRs) != 2 {
		t.Fatalf("expected 2 networks, got %d", len(cfg.TrustedProxyCIDRs))
	}
	if !cfg.TrustedProxyCIDRs[0].Contains(net.ParseIP("10.1.2.3")) {
		t.Error("10.0.0.0/8 should contain 10.1.2.3")
	}
	if cfg.TrustedProxyCIDRs[1].Contains(net.ParseIP("192.168.1.6")) {
		t.Error("a bare address should become a /32")
	}

	env["RELAY_TRUSTED_PROXY_CIDRS"] = "not-a-network"
	if _, err := loadFrom(t, env); err == nil {
		t.Fatal("an unparseable CIDR should be rejected")
	}
}

// A %v of the config must never render a credential.
func TestRedactionNeverLeaksSecrets(t *testing.T) {
	env := validEnv()
	cfg := mustLoad(t, env)

	rendered := cfg.String()
	for _, secret := range []string{env["RELAY_INGEST_TOKEN"], env["RELAY_INGEST_HMAC_KEY"], env["RELAY_TOKEN_SIGNING_KEY"]} {
		if strings.Contains(rendered, secret) {
			t.Fatalf("Config.String() leaked a secret")
		}
	}
	for key, value := range cfg.Redacted() {
		s, ok := value.(string)
		if !ok {
			continue
		}
		for _, secret := range []string{env["RELAY_INGEST_TOKEN"], env["RELAY_INGEST_HMAC_KEY"], env["RELAY_TOKEN_SIGNING_KEY"]} {
			if strings.Contains(s, secret) {
				t.Fatalf("Redacted()[%q] leaked a secret", key)
			}
		}
	}
}

func TestIsBlockedIP(t *testing.T) {
	blocked := []string{
		"127.0.0.1", "::1", "169.254.169.254", "10.1.2.3", "192.168.0.1",
		"172.16.5.5", "0.0.0.0", "224.0.0.1", "100.64.1.1", "fe80::1",
	}
	for _, s := range blocked {
		if !IsBlockedIP(net.ParseIP(s)) {
			t.Errorf("%s should be blocked", s)
		}
	}
	allowed := []string{"1.1.1.1", "172.32.0.1", "8.8.8.8", "2606:4700::1111", "99.83.1.1"}
	for _, s := range allowed {
		if IsBlockedIP(net.ParseIP(s)) {
			t.Errorf("%s should be allowed", s)
		}
	}
	if !IsBlockedIP(nil) {
		t.Error("a nil address must be treated as blocked")
	}
}

// The identity layer must fail closed in the ways that matter operationally.
func TestIdentityValidation(t *testing.T) {
	t.Run("a provider needs its audience", func(t *testing.T) {
		env := validEnv()
		delete(env, "RELAY_APPLE_AUDIENCES")
		_, err := loadFrom(t, env)
		if err == nil {
			t.Fatal("a verifier with no audience would accept tokens minted for any app")
		}
		if !strings.Contains(err.Error(), "RELAY_APPLE_AUDIENCES") {
			t.Errorf("error should name the variable: %v", err)
		}
	})

	t.Run("github needs a client secret and a redirect", func(t *testing.T) {
		env := validEnv()
		env["RELAY_GITHUB_ENABLED"] = "true"
		if _, err := loadFrom(t, env); err == nil {
			t.Fatal("a confidential client with no secret should be rejected")
		}

		env["RELAY_GITHUB_CLIENT_ID"] = "id"
		env["RELAY_GITHUB_CLIENT_SECRET"] = "secret"
		env["RELAY_GITHUB_REDIRECT_URI"] = "https://relay.example.com/api/v1/auth/github/callback"
		mustLoad(t, env)
	})

	// A relay that can bind nothing is misconfigured, not minimal.
	t.Run("some way to bind a device is required", func(t *testing.T) {
		env := validEnv()
		env["RELAY_APPLE_ENABLED"] = "false"
		delete(env, "RELAY_APPLE_AUDIENCES")
		env["RELAY_ENROLLMENT_CODES_ENABLED"] = "false"
		env["RELAY_PRIVILEGED_ROLES"] = ""
		if _, err := loadFrom(t, env); err == nil {
			t.Fatal("a relay with no binding method at all should be rejected")
		}
	})

	// The rule the deployment asked for: an ADMIN signs in with Apple or
	// Google. If neither is enabled, an admin could never sign in — and that
	// failure would look like an app bug, so the relay refuses to start.
	t.Run("a privileged role needs a reachable strong provider", func(t *testing.T) {
		env := validEnv()
		env["RELAY_APPLE_ENABLED"] = "false"
		delete(env, "RELAY_APPLE_AUDIENCES")
		_, err := loadFrom(t, env)
		if err == nil {
			t.Fatal("ADMIN with no strong provider enabled should be rejected")
		}
		if !strings.Contains(err.Error(), "RELAY_STRONG_PROVIDERS") {
			t.Errorf("error should name the variable: %v", err)
		}
	})

	t.Run("unknown strong provider rejected", func(t *testing.T) {
		env := validEnv()
		env["RELAY_STRONG_PROVIDERS"] = "apple,facebook"
		if _, err := loadFrom(t, env); err == nil {
			t.Fatal("an unknown provider name should be rejected")
		}
	})

	t.Run("defaults match the deployment rule", func(t *testing.T) {
		cfg := mustLoad(t, validEnv())
		if len(cfg.Identity.PrivilegedRoles) != 1 || cfg.Identity.PrivilegedRoles[0] != "ADMIN" {
			t.Errorf("privileged roles = %v, want [ADMIN]", cfg.Identity.PrivilegedRoles)
		}
		if len(cfg.Identity.StrongProviders) != 2 {
			t.Errorf("strong providers = %v, want apple and google", cfg.Identity.StrongProviders)
		}
		if !cfg.Identity.EnrollmentCodesEnabled {
			t.Error("enrollment codes should be available by default for non-privileged users")
		}
	})
}

func TestGitHubClientSecretIsRedacted(t *testing.T) {
	env := validEnv()
	env["RELAY_GITHUB_ENABLED"] = "true"
	env["RELAY_GITHUB_CLIENT_ID"] = "id"
	env["RELAY_GITHUB_CLIENT_SECRET"] = "a-very-secret-github-client-secret"
	env["RELAY_GITHUB_REDIRECT_URI"] = "https://relay.example.com/cb"
	cfg := mustLoad(t, env)

	for key, value := range cfg.Redacted() {
		if s, ok := value.(string); ok && strings.Contains(s, "a-very-secret-github-client-secret") {
			t.Fatalf("Redacted()[%q] leaked the GitHub client secret", key)
		}
	}
}

func TestBadDurationsAndNumbersAreReported(t *testing.T) {
	env := validEnv()
	env["RELAY_TOKEN_TTL"] = "fifteen minutes"
	if _, err := loadFrom(t, env); err == nil {
		t.Fatal("an unparseable duration should be rejected")
	}

	env = validEnv()
	env["RELAY_TOKEN_TTL"] = "48h"
	if _, err := loadFrom(t, env); err == nil {
		t.Fatal("a token TTL beyond the 24h ceiling should be rejected")
	}
}
