// Package tlsx owns the relay's certificate lifecycle across the three
// supported termination modes.
//
//	acme  the relay obtains and renews a Let's Encrypt certificate itself
//	file  the relay serves a certificate pair from disk and reloads it on change
//	off   the relay serves plaintext HTTP because something in front of it
//	      (an AWS ALB, an ingress controller) terminates TLS
//
// "off" is a first-class mode, not a debug escape hatch: terminating at an ALB
// is a normal production topology. What makes it safe is that it is explicit —
// config.Load refuses to start in that mode without RELAY_PLAINTEXT_ACK=true —
// and that no security property of the relay depends on TLS being terminated
// here. Authentication is per request and end-to-end at the application layer,
// so the ALB is a transport, not a trust boundary.
package tlsx

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"path/filepath"
	"sync"
	"sync/atomic"
	"time"

	"github.com/schmalle/secman/src/relay/internal/acme"
	"github.com/schmalle/secman/src/relay/internal/config"
	"github.com/schmalle/secman/src/relay/internal/logging"
)

// RenewBefore is how long before expiry a certificate is replaced. Let's
// Encrypt issues for 90 days and recommends renewing at 30 remaining, which
// leaves four weeks of retries before anything user-visible breaks.
const RenewBefore = 30 * 24 * time.Hour

// renewCheckInterval is how often the manager re-examines expiry. Twelve hours
// is the conventional value: frequent enough that a failed renewal has many
// attempts left, rare enough not to matter.
const renewCheckInterval = 12 * time.Hour

// fileWatchInterval is how often "file" mode re-stats the certificate.
// Polling rather than inotify keeps this portable and dependency-free; a
// certificate rotation taking up to a minute to be picked up is not a problem
// for a 90-day artifact.
const fileWatchInterval = time.Minute

// Manager provides certificates to the TLS listener.
type Manager struct {
	cfg    config.TLSConfig
	logger *slog.Logger

	current atomic.Pointer[tls.Certificate]

	solver *acme.HTTP01Solver

	mu           sync.Mutex
	lastFileMod  time.Time
	renewFailure error
}

// New builds a manager for the configured mode. It does not perform network
// I/O; call Start for that.
func New(cfg config.TLSConfig, logger *slog.Logger) (*Manager, error) {
	m := &Manager{cfg: cfg, logger: logger}
	if cfg.Mode == config.TLSModeACME {
		m.solver = acme.NewHTTP01Solver()
	}
	return m, nil
}

// Enabled reports whether the relay terminates TLS itself.
func (m *Manager) Enabled() bool { return m.cfg.Mode != config.TLSModeOff }

// Start prepares the first certificate and launches the maintenance loop.
// It blocks until a certificate is available, so a relay that cannot obtain one
// fails to start rather than serving TLS errors.
func (m *Manager) Start(ctx context.Context) error {
	switch m.cfg.Mode {
	case config.TLSModeOff:
		return nil
	case config.TLSModeFile:
		if err := m.loadFromFiles(); err != nil {
			return err
		}
		go m.watchFiles(ctx)
		return nil
	case config.TLSModeACME:
		if err := m.ensureACMECertificate(ctx); err != nil {
			return err
		}
		go m.renewLoop(ctx)
		return nil
	default:
		return fmt.Errorf("tlsx: unsupported TLS mode %q", m.cfg.Mode)
	}
}

// TLSConfig returns the server configuration.
//
// TLS 1.2 is the floor and the 1.2 cipher list is restricted to ECDHE + AEAD.
// Go picks sane defaults for 1.3, which is what any current iOS client will
// actually negotiate; the 1.2 list exists for the long tail and deliberately
// excludes CBC and RSA key exchange.
func (m *Manager) TLSConfig() *tls.Config {
	if !m.Enabled() {
		return nil
	}
	return &tls.Config{
		MinVersion:     tls.VersionTLS12,
		GetCertificate: m.GetCertificate,
		CurvePreferences: []tls.CurveID{
			tls.X25519, tls.CurveP256,
		},
		CipherSuites: []uint16{
			tls.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
			tls.TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,
			tls.TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305,
			tls.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
			tls.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
			tls.TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305,
		},
		NextProtos: []string{"h2", "http/1.1"},
	}
}

// GetCertificate serves the current certificate.
func (m *Manager) GetCertificate(_ *tls.ClientHelloInfo) (*tls.Certificate, error) {
	cert := m.current.Load()
	if cert == nil {
		return nil, errors.New("tlsx: no certificate is loaded")
	}
	return cert, nil
}

// HTTP01Handler returns the port-80 handler for ACME mode, or nil otherwise.
func (m *Manager) HTTP01Handler() http.Handler {
	if m.solver == nil {
		return nil
	}
	redirectHost := ""
	if len(m.cfg.ACMEDomains) > 0 {
		redirectHost = m.cfg.ACMEDomains[0]
	}
	return m.solver.Handler(redirectHost)
}

