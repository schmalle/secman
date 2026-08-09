// Package api implements the device -> relay plane: the read-only API a mobile
// client talks to.
//
// Three properties hold across every route here:
//
//   - Read-only. There is no route that mutates secman state, because there is
//     no path from the relay back to secman at all.
//   - Authenticated per request. Nothing is served on the basis of being on the
//     network, holding a long-lived shared secret, or having authenticated
//     earlier in the session.
//   - Scoped. A device sees the sections its enrollment granted and no others,
//     enforced when the bytes are selected, not in the client.
package api

import (
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"time"

	"github.com/schmalle/secman/src/relay/internal/auth"
	"github.com/schmalle/secman/src/relay/internal/devices"
	"github.com/schmalle/secman/src/relay/internal/httpx"
	"github.com/schmalle/secman/src/relay/internal/logging"
	"github.com/schmalle/secman/src/relay/internal/model"
	"github.com/schmalle/secman/src/relay/internal/store"
)

// Handler serves the mobile plane.
type Handler struct {
	Store      *store.Store
	Devices    *devices.Registry
	Tokens     *auth.TokenIssuer
	Challenges *auth.ChallengeStore
	Limiter    *httpx.RateLimiter
	Logger     *slog.Logger
	MaxAge     time.Duration
	Now        func() time.Time
}

// Routes registers the mobile plane.
//
// The three unauthenticated routes each carry their own rate-limit family so
// that hammering enrollment cannot exhaust a legitimate device's status budget.
func (h *Handler) Routes(mux *http.ServeMux) {
	mux.Handle("POST /api/v1/enroll",
		httpx.Chain(http.HandlerFunc(h.handleEnroll), httpx.Limit(h.Limiter, "enroll", h.Logger)))
	mux.Handle("POST /api/v1/auth/challenge",
		httpx.Chain(http.HandlerFunc(h.handleChallenge), httpx.Limit(h.Limiter, "challenge", h.Logger)))
	mux.Handle("POST /api/v1/auth/token",
		httpx.Chain(http.HandlerFunc(h.handleToken), httpx.Limit(h.Limiter, "token", h.Logger)))

	mux.Handle("GET /api/v1/meta", h.authenticated(h.handleMeta))
	mux.Handle("GET /api/v1/status", h.authenticated(h.handleStatus))
	mux.Handle("GET /api/v1/status/{section}", h.authenticated(h.handleSection))
}

// --- enrollment ------------------------------------------------------------

type enrollRequest struct {
	EnrollmentCode string `json:"enrollmentCode"`
	PublicKey      string `json:"publicKey"` // base64 SPKI DER, ECDSA P-256
	DeviceName     string `json:"deviceName"`
}

type enrollResponse struct {
	DeviceID string   `json:"deviceId"`
	Subject  string   `json:"subject"`
	Scopes   []string `json:"scopes"`
}

func (h *Handler) handleEnroll(w http.ResponseWriter, r *http.Request) {
	now := h.now()

	var req enrollRequest
	if err := httpx.DecodeJSON(r, &req); err != nil {
		httpx.WriteError(w, r, http.StatusBadRequest, err.Error())
		return
	}
	if req.EnrollmentCode == "" || len(req.EnrollmentCode) > 128 {
		httpx.WriteError(w, r, http.StatusBadRequest, "enrollmentCode is required")
		return
	}
	der, err := devices.ParsePublicKeyBase64(req.PublicKey)
	if err != nil {
		httpx.WriteError(w, r, http.StatusBadRequest, err.Error())
		return
	}
	if len(req.DeviceName) > 64 {
		httpx.WriteError(w, r, http.StatusBadRequest, "deviceName must be at most 64 characters")
		return
	}

	device, err := h.Devices.Enroll(req.EnrollmentCode, der, req.DeviceName, now)
	if err != nil {
		status := http.StatusForbidden
		message := "enrollment code is not valid"
		if errors.Is(err, devices.ErrRegistryFull) {
			status = http.StatusServiceUnavailable
			message = "device registry is full; contact an administrator"
		}
		h.Logger.Warn("device enrollment denied",
			"requestId", httpx.RequestIDFrom(r.Context()),
			"peer", httpx.ClientIPFrom(r.Context()),
			"deviceName", logging.Sanitize(req.DeviceName),
			"reason", logging.Sanitize(err.Error()),
			"outcome", "denied")
		httpx.WriteError(w, r, status, message)
		return
	}

	h.Logger.Info("device enrolled",
		"requestId", httpx.RequestIDFrom(r.Context()),
		"peer", httpx.ClientIPFrom(r.Context()),
		"deviceId", device.ID,
		"subject", logging.Sanitize(device.Subject),
		"deviceName", logging.Sanitize(device.Name),
		"scopes", device.Scopes,
		"outcome", "enrolled")

	httpx.WriteJSON(w, r, http.StatusCreated, enrollResponse{
		DeviceID: device.ID,
		Subject:  device.Subject,
		Scopes:   device.Scopes,
	})
}

