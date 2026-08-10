// Package logging builds the relay's structured logger and provides the two
// helpers every log call site needs: Sanitize (log forging) and Fingerprint
// (referring to a secret without printing it).
package logging

import (
	"crypto/sha256"
	"encoding/hex"
	"io"
	"log/slog"
	"strings"
)

// MaxLoggedValueLength bounds any attacker-influenced string that reaches a log
// line. A device name or a snapshot section key is user input; unbounded, it is
// a cheap way to flood the log volume.
const MaxLoggedValueLength = 200

// New returns a JSON logger at the requested level. JSON is not decoration: the
// relay is the internet-facing component, so its log is the primary detection
// surface and needs to be machine-parseable by whatever ships it.
func New(w io.Writer, level string) *slog.Logger {
	var lvl slog.Level
	switch strings.ToLower(level) {
	case "debug":
		lvl = slog.LevelDebug
	case "warn":
		lvl = slog.LevelWarn
	case "error":
		lvl = slog.LevelError
	default:
		lvl = slog.LevelInfo
	}
	return slog.New(slog.NewJSONHandler(w, &slog.HandlerOptions{Level: lvl}))
}

// Sanitize makes an untrusted string safe to log: CR, LF and other control
// characters are removed so a caller cannot inject a fake log record, and the
// result is length-bounded.
//
// JSON encoding already escapes newlines, so this is belt-and-braces for the
// JSON handler — but it is load-bearing the moment a value is interpolated into
// a message string or the handler is swapped for a text one.
func Sanitize(v string) string {
	var b strings.Builder
	b.Grow(len(v))
	for _, r := range v {
		switch {
		case r == '\n' || r == '\r':
			// dropped
		case r < 0x20 || r == 0x7f:
			b.WriteRune(' ')
		default:
			b.WriteRune(r)
		}
	}
	out := b.String()
	if len(out) > MaxLoggedValueLength {
		return out[:MaxLoggedValueLength] + "…"
	}
	return out
}

// Fingerprint renders a stable, non-reversible 8-hex-character tag for a
// secret. It lets an operator answer "is the relay using the same ingest token
// I configured in secman?" by comparing two fingerprints, without either side
// ever logging the credential.
func Fingerprint(secret string) string {
	if secret == "" {
		return "none"
	}
	sum := sha256.Sum256([]byte("secman-relay-fingerprint-v1:" + secret))
	return hex.EncodeToString(sum[:4])
}