// Status describes the certificate for the ops plane.
type Status struct {
	Mode         string     `json:"mode"`
	HasCert      bool       `json:"hasCertificate"`
	NotAfter     *time.Time `json:"notAfter,omitempty"`
	DaysLeft     *int       `json:"daysLeft,omitempty"`
	Domains      []string   `json:"domains,omitempty"`
	LastRenewErr string     `json:"lastRenewError,omitempty"`
}

// Status reports certificate state.
func (m *Manager) Status() Status {
	s := Status{Mode: string(m.cfg.Mode)}
	m.mu.Lock()
	if m.renewFailure != nil {
		s.LastRenewErr = logging.Sanitize(m.renewFailure.Error())
	}
	m.mu.Unlock()

	cert := m.current.Load()
	if cert == nil || len(cert.Certificate) == 0 {
		return s
	}
	leaf, err := leafOf(cert)
	if err != nil {
		return s
	}
	s.HasCert = true
	notAfter := leaf.NotAfter
	days := int(time.Until(notAfter).Hours() / 24)
	s.NotAfter = &notAfter
	s.DaysLeft = &days
	s.Domains = leaf.DNSNames
	return s
}

// --- file mode -------------------------------------------------------------

func (m *Manager) loadFromFiles() error {
	cert, err := tls.LoadX509KeyPair(m.cfg.CertFile, m.cfg.KeyFile)
	if err != nil {
		return fmt.Errorf("tlsx: loading certificate pair: %w", err)
	}
	if _, err := leafOf(&cert); err != nil {
		return err
	}
	m.current.Store(&cert)

	if info, err := os.Stat(m.cfg.CertFile); err == nil {
		m.mu.Lock()
		m.lastFileMod = info.ModTime()
		m.mu.Unlock()
	}
	m.logger.Info("TLS certificate loaded from disk", "certFile", m.cfg.CertFile)
	return nil
}

func (m *Manager) watchFiles(ctx context.Context) {
	ticker := time.NewTicker(fileWatchInterval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			info, err := os.Stat(m.cfg.CertFile)
			if err != nil {
				m.logger.Warn("certificate file is unreadable; keeping the loaded one",
					"error", logging.Sanitize(err.Error()))
				continue
			}
			m.mu.Lock()
			changed := info.ModTime().After(m.lastFileMod)
			m.mu.Unlock()
			if !changed {
				continue
			}
			if err := m.loadFromFiles(); err != nil {
				// Keep serving the previous certificate: a half-written pair
				// during an external renewal must not take the relay down.
				m.logger.Error("reloading certificate failed; keeping the previous one",
					"error", logging.Sanitize(err.Error()))
				continue
			}
			m.logger.Info("TLS certificate reloaded after change on disk")
		}
	}
}

// --- acme mode -------------------------------------------------------------

func (m *Manager) ensureACMECertificate(ctx context.Context) error {
	if err := os.MkdirAll(m.cfg.ACMECacheDir, 0o700); err != nil {
		return fmt.Errorf("tlsx: creating ACME cache directory: %w", err)
	}
	if err := os.Chmod(m.cfg.ACMECacheDir, 0o700); err != nil {
		return fmt.Errorf("tlsx: tightening ACME cache directory: %w", err)
	}

	if cert, err := m.loadCachedCertificate(); err == nil {
		leaf, leafErr := leafOf(cert)
		if leafErr == nil && time.Until(leaf.NotAfter) > RenewBefore {
			m.current.Store(cert)
			m.logger.Info("using cached ACME certificate",
				"notAfter", leaf.NotAfter.UTC().Format(time.RFC3339),
				"domains", leaf.DNSNames)
			return nil
		}
	}
	return m.obtain(ctx)
}

func (m *Manager) obtain(ctx context.Context) error {
	accountKey, err := m.loadOrCreateAccountKey()
	if err != nil {
		return err
	}
	client, err := acme.NewClient(m.cfg.ACMEDirectoryURL, m.cfg.ACMEEmail, accountKey, m.logger)
	if err != nil {
		return err
	}

	m.logger.Info("requesting certificate from ACME CA",
		"directory", m.cfg.ACMEDirectoryURL, "domains", m.cfg.ACMEDomains)

	issueCtx, cancel := context.WithTimeout(ctx, 5*time.Minute)
	defer cancel()

	certPEM, keyPEM, err := client.Obtain(issueCtx, m.cfg.ACMEDomains, m.solver)
	if err != nil {
		m.mu.Lock()
		m.renewFailure = err
		m.mu.Unlock()
		return fmt.Errorf("tlsx: obtaining certificate: %w", err)
	}

	if err := m.writeCached(certPEM, keyPEM); err != nil {
		return err
	}
	cert, err := tls.X509KeyPair(certPEM, keyPEM)
	if err != nil {
		return fmt.Errorf("tlsx: the issued certificate and key do not match: %w", err)
	}
	if _, err := leafOf(&cert); err != nil {
		return err
	}
	m.current.Store(&cert)

	m.mu.Lock()
	m.renewFailure = nil
	m.mu.Unlock()

	leaf, _ := leafOf(&cert)
	m.logger.Info("certificate issued",
		"notAfter", leaf.NotAfter.UTC().Format(time.RFC3339), "domains", leaf.DNSNames)
	return nil
}