// --- device authentication -------------------------------------------------

type challengeRequest struct {
	DeviceID string `json:"deviceId"`
}

type challengeResponse struct {
	Nonce     string    `json:"nonce"`
	ExpiresAt time.Time `json:"expiresAt"`
	// SigningInput removes the guesswork from the client implementation: the
	// device signs SHA-256 of exactly these bytes. Publishing it is not a
	// weakness — the security comes from the private key, not from the format
	// being obscure.
	SigningInput string `json:"signingInput"`
	Algorithm    string `json:"algorithm"`
}

func (h *Handler) handleChallenge(w http.ResponseWriter, r *http.Request) {
	now := h.now()

	var req challengeRequest
	if err := httpx.DecodeJSON(r, &req); err != nil {
		httpx.WriteError(w, r, http.StatusBadRequest, err.Error())
		return
	}
	if req.DeviceID == "" || len(req.DeviceID) > 128 {
		httpx.WriteError(w, r, http.StatusBadRequest, "deviceId is required")
		return
	}

	// A challenge is only issued for a known, unrevoked device. The response is
	// identical in shape either way at the HTTP level (403 + generic text), so
	// this does not become a device-id oracle beyond confirming a value the
	// caller already possesses.
	if _, err := h.Devices.Get(req.DeviceID); err != nil {
		h.logDeviceDenied(r, req.DeviceID, err)
		httpx.WriteError(w, r, http.StatusForbidden, "device is not authorized")
		return
	}

	c, err := h.Challenges.Issue(req.DeviceID, now)
	if err != nil {
		httpx.WriteError(w, r, http.StatusServiceUnavailable, "cannot issue a challenge right now")
		return
	}
	httpx.WriteJSON(w, r, http.StatusOK, challengeResponse{
		Nonce:        c.Nonce,
		ExpiresAt:    c.ExpiresAt.UTC(),
		SigningInput: string(auth.DeviceSigningInput(req.DeviceID, c.Nonce)),
		Algorithm:    "ECDSA-P256-SHA256-ASN1",
	})
}

type tokenRequest struct {
	DeviceID  string `json:"deviceId"`
	Nonce     string `json:"nonce"`
	Signature string `json:"signature"` // base64, ASN.1 DER ECDSA
}

type tokenResponse struct {
	AccessToken string   `json:"accessToken"`
	TokenType   string   `json:"tokenType"`
	ExpiresIn   int64    `json:"expiresIn"`
	Scopes      []string `json:"scopes"`
}

