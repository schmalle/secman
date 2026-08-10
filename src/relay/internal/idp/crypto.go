package idp

import (
	"crypto"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
	"errors"
	"fmt"
	"net"
	"net/http"
	"net/url"
	"strings"
	"syscall"
	"time"

	"github.com/schmalle/secman/src/relay/internal/config"
)

// verifyRS256 checks a PKCS#1 v1.5 signature over the JWS signing input.
func verifyRS256(key *rsa.PublicKey, signingInput string, signature []byte) error {
	digest := sha256.Sum256([]byte(signingInput))
	return rsa.VerifyPKCS1v15(key, crypto.SHA256, digest[:], signature)
}

// NewNonce returns a fresh, unguessable nonce.
func NewNonce() (string, error) {
	buf := make([]byte, 32)
	if _, err := rand.Read(buf); err != nil {
		return "", errors.New("idp: generating nonce failed")
	}
	return hex.EncodeToString(buf), nil
}

// HashNonce is the transformation an iOS client applies before handing a nonce
// to Sign in with Apple: SHA-256 of the raw value, lowercase hex. Exported so
// the app and the test suite derive it the same way instead of guessing.
func HashNonce(raw string) string {
	sum := sha256.Sum256([]byte(raw))
	return hex.EncodeToString(sum[:])
}

func constantTimeEqual(a, b string) bool {
	if len(a) != len(b) {
		return false
	}
	return subtle.ConstantTimeCompare([]byte(a), []byte(b)) == 1
}

// NewHTTPClient builds the one outbound client every identity provider uses.
//
// It carries the same SSRF policy as the ACME client: TLS only, port 443 only,
// no redirects, no ambient proxy, and — critically — the destination address is
// re-checked in Dialer.Control *after* DNS resolution, so a provider hostname
// that resolves to 169.254.169.254 is refused at connect time rather than
// trusted because the URL looked fine.
func NewHTTPClient(timeout time.Duration) *http.Client {
	dialer := &net.Dialer{
		Timeout:   10 * time.Second,
		KeepAlive: 30 * time.Second,
		Control: func(network, address string, _ syscall.RawConn) error {
			return checkDialAddress(network, address)
		},
	}
	transport := http.DefaultTransport.(*http.Transport).Clone()
	transport.DialContext = dialer.DialContext
	transport.Proxy = nil

	return &http.Client{
		Timeout:   timeout,
		Transport: transport,
		CheckRedirect: func(req *http.Request, via []*http.Request) error {
			// Never follow a redirect: it would let a provider response walk
			// the client to a host the guard above would have to re-derive.
			return http.ErrUseLastResponse
		},
	}
}

func checkDialAddress(network, address string) error {
	if network != "tcp4" && network != "tcp6" && network != "tcp" {
		return fmt.Errorf("idp: refusing to dial network %q", network)
	}
	host, port, err := net.SplitHostPort(address)
	if err != nil {
		return errors.New("idp: unparseable dial address")
	}
	if port != "443" {
		return errors.New("idp: refusing to dial a non-https port")
	}
	ip := net.ParseIP(host)
	if ip == nil || config.IsBlockedIP(ip) {
		return errors.New("idp: refusing to dial a loopback, link-local, metadata or private address")
	}
	return nil
}

// ValidateProviderURL guards a provider endpoint read from configuration.
func ValidateProviderURL(raw string) error {
	if raw == "" {
		return errors.New("idp: empty URL")
	}
	if len(raw) > 2048 {
		return errors.New("idp: URL is implausibly long")
	}
	u, err := url.Parse(raw)
	if err != nil {
		return errors.New("idp: URL is not parseable")
	}
	if !strings.EqualFold(u.Scheme, "https") {
		return errors.New("idp: URL must use https")
	}
	if u.User != nil {
		return errors.New("idp: URL must not carry credentials")
	}
	if u.Hostname() == "" {
		return errors.New("idp: URL has no host")
	}
	if port := u.Port(); port != "" && port != "443" {
		return errors.New("idp: URL must use the default https port")
	}
	if ip := net.ParseIP(u.Hostname()); ip != nil && config.IsBlockedIP(ip) {
		return errors.New("idp: URL points at a blocked address range")
	}
	return nil
}
