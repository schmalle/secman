// Package acme is a minimal RFC 8555 client: enough to obtain and renew a
// Let's Encrypt certificate with the HTTP-01 challenge, and nothing else.
//
// Why not golang.org/x/crypto/acme/autocert? Because the relay is the one
// component of secman that sits on the public internet, and its value as a
// target is "the box that has already been trusted by the phone app". Keeping
// its dependency graph at exactly zero means its supply chain is the Go release
// and this repository — nothing else to audit, pin or patch out of band. The
// protocol surface actually needed here is small: one account, one order, one
// challenge type, one key type.
//
// Deliberate limitations, all of them documented in docs/RELAY.md:
//   - HTTP-01 only. No DNS-01, therefore no wildcard certificates.
//   - ECDSA P-256 account and certificate keys only.
//   - No external account binding, no certificate revocation.
package acme

import (
	"bytes"
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/schmalle/secman/src/relay/internal/config"
	"github.com/schmalle/secman/src/relay/internal/logging"
)

// LetsEncryptProduction is the default ACME directory.
const LetsEncryptProduction = "https://acme-v02.api.letsencrypt.org/directory"

// LetsEncryptStaging issues untrusted certificates from a directory with far
// looser rate limits. Point RELAY_ACME_DIRECTORY_URL here while testing a
// deployment; the production limits are low enough to lock you out for a week.
const LetsEncryptStaging = "https://acme-staging-v02.api.letsencrypt.org/directory"

const (
	maxACMEResponseBytes = 1 << 20
	pollInterval         = 2 * time.Second
	maxPollAttempts      = 45 // ~90s, comfortably above Let's Encrypt's usual few seconds
)

// directory is the ACME directory resource.
type directory struct {
	NewNonce   string `json:"newNonce"`
	NewAccount string `json:"newAccount"`
	NewOrder   string `json:"newOrder"`
}

type problem struct {
	Type   string `json:"type"`
	Detail string `json:"detail"`
	Status int    `json:"status"`
}

func (p problem) Error() string {
	if p.Detail != "" {
		return fmt.Sprintf("acme: %s (%s)", p.Detail, p.Type)
	}
	return "acme: " + p.Type
}

type order struct {
	Status         string       `json:"status"`
	Expires        string       `json:"expires"`
	Identifiers    []identifier `json:"identifiers"`
	Authorizations []string     `json:"authorizations"`
	Finalize       string       `json:"finalize"`
	Certificate    string       `json:"certificate"`
	url            string
}

type identifier struct {
	Type  string `json:"type"`
	Value string `json:"value"`
}

type authorization struct {
	Status     string      `json:"status"`
	Identifier identifier  `json:"identifier"`
	Challenges []challenge `json:"challenges"`
}

type challenge struct {
	Type   string `json:"type"`
	URL    string `json:"url"`
	Token  string `json:"token"`
	Status string `json:"status"`
}

// Solver publishes and withdraws an HTTP-01 challenge response.
type Solver interface {
	Present(token, keyAuth string)
	CleanUp(token string)
}

// Client talks to one ACME CA with one account key.
type Client struct {
	DirectoryURL string
	Email        string
	AccountKey   *ecdsa.PrivateKey
	Logger       *slog.Logger

	http *http.Client
	// validateURL is the guard applied to every URL before it is fetched.
	// It is a field only so the test suite can point the client at a local
	// mock CA; there is no exported way to replace it, so a production build
	// always uses validateACMEURL.
	validateURL func(string) error

	mu    sync.Mutex
	dir   *directory
	nonce string
	kid   string
}

// NewClient builds a client. The HTTP client is constructed here rather than
// injected so that the SSRF guard below cannot be configured away by a caller.
func NewClient(directoryURL, email string, accountKey *ecdsa.PrivateKey, logger *slog.Logger) (*Client, error) {
	if accountKey == nil {
		return nil, errors.New("acme: account key is required")
	}
	if _, err := newJWK(&accountKey.PublicKey); err != nil {
		return nil, err
	}
	if directoryURL == "" {
		directoryURL = LetsEncryptProduction
	}
	return &Client{
		DirectoryURL: directoryURL,
		Email:        email,
		AccountKey:   accountKey,
		Logger:       logger,
		validateURL:  validateACMEURL,
		http: &http.Client{
			Timeout:   30 * time.Second,
			Transport: safeTransport(),
			CheckRedirect: func(req *http.Request, via []*http.Request) error {
				// ACME does not need redirects, and following one would let a
				// CA response walk the client to a host the SSRF guard would
				// otherwise have to re-derive.
				return http.ErrUseLastResponse
			},
		},
	}, nil
}