func (h *Handler) handleToken(w http.ResponseWriter, r *http.Request) {
	now := h.now()

	var req tokenRequest
	if err := httpx.DecodeJSON(r, &req); err != nil {
		httpx.WriteError(w, r, http.StatusBadRequest, err.Error())
		return
	}
	if req.DeviceID == "" || req.Nonce == "" || req.Signature == "" {
		httpx.WriteError(w, r, http.StatusBadRequest, "deviceId, nonce and signature are required")
		return
	}

	device, err := h.Devices.Get(req.DeviceID)
	if err != nil {
		h.logDeviceDenied(r, req.DeviceID, err)
		httpx.WriteError(w, r, http.StatusForbidden, "device is not authorized")
		return
	}
	// Burn the nonce before checking the signature, so a wrong signature costs
	// the caller a fresh round trip and cannot be brute-forced against one
	// challenge.
	if err := h.Challenges.Redeem(req.DeviceID, req.Nonce, now); err != nil {
		h.logDeviceDenied(r, req.DeviceID, err)
		httpx.WriteError(w, r, http.StatusForbidden, "device is not authorized")
		return
	}
	pub, err := device.PublicKey()
	if err != nil {
		h.Logger.Error("stored device key is unusable",
			"deviceId", device.ID, "error", logging.Sanitize(err.Error()))
		httpx.WriteError(w, r, http.StatusForbidden, "device is not authorized")
		return
	}
	if err := auth.VerifyDeviceSignature(pub, req.DeviceID, req.Nonce, req.Signature); err != nil {
		h.logDeviceDenied(r, req.DeviceID, err)
		httpx.WriteError(w, r, http.StatusForbidden, "device is not authorized")
		return
	}

	token, claims, err := h.Tokens.Issue(device.ID, device.Scopes, now)
	if err != nil {
		httpx.WriteError(w, r, http.StatusInternalServerError, "internal error")
		return
	}
	h.Devices.TouchLastSeen(device.ID, now)

	h.Logger.Info("device authenticated",
		"requestId", httpx.RequestIDFrom(r.Context()),
		"peer", httpx.ClientIPFrom(r.Context()),
		"deviceId", device.ID,
		"subject", logging.Sanitize(device.Subject),
		"jti", claims.JTI,
		"outcome", "token_issued")

	httpx.WriteJSON(w, r, http.StatusOK, tokenResponse{
		AccessToken: token,
		TokenType:   "Bearer",
		ExpiresIn:   int64(h.Tokens.TTL().Seconds()),
		Scopes:      device.Scopes,
	})
}

// --- authenticated reads ---------------------------------------------------

type authedHandler func(w http.ResponseWriter, r *http.Request, device *devices.Device, claims auth.Claims)

func (h *Handler) authenticated(next authedHandler) http.Handler {
	return httpx.Chain(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		now := h.now()

		raw, ok := auth.BearerFromHeader(r.Header.Get("Authorization"))
		if !ok {
			httpx.WriteError(w, r, http.StatusUnauthorized, "authentication required")
			return
		}
		// The token is checked against the *live* device record, so a
		// revocation pushed by secman one second ago takes effect on the very
		// next request rather than when the token would have expired.
		claims, err := h.Tokens.Verify(raw, now, time.Time{})
		if err != nil {
			h.Logger.Warn("access token rejected",
				"requestId", httpx.RequestIDFrom(r.Context()),
				"peer", httpx.ClientIPFrom(r.Context()),
				"outcome", "denied")
			httpx.WriteError(w, r, http.StatusUnauthorized, "authentication required")
			return
		}
		device, err := h.Devices.Get(claims.DeviceID)
		if err != nil {
			h.logDeviceDenied(r, claims.DeviceID, err)
			httpx.WriteError(w, r, http.StatusForbidden, "device is not authorized")
			return
		}
		if !device.TokensValidAfter.IsZero() && claims.IssuedAt <= device.TokensValidAfter.Unix() {
			h.logDeviceDenied(r, claims.DeviceID, errors.New("token predates revocation"))
			httpx.WriteError(w, r, http.StatusForbidden, "device is not authorized")
			return
		}
		// Scopes come from the device record, not from the token. A token
		// minted before an admin narrowed a device's scopes must not keep the
		// wider set.
		h.Devices.TouchLastSeen(device.ID, now)
		next(w, r, device, claims)
	}), httpx.Limit(h.Limiter, "read", h.Logger))
}

type metaResponse struct {
	InstanceID    string    `json:"instanceId"`
	SchemaVersion int       `json:"schemaVersion"`
	GeneratedAt   time.Time `json:"generatedAt"`
	ReceivedAt    time.Time `json:"receivedAt"`
	AgeSeconds    int64     `json:"ageSeconds"`
	Stale         bool      `json:"stale"`
	MaxAgeSeconds int64     `json:"maxAgeSeconds"`
	Sections      []string  `json:"sections"`
	DeviceID      string    `json:"deviceId"`
	Scopes        []string  `json:"scopes"`
}

