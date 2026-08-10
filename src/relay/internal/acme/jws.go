package acme

import (
	"crypto/ecdsa"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"math/big"
)

// JSON Web Signature support for ACME, limited to exactly one algorithm.
//
// Supporting only ES256 is a security decision, not a shortcut: the classic JWS
// vulnerability class is algorithm confusion, and it does not exist in a
// verifier that has no algorithm to select. The account key is always ECDSA
// P-256.

// jwk is the JSON Web Key for an ECDSA P-256 public key.
//
// The field order below is not cosmetic. RFC 7638 defines the thumbprint as the
// SHA-256 of the JSON with the required members in lexicographic order and no
// whitespace — "crv", "kty", "x", "y". Go's encoding/json emits struct fields
// in declaration order, so this declaration *is* the canonicalisation.
type jwk struct {
	Crv string `json:"crv"`
	Kty string `json:"kty"`
	X   string `json:"x"`
	Y   string `json:"y"`
}

const p256CoordinateBytes = 32

func newJWK(pub *ecdsa.PublicKey) (jwk, error) {
	if pub == nil || pub.Curve == nil || pub.Curve.Params().Name != "P-256" {
		return jwk{}, errors.New("acme: account key must be ECDSA P-256")
	}
	return jwk{
		Crv: "P-256",
		Kty: "EC",
		X:   base64.RawURLEncoding.EncodeToString(padCoordinate(pub.X)),
		Y:   base64.RawURLEncoding.EncodeToString(padCoordinate(pub.Y)),
	}, nil
}

// thumbprint implements RFC 7638. It is half of the HTTP-01 key
// authorization, which is what proves the challenge response came from the
// holder of the account key rather than from anyone who saw the token.
func (k jwk) thumbprint() (string, error) {
	raw, err := json.Marshal(k)
	if err != nil {
		return "", fmt.Errorf("acme: encoding JWK: %w", err)
	}
	sum := sha256.Sum256(raw)
	return base64.RawURLEncoding.EncodeToString(sum[:]), nil
}

// padCoordinate renders a curve coordinate as a fixed-width big-endian value.
// big.Int.Bytes() strips leading zero bytes; a coordinate that happens to start
// with one would otherwise produce a short, and therefore invalid, JWK.
func padCoordinate(v *big.Int) []byte {
	buf := make([]byte, p256CoordinateBytes)
	b := v.Bytes()
	if len(b) > p256CoordinateBytes {
		// Cannot happen for a valid P-256 point; truncating the wrong end
		// would be worse than returning the low-order bytes.
		b = b[len(b)-p256CoordinateBytes:]
	}
	copy(buf[p256CoordinateBytes-len(b):], b)
	return buf
}

// protectedHeader is the JWS protected header ACME requires.
type protectedHeader struct {
	Alg   string `json:"alg"`
	Nonce string `json:"nonce"`
	URL   string `json:"url"`
	// Exactly one of JWK / KID is set: JWK for newAccount and revokeCert, KID
	// for everything afterwards. RFC 8555 §6.2.
	JWK *jwk   `json:"jwk,omitempty"`
	KID string `json:"kid,omitempty"`
}

// jwsBody is the flattened JSON serialization ACME expects.
type jwsBody struct {
	Protected string `json:"protected"`
	Payload   string `json:"payload"`
	Signature string `json:"signature"`
}

// signJWS builds a flattened JWS. A nil payload produces the empty payload used
// by POST-as-GET requests, which is distinct from an empty JSON object.
func signJWS(key *ecdsa.PrivateKey, header protectedHeader, payload []byte) ([]byte, error) {
	header.Alg = "ES256"

	rawHeader, err := json.Marshal(header)
	if err != nil {
		return nil, fmt.Errorf("acme: encoding protected header: %w", err)
	}
	protected := base64.RawURLEncoding.EncodeToString(rawHeader)

	encodedPayload := ""
	if payload != nil {
		encodedPayload = base64.RawURLEncoding.EncodeToString(payload)
	}

	signingInput := protected + "." + encodedPayload
	digest := sha256.Sum256([]byte(signingInput))

	r, s, err := ecdsa.Sign(rand.Reader, key, digest[:])
	if err != nil {
		return nil, fmt.Errorf("acme: signing request: %w", err)
	}
	// ES256 uses the raw R||S concatenation, not the ASN.1 encoding that
	// ecdsa.SignASN1 would produce.
	sig := make([]byte, 2*p256CoordinateBytes)
	copy(sig[:p256CoordinateBytes], padCoordinate(r))
	copy(sig[p256CoordinateBytes:], padCoordinate(s))

	return json.Marshal(jwsBody{
		Protected: protected,
		Payload:   encodedPayload,
		Signature: base64.RawURLEncoding.EncodeToString(sig),
	})
}

// keyAuthorization is the value served at the HTTP-01 challenge path.
func keyAuthorization(token string, k jwk) (string, error) {
	tp, err := k.thumbprint()
	if err != nil {
		return "", err
	}
	return token + "." + tp, nil
}