// safeTransport refuses to connect to any address that configuration-derived
// URLs must never reach.
//
// The check runs in Dialer.Control, i.e. after DNS resolution and immediately
// before connect. Validating the hostname in the URL is not sufficient: a
// hostile or compromised directory can return further URLs, and a name that
// resolved publicly once can resolve to 169.254.169.254 the next time (DNS
// rebinding). Checking the resolved address closes both.
func safeTransport() *http.Transport {
	dialer := &net.Dialer{
		Timeout:   10 * time.Second,
		KeepAlive: 30 * time.Second,
		Control: func(network, address string, _ syscall.RawConn) error {
			return checkDialAddress(network, address)
		},
	}
	t := http.DefaultTransport.(*http.Transport).Clone()
	t.DialContext = dialer.DialContext
	t.Proxy = nil // never route ACME through an ambient proxy env var
	return t
}

// checkDialAddress is the post-DNS SSRF guard. Split out from the dialer so it
// can be exercised directly.
func checkDialAddress(network, address string) error {
	if network != "tcp4" && network != "tcp6" && network != "tcp" {
		return fmt.Errorf("acme: refusing to dial network %q", network)
	}
	host, port, err := net.SplitHostPort(address)
	if err != nil {
		return errors.New("acme: unparseable dial address")
	}
	if port != "443" {
		return errors.New("acme: refusing to dial a non-https port")
	}
	ip := net.ParseIP(host)
	if ip == nil || config.IsBlockedIP(ip) {
		return errors.New("acme: refusing to dial a loopback, link-local, metadata or private address")
	}
	return nil
}

func (c *Client) checkURL(u string) error {
	if c.validateURL != nil {
		return c.validateURL(u)
	}
	return validateACMEURL(u)
}

// Obtain runs a full issuance: account, order, challenges, CSR, download.
// It returns the certificate chain and the freshly generated private key, both
// PEM-encoded.
func (c *Client) Obtain(ctx context.Context, domains []string, solver Solver) (certPEM, keyPEM []byte, err error) {
	if len(domains) == 0 {
		return nil, nil, errors.New("acme: at least one domain is required")
	}
	if solver == nil {
		return nil, nil, errors.New("acme: a challenge solver is required")
	}

	if err := c.ensureDirectory(ctx); err != nil {
		return nil, nil, err
	}
	if err := c.ensureAccount(ctx); err != nil {
		return nil, nil, err
	}

	ord, err := c.newOrder(ctx, domains)
	if err != nil {
		return nil, nil, err
	}

	for _, authzURL := range ord.Authorizations {
		if err := c.solveAuthorization(ctx, authzURL, solver); err != nil {
			return nil, nil, err
		}
	}

	certKey, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return nil, nil, fmt.Errorf("acme: generating certificate key: %w", err)
	}
	csrDER, err := makeCSR(certKey, domains)
	if err != nil {
		return nil, nil, err
	}

	finalized, err := c.finalize(ctx, ord, csrDER)
	if err != nil {
		return nil, nil, err
	}
	certPEM, err = c.downloadCertificate(ctx, finalized.Certificate)
	if err != nil {
		return nil, nil, err
	}
	keyPEM, err = encodeECPrivateKeyPEM(certKey)
	if err != nil {
		return nil, nil, err
	}
	return certPEM, keyPEM, nil
}

func (c *Client) ensureDirectory(ctx context.Context) error {
	c.mu.Lock()
	cached := c.dir
	c.mu.Unlock()
	if cached != nil {
		return nil
	}

	if err := c.checkURL(c.DirectoryURL); err != nil {
		return err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, c.DirectoryURL, nil)
	if err != nil {
		return fmt.Errorf("acme: building directory request: %w", err)
	}
	resp, err := c.http.Do(req)
	if err != nil {
		return fmt.Errorf("acme: fetching directory: %w", err)
	}
	defer resp.Body.Close()

	body, err := io.ReadAll(io.LimitReader(resp.Body, maxACMEResponseBytes))
	if err != nil {
		return fmt.Errorf("acme: reading directory: %w", err)
	}
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("acme: directory returned HTTP %d", resp.StatusCode)
	}
	var dir directory
	if err := json.Unmarshal(body, &dir); err != nil {
		return fmt.Errorf("acme: directory is not valid JSON: %w", err)
	}
	// Every subsequent request goes to a URL the CA supplied. Validate them
	// once, here, instead of trusting the directory because it came over TLS.
	for name, u := range map[string]string{"newNonce": dir.NewNonce, "newAccount": dir.NewAccount, "newOrder": dir.NewOrder} {
		if u == "" {
			return fmt.Errorf("acme: directory is missing %s", name)
		}
		if err := c.checkURL(u); err != nil {
			return fmt.Errorf("acme: directory %s: %w", name, err)
		}
	}

	c.mu.Lock()
	c.dir = &dir
	if n := resp.Header.Get("Replay-Nonce"); n != "" {
		c.nonce = n
	}
	c.mu.Unlock()
	return nil
}