func (h *Handler) handleMeta(w http.ResponseWriter, r *http.Request, device *devices.Device, _ auth.Claims) {
	now := h.now()
	meta, err := h.Store.Metadata(now, h.MaxAge)
	if errors.Is(err, store.ErrEmpty) {
		httpx.WriteJSON(w, r, http.StatusServiceUnavailable, map[string]any{
			"error":         "no snapshot has been received yet",
			"deviceId":      device.ID,
			"scopes":        device.Scopes,
			"maxAgeSeconds": int64(h.MaxAge.Seconds()),
		})
		return
	}
	// Only the sections this device may read are named, so the listing does not
	// become a map of everything the relay holds.
	visible := make([]string, 0, len(meta.Sections))
	for _, s := range meta.Sections {
		if model.ScopeAllows(device.Scopes, s) {
			visible = append(visible, s)
		}
	}
	httpx.WriteJSON(w, r, http.StatusOK, metaResponse{
		InstanceID:    meta.InstanceID,
		SchemaVersion: meta.SchemaVersion,
		GeneratedAt:   meta.GeneratedAt.UTC(),
		ReceivedAt:    meta.ReceivedAt.UTC(),
		AgeSeconds:    meta.AgeSeconds,
		Stale:         meta.Stale,
		MaxAgeSeconds: int64(h.MaxAge.Seconds()),
		Sections:      visible,
		DeviceID:      device.ID,
		Scopes:        device.Scopes,
	})
}

type statusResponse struct {
	InstanceID    string                     `json:"instanceId"`
	SchemaVersion int                        `json:"schemaVersion"`
	GeneratedAt   time.Time                  `json:"generatedAt"`
	AgeSeconds    int64                      `json:"ageSeconds"`
	Stale         bool                       `json:"stale"`
	Sections      map[string]json.RawMessage `json:"sections"`
}

func (h *Handler) handleStatus(w http.ResponseWriter, r *http.Request, device *devices.Device, _ auth.Claims) {
	now := h.now()
	meta, sections, err := h.Store.Sections(device.Scopes, now, h.MaxAge)
	if errors.Is(err, store.ErrEmpty) {
		httpx.WriteError(w, r, http.StatusServiceUnavailable, "no snapshot has been received yet")
		return
	}
	// ErrStale is not a failure. The payload is returned with stale=true so the
	// app can grey the screen out and show the age, which is far more useful to
	// an on-call admin than an empty view — and, unlike silently serving old
	// data, cannot be mistaken for "all clear right now".
	httpx.WriteJSON(w, r, http.StatusOK, statusResponse{
		InstanceID:    meta.InstanceID,
		SchemaVersion: meta.SchemaVersion,
		GeneratedAt:   meta.GeneratedAt.UTC(),
		AgeSeconds:    meta.AgeSeconds,
		Stale:         meta.Stale,
		Sections:      sections,
	})
}

func (h *Handler) handleSection(w http.ResponseWriter, r *http.Request, device *devices.Device, _ auth.Claims) {
	now := h.now()
	section := r.PathValue("section")

	if err := model.ValidateSectionName(section); err != nil {
		httpx.WriteError(w, r, http.StatusBadRequest, "invalid section name")
		return
	}
	// Authorization before existence: an out-of-scope section answers 403
	// whether or not it exists, so the scope boundary is not a discovery tool.
	if !model.ScopeAllows(device.Scopes, section) {
		h.Logger.Warn("section access denied",
			"requestId", httpx.RequestIDFrom(r.Context()),
			"deviceId", device.ID,
			"section", logging.Sanitize(section),
			"outcome", "denied")
		httpx.WriteError(w, r, http.StatusForbidden, "not permitted for this device")
		return
	}

	raw, found, err := h.Store.Section(section, now, h.MaxAge)
	if errors.Is(err, store.ErrEmpty) {
		httpx.WriteError(w, r, http.StatusServiceUnavailable, "no snapshot has been received yet")
		return
	}
	if !found {
		httpx.WriteError(w, r, http.StatusNotFound, "section not present in the current snapshot")
		return
	}
	meta, _ := h.Store.Metadata(now, h.MaxAge)
	httpx.WriteJSON(w, r, http.StatusOK, map[string]any{
		"instanceId":  meta.InstanceID,
		"generatedAt": meta.GeneratedAt.UTC(),
		"ageSeconds":  meta.AgeSeconds,
		"stale":       meta.Stale,
		"section":     section,
		"data":        raw,
	})
}

func (h *Handler) logDeviceDenied(r *http.Request, deviceID string, err error) {
	h.Logger.Warn("device request denied",
		"requestId", httpx.RequestIDFrom(r.Context()),
		"peer", httpx.ClientIPFrom(r.Context()),
		"deviceId", logging.Sanitize(deviceID),
		"reason", logging.Sanitize(err.Error()),
		"outcome", "denied")
}

func (h *Handler) now() time.Time {
	if h.Now != nil {
		return h.Now()
	}
	return time.Now()
}