func (m *Manager) renewLoop(ctx context.Context) {
	ticker := time.NewTicker(renewCheckInterval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			cert := m.current.Load()
			if cert == nil {
				continue
			}
			leaf, err := leafOf(cert)
			if err != nil {
				continue
			}
			if time.Until(leaf.NotAfter) > RenewBefore {
				continue
			}
			m.logger.Info("renewing certificate",
				"notAfter", leaf.NotAfter.UTC().Format(time.RFC3339))
			if err := m.obtain(ctx); err != nil {
				// Not fatal: the existing certificate is still valid for up to
				// RenewBefore, so there are ~60 further attempts before expiry.
				m.logger.Error("certificate renewal failed; will retry",
					"error", logging.Sanitize(err.Error()),
					"expiresIn", time.Until(leaf.NotAfter).Round(time.Hour).String())
			}
		}
	}
}

func (m *Manager) certPath() string { return filepath.Join(m.cfg.ACMECacheDir, "cert.pem") }
func (m *Manager) keyPath() string  { return filepath.Join(m.cfg.ACMECacheDir, "key.pem") }
func (m *Manager) accountKeyPath() string {
	return filepath.Join(m.cfg.ACMECacheDir, "account.key")
}

func (m *Manager) loadCachedCertificate() (*tls.Certificate, error) {
	cert, err := tls.LoadX509KeyPair(m.certPath(), m.keyPath())
	if err != nil {
		return nil, err
	}
	return &cert, nil
}

func (m *Manager) writeCached(certPEM, keyPEM []byte) error {
	// The certificate is public; the key is not. Different modes on purpose.
	if err := writeFileAtomic(m.certPath(), certPEM, 0o644); err != nil {
		return fmt.Errorf("tlsx: writing certificate: %w", err)
	}
	if err := writeFileAtomic(m.keyPath(), keyPEM, 0o600); err != nil {
		return fmt.Errorf("tlsx: writing private key: %w", err)
	}
	return nil
}

func (m *Manager) loadOrCreateAccountKey() (*ecdsa.PrivateKey, error) {
	raw, err := os.ReadFile(m.accountKeyPath())
	if err == nil {
		key, parseErr := acme.ParsePrivateKeyPEM(raw)
		if parseErr == nil {
			return key, nil
		}
		// Refuse to silently mint a second account: losing the account key
		// means losing the CA's rate-limit and authorization history, and
		// overwriting it on a transient read problem would do exactly that.
		return nil, fmt.Errorf("tlsx: existing ACME account key is unreadable: %w", parseErr)
	}
	if !errors.Is(err, os.ErrNotExist) {
		return nil, fmt.Errorf("tlsx: reading ACME account key: %w", err)
	}

	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return nil, fmt.Errorf("tlsx: generating ACME account key: %w", err)
	}
	pemBytes, err := acme.EncodePrivateKeyPEM(key)
	if err != nil {
		return nil, err
	}
	if err := writeFileAtomic(m.accountKeyPath(), pemBytes, 0o600); err != nil {
		return nil, fmt.Errorf("tlsx: writing ACME account key: %w", err)
	}
	m.logger.Info("generated a new ACME account key", "path", m.accountKeyPath())
	return key, nil
}

func writeFileAtomic(path string, data []byte, mode os.FileMode) error {
	dir := filepath.Dir(path)
	tmp, err := os.CreateTemp(dir, ".tmp-*")
	if err != nil {
		return err
	}
	tmpName := tmp.Name()
	defer func() { _ = os.Remove(tmpName) }()

	if err := tmp.Chmod(mode); err != nil {
		_ = tmp.Close()
		return err
	}
	if _, err := tmp.Write(data); err != nil {
		_ = tmp.Close()
		return err
	}
	if err := tmp.Sync(); err != nil {
		_ = tmp.Close()
		return err
	}
	if err := tmp.Close(); err != nil {
		return err
	}
	return os.Rename(tmpName, path)
}

// leafOf parses and caches the leaf certificate.
func leafOf(cert *tls.Certificate) (*x509.Certificate, error) {
	if cert.Leaf != nil {
		return cert.Leaf, nil
	}
	if len(cert.Certificate) == 0 {
		return nil, errors.New("tlsx: certificate chain is empty")
	}
	leaf, err := x509.ParseCertificate(cert.Certificate[0])
	if err != nil {
		return nil, fmt.Errorf("tlsx: parsing leaf certificate: %w", err)
	}
	cert.Leaf = leaf
	return leaf, nil
}