func (c *Client) ensureAccount(ctx context.Context) error {
	c.mu.Lock()
	haveKID := c.kid != ""
	newAccountURL := ""
	if c.dir != nil {
		newAccountURL = c.dir.NewAccount
	}
	c.mu.Unlock()
	if haveKID {
		return nil
	}

	payload := map[string]any{"termsOfServiceAgreed": true}
	if c.Email != "" {
		payload["contact"] = []string{"mailto:" + c.Email}
	}
	raw, err := json.Marshal(payload)
	if err != nil {
		return fmt.Errorf("acme: encoding account request: %w", err)
	}

	resp, _, err := c.post(ctx, newAccountURL, raw, true)
	if err != nil {
		return err
	}
	kid := resp.Header.Get("Location")
	if kid == "" {
		return errors.New("acme: account response carried no Location header")
	}
	if err := c.checkURL(kid); err != nil {
		return fmt.Errorf("acme: account URL: %w", err)
	}

	c.mu.Lock()
	c.kid = kid
	c.mu.Unlock()
	return nil
}

func (c *Client) newOrder(ctx context.Context, domains []string) (*order, error) {
	ids := make([]identifier, 0, len(domains))
	for _, d := range domains {
		ids = append(ids, identifier{Type: "dns", Value: d})
	}
	raw, err := json.Marshal(map[string]any{"identifiers": ids})
	if err != nil {
		return nil, fmt.Errorf("acme: encoding order: %w", err)
	}

	c.mu.Lock()
	newOrderURL := c.dir.NewOrder
	c.mu.Unlock()

	resp, body, err := c.post(ctx, newOrderURL, raw, false)
	if err != nil {
		return nil, err
	}
	var ord order
	if err := json.Unmarshal(body, &ord); err != nil {
		return nil, fmt.Errorf("acme: order is not valid JSON: %w", err)
	}
	ord.url = resp.Header.Get("Location")
	if ord.url == "" {
		return nil, errors.New("acme: order response carried no Location header")
	}
	if err := c.checkURL(ord.url); err != nil {
		return nil, fmt.Errorf("acme: order URL: %w", err)
	}
	for _, a := range ord.Authorizations {
		if err := c.checkURL(a); err != nil {
			return nil, fmt.Errorf("acme: authorization URL: %w", err)
		}
	}
	if err := c.checkURL(ord.Finalize); err != nil {
		return nil, fmt.Errorf("acme: finalize URL: %w", err)
	}
	return &ord, nil
}

func (c *Client) solveAuthorization(ctx context.Context, authzURL string, solver Solver) error {
	_, body, err := c.post(ctx, authzURL, nil, false) // POST-as-GET
	if err != nil {
		return err
	}
	var authz authorization
	if err := json.Unmarshal(body, &authz); err != nil {
		return fmt.Errorf("acme: authorization is not valid JSON: %w", err)
	}
	if authz.Status == "valid" {
		return nil // already satisfied by an earlier order
	}

	var http01 *challenge
	for i := range authz.Challenges {
		if authz.Challenges[i].Type == "http-01" {
			http01 = &authz.Challenges[i]
			break
		}
	}
	if http01 == nil {
		return fmt.Errorf("acme: no http-01 challenge offered for %s", logging.Sanitize(authz.Identifier.Value))
	}
	if err := c.checkURL(http01.URL); err != nil {
		return fmt.Errorf("acme: challenge URL: %w", err)
	}
	if err := validateChallengeToken(http01.Token); err != nil {
		return err
	}

	k, err := newJWK(&c.AccountKey.PublicKey)
	if err != nil {
		return err
	}
	keyAuth, err := keyAuthorization(http01.Token, k)
	if err != nil {
		return err
	}

	solver.Present(http01.Token, keyAuth)
	defer solver.CleanUp(http01.Token)

	// An empty JSON object tells the CA the challenge is ready to be validated.
	if _, _, err := c.post(ctx, http01.URL, []byte("{}"), false); err != nil {
		return err
	}

	for attempt := 0; attempt < maxPollAttempts; attempt++ {
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(pollInterval):
		}
		_, body, err := c.post(ctx, authzURL, nil, false)
		if err != nil {
			return err
		}
		var current authorization
		if err := json.Unmarshal(body, &current); err != nil {
			return fmt.Errorf("acme: authorization poll is not valid JSON: %w", err)
		}
		switch current.Status {
		case "valid":
			return nil
		case "invalid", "revoked", "deactivated", "expired":
			return fmt.Errorf("acme: authorization for %s ended as %s",
				logging.Sanitize(current.Identifier.Value), logging.Sanitize(current.Status))
		}
	}
	return errors.New("acme: timed out waiting for the CA to validate the challenge")
}

