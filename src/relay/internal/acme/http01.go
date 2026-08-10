package acme

import (
	"crypto/ecdsa"
	"crypto/x509"
	"encoding/pem"
	"fmt"
	"net/http"
	"strings"
	"sync"
)

// ChallengePath is the fixed prefix an ACME CA fetches for HTTP-01.
const ChallengePath = "/.well-known/acme-challenge/"

// HTTP01Solver serves challenge responses on plain HTTP port 80.
//
// It is the only part of the relay that answers unauthenticated requests with
// content, so it is kept as small as it can be: an in-memory map that is empty
// except during an issuance, and a handler that serves nothing else.
type HTTP01Solver struct {
	mu     sync.RWMutex
	tokens map[string]string
}

// NewHTTP01Solver builds a solver.
func NewHTTP01Solver() *HTTP01Solver {
	return &HTTP01Solver{tokens: make(map[string]string)}
}

// Present publishes a key authorization.
func (s *HTTP01Solver) Present(token, keyAuth string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.tokens[token] = keyAuth
}

// CleanUp withdraws it again.
func (s *HTTP01Solver) CleanUp(token string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	delete(s.tokens, token)
}

// Pending reports how many challenges are currently published.
func (s *HTTP01Solver) Pending() int {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return len(s.tokens)
}

// Handler serves the challenge path and redirects everything else to https.
//
// redirectHost is the canonical hostname to redirect to; when empty the
// request's own Host header is reused. Only GET and HEAD are answered — the
// port-80 listener must never become a second, weaker API surface.
func (s *HTTP01Solver) Handler(redirectHost string) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet && r.Method != http.MethodHead {
			http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
			return
		}
		w.Header().Set("X-Content-Type-Options", "nosniff")

		if strings.HasPrefix(r.URL.Path, ChallengePath) {
			token := strings.TrimPrefix(r.URL.Path, ChallengePath)
			if err := validateChallengeToken(token); err != nil {
				http.NotFound(w, r)
				return
			}
			s.mu.RLock()
			keyAuth, ok := s.tokens[token]
			s.mu.RUnlock()
			if !ok {
				http.NotFound(w, r)
				return
			}
			w.Header().Set("Content-Type", "application/octet-stream")
			_, _ = w.Write([]byte(keyAuth))
			return
		}

		host := redirectHost
		if host == "" {
			host = r.Host
		}
		if host == "" || strings.ContainsAny(host, "\r\n") {
			// A Host header ends up in a Location header; refuse rather than
			// emit a response-splitting primitive or an open redirect.
			http.NotFound(w, r)
			return
		}
		// Strip any port: the redirect always targets the canonical https port.
		if idx := strings.IndexByte(host, ':'); idx >= 0 {
			host = host[:idx]
		}
		target := "https://" + host + r.URL.RequestURI()
		http.Redirect(w, r, target, http.StatusMovedPermanently)
	})
}

// encodeECPrivateKeyPEM renders a key as PKCS#8 PEM.
func encodeECPrivateKeyPEM(key *ecdsa.PrivateKey) ([]byte, error) {
	der, err := x509.MarshalPKCS8PrivateKey(key)
	if err != nil {
		return nil, fmt.Errorf("acme: encoding private key: %w", err)
	}
	return pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: der}), nil
}

// EncodePrivateKeyPEM is the exported form, used to persist the account key.
func EncodePrivateKeyPEM(key *ecdsa.PrivateKey) ([]byte, error) { return encodeECPrivateKeyPEM(key) }

// ParsePrivateKeyPEM reads an account key back from disk.
func ParsePrivateKeyPEM(raw []byte) (*ecdsa.PrivateKey, error) {
	block, _ := pem.Decode(raw)
	if block == nil {
		return nil, fmt.Errorf("acme: key file does not contain PEM data")
	}
	parsed, err := x509.ParsePKCS8PrivateKey(block.Bytes)
	if err != nil {
		// Older files may carry the SEC 1 form.
		if ecKey, ecErr := x509.ParseECPrivateKey(block.Bytes); ecErr == nil {
			return ecKey, nil
		}
		return nil, fmt.Errorf("acme: key file is not a usable private key: %w", err)
	}
	key, ok := parsed.(*ecdsa.PrivateKey)
	if !ok {
		return nil, fmt.Errorf("acme: key file is not an ECDSA key")
	}
	return key, nil
}
