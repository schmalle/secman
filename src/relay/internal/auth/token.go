package auth

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"strings"
	"time"
)

// ErrInvalidToken is what every access-token failure returns to the client.
// As with ingest, the caller learns "no" and the log learns why.
var ErrInvalidToken = errors.New("access token is not valid")

// TokenPrefix versions the token format.
const TokenPrefix = "smrt1"

// Claims are the contents of a relay-issued access token.
//
// This is intentionally not a JWT. A JWT would bring an algorithm field that
// has to be pinned, a library, and a parsing surface — for a token that only
// this process issues and only this process verifies. A fixed-format HMAC blob
// has no algorithm confusion to get wrong.
type Claims struct {
	DeviceID  string   `json:"d"`
	Scopes    []string `json:"s"`
	IssuedAt  int64    `json:"iat"`
	ExpiresAt int64    `json:"exp"`
	// JTI exists so a token can be named in an audit log without the log line
	// containing the token itself.
	JTI string `json:"jti"`
}

// TokenIssuer mints and verifies access tokens.
type TokenIssuer struct {
	key []byte
	ttl time.Duration
}

// NewTokenIssuer builds an issuer.
func NewTokenIssuer(key []byte, ttl time.Duration) (*TokenIssuer, error) {
	if len(key) < 32 {
		return nil, errors.New("token signing key must be at least 32 bytes")
	}
	if ttl <= 0 {
		return nil, errors.New("token TTL must be positive")
	}
	return &TokenIssuer{key: append([]byte(nil), key...), ttl: ttl}, nil
}

// TTL exposes the configured lifetime so a handler can report expires_in.
func (t *TokenIssuer) TTL() time.Duration { return t.ttl }

// Issue mints a token for a device.
func (t *TokenIssuer) Issue(deviceID string, scopes []string, now time.Time) (string, Claims, error) {
	jtiBytes := make([]byte, 12)
	if _, err := rand.Read(jtiBytes); err != nil {
		return "", Claims{}, errors.New("generating token id failed")
	}
	claims := Claims{
		DeviceID:  deviceID,
		Scopes:    append([]string(nil), scopes...),
		IssuedAt:  now.Unix(),
		ExpiresAt: now.Add(t.ttl).Unix(),
		JTI:       hex.EncodeToString(jtiBytes),
	}
	payload, err := json.Marshal(claims)
	if err != nil {
		return "", Claims{}, errors.New("encoding token failed")
	}
	body := TokenPrefix + "." + base64.RawURLEncoding.EncodeToString(payload)
	sig := t.sign(body)
	return body + "." + base64.RawURLEncoding.EncodeToString(sig), claims, nil
}

// Verify checks a token's signature and expiry.
//
// `notBefore` is the device's TokensValidAfter instant: a token issued at or
// before it is refused even though the HMAC is intact. That is what makes
// revocation immediate rather than "immediate once the token expires".
func (t *TokenIssuer) Verify(token string, now time.Time, notBefore time.Time) (Claims, error) {
	parts := strings.Split(token, ".")
	if len(parts) != 3 || parts[0] != TokenPrefix {
		return Claims{}, ErrInvalidToken
	}
	body := parts[0] + "." + parts[1]
	sig, err := base64.RawURLEncoding.DecodeString(parts[2])
	if err != nil {
		return Claims{}, ErrInvalidToken
	}
	// Signature first: never unmarshal attacker-controlled JSON that has not
	// been authenticated.
	if !hmac.Equal(sig, t.sign(body)) {
		return Claims{}, ErrInvalidToken
	}

	payload, err := base64.RawURLEncoding.DecodeString(parts[1])
	if err != nil {
		return Claims{}, ErrInvalidToken
	}
	var claims Claims
	if err := json.Unmarshal(payload, &claims); err != nil {
		return Claims{}, ErrInvalidToken
	}
	if claims.DeviceID == "" {
		return Claims{}, ErrInvalidToken
	}
	if now.Unix() >= claims.ExpiresAt {
		return Claims{}, ErrInvalidToken
	}
	// Guard against a token minted with an absurd lifetime by a future bug.
	if claims.ExpiresAt-claims.IssuedAt > int64((24 * time.Hour).Seconds()) {
		return Claims{}, ErrInvalidToken
	}
	if !notBefore.IsZero() && claims.IssuedAt <= notBefore.Unix() {
		return Claims{}, ErrInvalidToken
	}
	return claims, nil
}

// BearerFromHeader extracts a token from an Authorization header.
func BearerFromHeader(header string) (string, bool) { return bearerToken(header) }

func (t *TokenIssuer) sign(body string) []byte {
	mac := hmac.New(sha256.New, t.key)
	mac.Write([]byte(body))
	return mac.Sum(nil)
}