func (c *Client) finalize(ctx context.Context, ord *order, csrDER []byte) (*order, error) {
	raw, err := json.Marshal(map[string]string{
		"csr": base64.RawURLEncoding.EncodeToString(csrDER),
	})
	if err != nil {
		return nil, fmt.Errorf("acme: encoding finalize request: %w", err)
	}
	if _, _, err := c.post(ctx, ord.Finalize, raw, false); err != nil {
		return nil, err
	}

	for attempt := 0; attempt < maxPollAttempts; attempt++ {
		_, body, err := c.post(ctx, ord.url, nil, false)
		if err != nil {
			return nil, err
		}
		var current order
		if err := json.Unmarshal(body, &current); err != nil {
			return nil, fmt.Errorf("acme: order poll is not valid JSON: %w", err)
		}
		switch current.Status {
		case "valid":
			if err := c.checkURL(current.Certificate); err != nil {
				return nil, fmt.Errorf("acme: certificate URL: %w", err)
			}
			current.url = ord.url
			return &current, nil
		case "invalid":
			return nil, errors.New("acme: the CA rejected the certificate order")
		}
		select {
		case <-ctx.Done():
			return nil, ctx.Err()
		case <-time.After(pollInterval):
		}
	}
	return nil, errors.New("acme: timed out waiting for the order to be finalized")
}

func (c *Client) downloadCertificate(ctx context.Context, certURL string) ([]byte, error) {
	resp, body, err := c.postWithAccept(ctx, certURL, nil, false, "application/pem-certificate-chain")
	if err != nil {
		return nil, err
	}
	_ = resp
	if len(body) == 0 {
		return nil, errors.New("acme: the CA returned an empty certificate")
	}
	if !bytes.Contains(body, []byte("-----BEGIN CERTIFICATE-----")) {
		return nil, errors.New("acme: the CA response is not a PEM certificate chain")
	}
	return body, nil
}

// post performs a signed ACME request, retrying once on a badNonce error.
func (c *Client) post(ctx context.Context, url string, payload []byte, useJWK bool) (*http.Response, []byte, error) {
	return c.postWithAccept(ctx, url, payload, useJWK, "application/json")
}

func (c *Client) postWithAccept(ctx context.Context, targetURL string, payload []byte, useJWK bool, accept string) (*http.Response, []byte, error) {
	if err := c.checkURL(targetURL); err != nil {
		return nil, nil, err
	}
	var lastErr error
	for attempt := 0; attempt < 2; attempt++ {
		nonce, err := c.takeNonce(ctx)
		if err != nil {
			return nil, nil, err
		}

		header := protectedHeader{Nonce: nonce, URL: targetURL}
		if useJWK {
			k, err := newJWK(&c.AccountKey.PublicKey)
			if err != nil {
				return nil, nil, err
			}
			header.JWK = &k
		} else {
			c.mu.Lock()
			header.KID = c.kid
			c.mu.Unlock()
			if header.KID == "" {
				return nil, nil, errors.New("acme: no account URL; call ensureAccount first")
			}
		}

		signed, err := signJWS(c.AccountKey, header, payload)
		if err != nil {
			return nil, nil, err
		}

		req, err := http.NewRequestWithContext(ctx, http.MethodPost, targetURL, bytes.NewReader(signed))
		if err != nil {
			return nil, nil, fmt.Errorf("acme: building request: %w", err)
		}
		req.Header.Set("Content-Type", "application/jose+json")
		req.Header.Set("Accept", accept)

		resp, err := c.http.Do(req)
		if err != nil {
			return nil, nil, fmt.Errorf("acme: request failed: %w", err)
		}
		body, readErr := io.ReadAll(io.LimitReader(resp.Body, maxACMEResponseBytes))
		resp.Body.Close()
		if readErr != nil {
			return nil, nil, fmt.Errorf("acme: reading response: %w", readErr)
		}
		if n := resp.Header.Get("Replay-Nonce"); n != "" {
			c.mu.Lock()
			c.nonce = n
			c.mu.Unlock()
		}

		if resp.StatusCode >= 200 && resp.StatusCode < 300 {
			return resp, body, nil
		}

		var p problem
		if json.Unmarshal(body, &p) == nil && p.Type != "" {
			if strings.HasSuffix(p.Type, ":badNonce") && attempt == 0 {
				// The one error RFC 8555 says to retry.
				lastErr = p
				continue
			}
			return nil, nil, p
		}
		return nil, nil, fmt.Errorf("acme: request to CA returned HTTP %d", resp.StatusCode)
	}
	if lastErr == nil {
		lastErr = errors.New("acme: request failed")
	}
	return nil, nil, lastErr
}

func (c *Client) takeNonce(ctx context.Context) (string, error) {
	c.mu.Lock()
	if c.nonce != "" {
		n := c.nonce
		c.nonce = ""
		c.mu.Unlock()
		return n, nil
	}
	newNonceURL := ""
	if c.dir != nil {
		newNonceURL = c.dir.NewNonce
	}
	c.mu.Unlock()

	if newNonceURL == "" {
		return "", errors.New("acme: directory has not been fetched")
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodHead, newNonceURL, nil)
	if err != nil {
		return "", fmt.Errorf("acme: building nonce request: %w", err)
	}
	resp, err := c.http.Do(req)
	if err != nil {
		return "", fmt.Errorf("acme: fetching nonce: %w", err)
	}
	defer resp.Body.Close()
	_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, 4096))

	n := resp.Header.Get("Replay-Nonce")
	if n == "" {
		return "", errors.New("acme: the CA returned no nonce")
	}
	return n, nil
}

// makeCSR builds a CSR carrying the domains as SANs. The subject is left empty
// apart from the first domain as CN: CAs derive the names from the SAN
// extension, and an over-specified subject only invites a rejection.
func makeCSR(key *ecdsa.PrivateKey, domains []string) ([]byte, error) {
	tmpl := &x509.CertificateRequest{
		Subject:            pkix.Name{CommonName: domains[0]},
		DNSNames:           domains,
		SignatureAlgorithm: x509.ECDSAWithSHA256,
	}
	der, err := x509.CreateCertificateRequest(rand.Reader, tmpl, key)
	if err != nil {
		return nil, fmt.Errorf("acme: building CSR: %w", err)
	}
	return der, nil
}

// validateACMEURL is applied to every URL before it is fetched, including the
// ones the CA supplies mid-flow.
func validateACMEURL(raw string) error {
	if raw == "" {
		return errors.New("acme: empty URL")
	}
	if len(raw) > 2048 {
		return errors.New("acme: URL is implausibly long")
	}
	u, err := url.Parse(raw)
	if err != nil {
		return errors.New("acme: URL is not parseable")
	}
	if !strings.EqualFold(u.Scheme, "https") {
		return errors.New("acme: URL must use https")
	}
	if u.User != nil {
		return errors.New("acme: URL must not carry credentials")
	}
	if u.Hostname() == "" {
		return errors.New("acme: URL has no host")
	}
	if port := u.Port(); port != "" && port != "443" {
		return errors.New("acme: URL must use the default https port")
	}
	if ip := net.ParseIP(u.Hostname()); ip != nil && config.IsBlockedIP(ip) {
		return errors.New("acme: URL points at a blocked address range")
	}
	return nil
}

// validateChallengeToken constrains the CA-supplied token before it becomes
// part of a URL path the relay serves. RFC 8555 defines it as base64url, and
// anything else here would be a path-traversal attempt wearing a CA's clothes.
func validateChallengeToken(token string) error {
	if token == "" || len(token) > 256 {
		return errors.New("acme: challenge token has an implausible length")
	}
	for _, r := range token {
		ok := (r >= 'a' && r <= 'z') || (r >= 'A' && r <= 'Z') || (r >= '0' && r <= '9') || r == '-' || r == '_'
		if !ok {
			return errors.New("acme: challenge token contains an unexpected character")
		}
	}
	return nil
}
