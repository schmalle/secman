// Package api implements the device -> relay plane: the read-only API a mobile
// client talks to.
//
// Four properties hold across every route here.
//
//   - **Read-only.** No route mutates secman state, because no path from the
//     relay back to secman exists at all.
//   - **Authenticated per request.** Nothing is served on the basis of being on
//     the network, holding a long-lived shared secret, or having authenticated
//     earlier in the session.
//   - **Authorized like secman.** A section is readable only if the caller's
//     principal holds one of the secman roles the section's policy demands —
//     the same roles the originating secman controller's `@Secured` demands.
//     A user sees on a phone exactly what they would see in the web UI.
//   - **Scoped per device.** On top of the role gate, a device may be granted
//     a subset of its user's sections. A scope can narrow, never widen.
package api

import (
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"time"

	"github.com/schmalle/secman/src/relay/internal/auth"
	"github.com/schmalle/secman/src/relay/internal/devices"
	"github.com/schmalle/secman/src/relay/internal/httpx"
	"github.com/schmalle/secman/src/relay/internal/idp"
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

	// Verifiers holds the OIDC verifiers by provider name ("apple", "google").
	// A provider that is not configured simply is not offered.
	Verifiers map[string]*idp.Verifier
	// GitHub is nil when the GitHub login is not configured.
	GitHub *idp.GitHubClient

	// LoginNonces are issued for the OIDC flows; States and Tickets carry the
	// GitHub browser round trip. All three are single-use and key-bound.
	LoginNonces *idp.EphemeralStore
	States      *idp.EphemeralStore
	Tickets     *idp.EphemeralStore

	// EnrollmentCodesEnabled allows the code-based path to be switched off
	// entirely in a deployment that wants federated login only.
	EnrollmentCodesEnabled bool

	Now func() time.Time
}

// Routes registers the mobile plane.
//
// Each unauthenticated route carries its own rate-limit family so that
// hammering one cannot exhaust a legitimate device's budget on another.
func (h *Handler) Routes(mux *http.ServeMux) {
	limited := func(family string, fn http.HandlerFunc) http.Handler {
		return httpx.Chain(fn, httpx.Limit(h.Limiter, family, h.Logger))
	}

	mux.Handle("GET /api/v1/providers", limited("providers", h.handleProviders))

	mux.Handle("POST /api/v1/auth/nonce", limited("login", h.handleNonce))
	mux.Handle("POST /api/v1/auth/oidc", limited("login", h.handleOIDCBind))
	mux.Handle("POST /api/v1/auth/github/start", limited("login", h.handleGitHubStart))
	mux.Handle("GET /api/v1/auth/github/callback", limited("login", h.handleGitHubCallback))
	mux.Handle("POST /api/v1/auth/github/complete", limited("login", h.handleGitHubComplete))
	mux.Handle("POST /api/v1/enroll", limited("enroll", h.handleEnroll))

	mux.Handle("POST /api/v1/auth/challenge", limited("challenge", h.handleChallenge))
	mux.Handle("POST /api/v1/auth/token", limited("token", h.handleToken))

	mux.Handle("GET /api/v1/meta", h.authenticated(h.handleMeta))
	mux.Handle("GET /api/v1/status", h.authenticated(h.handleStatus))
	mux.Handle("GET /api/v1/status/{section}", h.authenticated(h.handleSection))
	mux.Handle("GET /api/v1/session", h.authenticated(h.handleSession))
}

// --- discovery --------------------------------------------------------------

type providersResponse struct {
	Providers []string `json:"providers"`
	// EnrollmentCodes reports whether the typed-code path is available at all.
	EnrollmentCodes bool `json:"enrollmentCodes"`
	// PrivilegedRoles and StrongProviders let the app explain, before the user
	// picks a button, that an admin account cannot be bound by a weak method.
	// Publishing the policy is not a leak — it is the same rule the relay will
	// enforce anyway, and stating it up front avoids a confusing 403.
	PrivilegedRoles []string `json:"privilegedRoles"`
	StrongProviders []string `json:"strongProviders"`
}

func (h *Handler) handleProviders(w http.ResponseWriter, r *http.Request) {
	available := make([]string, 0, len(h.Verifiers)+1)
	for name := range h.Verifiers {
		available = append(available, name)
	}
	if h.GitHub != nil {
		available = append(available, "github")
	}
	sortStrings(available)

	policy := h.Devices.Policy()
	httpx.WriteJSON(w, r, http.StatusOK, providersResponse{
		Providers:       available,
		EnrollmentCodes: h.EnrollmentCodesEnabled,
		PrivilegedRoles: policy.PrivilegedRoles,
		StrongProviders: policy.StrongProviders,
	})
}

// --- login nonce ------------------------------------------------------------

type nonceRequest struct {
	PublicKey string `json:"publicKey"`
}

type nonceResponse struct {
	Nonce string `json:"nonce"`
	// NonceHash is what an iOS client passes to Sign in with Apple. Returned so
	// the client does not have to reimplement the hashing convention and get it
	// subtly wrong.
	NonceHash string    `json:"nonceHash"`
	ExpiresAt time.Time `json:"expiresAt"`
	// BindingInput is the exact byte string the device must sign with its
	// Secure Enclave key when completing the binding.
	BindingInput string `json:"bindingInput"`
	Algorithm    string `json:"algorithm"`
}

func (h *Handler) handleNonce(w http.ResponseWriter, r *http.Request) {
	var req nonceRequest
	if err := httpx.DecodeJSON(r, &req); err != nil {
		httpx.WriteError(w, r, http.StatusBadRequest, err.Error())
		return
	}
	der, err := devices.ParsePublicKeyBase64(req.PublicKey)
	if err != nil {
		httpx.WriteError(w, r, http.StatusBadRequest, err.Error())
		return
	}

	now := h.now()
	fingerprint := idp.Fingerprint(der)
	nonce, err := h.LoginNonces.Issue(idp.Payload{DeviceKeyFingerprint: fingerprint}, now)
	if err != nil {
		httpx.WriteError(w, r, http.StatusServiceUnavailable, "cannot start a login right now")
		return
	}

	httpx.WriteJSON(w, r, http.StatusOK, nonceResponse{
		Nonce:        nonce,
		NonceHash:    idp.HashNonce(nonce),
		ExpiresAt:    now.Add(idp.TicketTTL).UTC(),
		BindingInput: string(auth.DeviceBindingInput(nonce, fingerprint)),
		Algorithm:    "ECDSA-P256-SHA256-ASN1",
	})
}

// --- Apple / Google ---------------------------------------------------------

type oidcBindRequest struct {
	Provider   string `json:"provider"`
	IDToken    string `json:"idToken"`
	Nonce      string `json:"nonce"`
	PublicKey  string `json:"publicKey"`
	Signature  string `json:"signature"`
	DeviceName string `json:"deviceName"`
}

type bindResponse struct {
	DeviceID    string   `json:"deviceId"`
	Subject     string   `json:"subject"`
	DisplayName string   `json:"displayName,omitempty"`
	Roles       []string `json:"roles"`
	Scopes      []string `json:"scopes"`
	BoundVia    string   `json:"boundVia"`
	Provider    string   `json:"provider,omitempty"`
}

func (h *Handler) handleOIDCBind(w http.ResponseWriter, r *http.Request) {
	var req oidcBindRequest
	if err := httpx.DecodeJSON(r, &req); err != nil {
		httpx.WriteError(w, r, http.StatusBadRequest, err.Error())
		return
	}
	verifier, ok := h.Verifiers[req.Provider]
	if !ok {
		httpx.WriteError(w, r, http.StatusBadRequest, "this login provider is not enabled on this relay")
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

	now := h.now()
	payload, err := h.LoginNonces.Redeem(req.Nonce, now)
	if err != nil {
		h.denyLogin(r, req.Provider, "nonce_not_valid")
		httpx.WriteError(w, r, http.StatusForbidden, "login could not be completed")
		return
	}
	// The nonce was issued against one device key; a different one now means
	// the identity token is being replayed on behalf of another device.
	if !payload.MatchesKey(der) {
		h.denyLogin(r, req.Provider, "nonce_key_mismatch")
		httpx.WriteError(w, r, http.StatusForbidden, "login could not be completed")
		return
	}
	if err := h.verifyBinding(der, req.Nonce, req.Signature); err != nil {
		h.denyLogin(r, req.Provider, "binding_signature_invalid")
		httpx.WriteError(w, r, http.StatusForbidden, "login could not be completed")
		return
	}

	ctx, cancel := context.WithTimeout(r.Context(), 15*time.Second)
	defer cancel()

	identity, reason, err := verifier.Verify(ctx, req.IDToken, req.Nonce)
	if err != nil {
		h.denyLogin(r, req.Provider, reason)
		httpx.WriteError(w, r, http.StatusForbidden, "login could not be completed")
		return
	}

	h.finishBinding(w, r, identity, der, req.DeviceName, now)
}

// --- GitHub -----------------------------------------------------------------

type githubStartRequest struct {
	PublicKey  string `json:"publicKey"`
	DeviceName string `json:"deviceName"`
}

type githubStartResponse struct {
	AuthorizationURL string `json:"authorizationUrl"`
	State            string `json:"state"`
	BindingInput     string `json:"bindingInput"`
	Algorithm        string `json:"algorithm"`
}

func (h *Handler) handleGitHubStart(w http.ResponseWriter, r *http.Request) {
	if h.GitHub == nil {
		httpx.WriteError(w, r, http.StatusBadRequest, "the GitHub login is not enabled on this relay")
		return
	}
	var req githubStartRequest
	if err := httpx.DecodeJSON(r, &req); err != nil {
		httpx.WriteError(w, r, http.StatusBadRequest, err.Error())
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

	now := h.now()
	fingerprint := idp.Fingerprint(der)
	state, err := h.States.Issue(idp.Payload{
		DeviceKeyFingerprint: fingerprint,
		DeviceName:           req.DeviceName,
	}, now)
	if err != nil {
		httpx.WriteError(w, r, http.StatusServiceUnavailable, "cannot start a login right now")
		return
	}

	httpx.WriteJSON(w, r, http.StatusOK, githubStartResponse{
		AuthorizationURL: h.GitHub.AuthorizeURL(state),
		State:            state,
		// Signed at /complete, over the *ticket*, not the state — the state
		// travels through the browser and must not double as the challenge.
		BindingInput: "issued with the ticket",
		Algorithm:    "ECDSA-P256-SHA256-ASN1",
	})
}

// handleGitHubCallback is the only route a browser ever reaches. It performs
// the code exchange server-side and hands a single-use ticket back to the app
// through its custom URL scheme.
func (h *Handler) handleGitHubCallback(w http.ResponseWriter, r *http.Request) {
	if h.GitHub == nil {
		httpx.WriteError(w, r, http.StatusNotFound, "not found")
		return
	}
	now := h.now()
	state := r.URL.Query().Get("state")
	code := r.URL.Query().Get("code")

	payload, err := h.States.Redeem(state, now)
	if err != nil {
		// The state is unknown, expired or already used. Do not redirect back
		// into the app with an attacker-influenced value; answer here.
		h.denyLogin(r, "github", "state_not_valid")
		httpx.WriteError(w, r, http.StatusForbidden, "login could not be completed")
		return
	}
	if code == "" {
		h.redirectToApp(w, r, h.GitHub.AppErrorRedirect("cancelled"))
		return
	}

	ctx, cancel := context.WithTimeout(r.Context(), 20*time.Second)
	defer cancel()

	identity, reason, err := h.GitHub.Exchange(ctx, code)
	if err != nil {
		h.denyLogin(r, "github", reason)
		h.redirectToApp(w, r, h.GitHub.AppErrorRedirect("verification_failed"))
		return
	}

	ticket, err := h.Tickets.Issue(idp.Payload{
		DeviceKeyFingerprint: payload.DeviceKeyFingerprint,
		DeviceName:           payload.DeviceName,
		Identity:             identity,
	}, now)
	if err != nil {
		h.redirectToApp(w, r, h.GitHub.AppErrorRedirect("temporarily_unavailable"))
		return
	}
	h.redirectToApp(w, r, h.GitHub.AppRedirect(ticket))
}

type githubCompleteRequest struct {
	Ticket     string `json:"ticket"`
	PublicKey  string `json:"publicKey"`
	Signature  string `json:"signature"`
	DeviceName string `json:"deviceName"`
}

func (h *Handler) handleGitHubComplete(w http.ResponseWriter, r *http.Request) {
	if h.GitHub == nil {
		httpx.WriteError(w, r, http.StatusBadRequest, "the GitHub login is not enabled on this relay")
		return
	}
	var req githubCompleteRequest
	if err := httpx.DecodeJSON(r, &req); err != nil {
		httpx.WriteError(w, r, http.StatusBadRequest, err.Error())
		return
	}
	der, err := devices.ParsePublicKeyBase64(req.PublicKey)
	if err != nil {
		httpx.WriteError(w, r, http.StatusBadRequest, err.Error())
		return
	}

	now := h.now()
	payload, err := h.Tickets.Redeem(req.Ticket, now)
	if err != nil || payload.Identity == nil {
		h.denyLogin(r, "github", "ticket_not_valid")
		httpx.WriteError(w, r, http.StatusForbidden, "login could not be completed")
		return
	}
	// The ticket travelled through the browser. Binding it to the device key
	// the flow started with — and demanding a signature from that key — is what
	// stops a ticket lifted from the URL bar being redeemed by anyone else.
	if !payload.MatchesKey(der) {
		h.denyLogin(r, "github", "ticket_key_mismatch")
		httpx.WriteError(w, r, http.StatusForbidden, "login could not be completed")
		return
	}
	if err := h.verifyBinding(der, req.Ticket, req.Signature); err != nil {
		h.denyLogin(r, "github", "binding_signature_invalid")
		httpx.WriteError(w, r, http.StatusForbidden, "login could not be completed")
		return
	}

	name := req.DeviceName
	if name == "" {
		name = payload.DeviceName
	}
	h.finishBinding(w, r, payload.Identity, der, name, now)
}

// finishBinding is the single place a verified identity becomes a device.
func (h *Handler) finishBinding(w http.ResponseWriter, r *http.Request, identity *idp.Identity,
	der []byte, deviceName string, now time.Time) {

	device, err := h.Devices.BindIdentity(identity.Provider, identity.Subject, der, deviceName, nil, now)
	if err != nil {
		status := http.StatusForbidden
		message := "this account is not authorized to use the secman app"
		switch {
		case errors.Is(err, devices.ErrProviderNotAllowed):
			// Worth being specific: the user can act on it, and the rule is
			// already published by /api/v1/providers.
			message = "this account requires a stronger login method; sign in with Apple or Google"
		case errors.Is(err, devices.ErrRegistryFull):
			status = http.StatusServiceUnavailable
			message = "device registry is full; contact an administrator"
		}
		h.Logger.Warn("device binding denied",
			"requestId", httpx.RequestIDFrom(r.Context()),
			"peer", httpx.ClientIPFrom(r.Context()),
			"provider", logging.Sanitize(identity.Provider),
			"identitySubject", logging.Sanitize(identity.Subject),
			"reason", logging.Sanitize(err.Error()),
			"outcome", "denied")
		httpx.WriteError(w, r, status, message)
		return
	}

	resolved, err := h.Devices.Resolve(device.ID)
	if err != nil {
		httpx.WriteError(w, r, http.StatusForbidden, "this account is not authorized to use the secman app")
		return
	}

	h.Logger.Info("device bound",
		"requestId", httpx.RequestIDFrom(r.Context()),
		"peer", httpx.ClientIPFrom(r.Context()),
		"deviceId", device.ID,
		"subject", logging.Sanitize(device.Subject),
		"provider", logging.Sanitize(identity.Provider),
		"roles", resolved.Principal.Roles,
		"outcome", "bound")

	httpx.WriteJSON(w, r, http.StatusCreated, bindResponse{
		DeviceID:    device.ID,
		Subject:     device.Subject,
		DisplayName: resolved.Principal.DisplayName,
		Roles:       resolved.Principal.Roles,
		Scopes:      device.Scopes,
		BoundVia:    string(device.BoundVia),
		Provider:    device.Provider,
	})
}

// --- enrollment code --------------------------------------------------------

type enrollRequest struct {
	EnrollmentCode string `json:"enrollmentCode"`
	PublicKey      string `json:"publicKey"` // base64 SPKI DER, ECDSA P-256
	DeviceName     string `json:"deviceName"`
}

func (h *Handler) handleEnroll(w http.ResponseWriter, r *http.Request) {
	if !h.EnrollmentCodesEnabled {
		httpx.WriteError(w, r, http.StatusBadRequest, "enrollment codes are not enabled on this relay")
		return
	}
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
		switch {
		case errors.Is(err, devices.ErrProviderNotAllowed):
			message = "this account requires a stronger login method; sign in with Apple or Google"
		case errors.Is(err, devices.ErrRegistryFull):
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

	resolved, err := h.Devices.Resolve(device.ID)
	if err != nil {
		httpx.WriteError(w, r, http.StatusForbidden, "this account is not authorized to use the secman app")
		return
	}

	h.Logger.Info("device enrolled",
		"requestId", httpx.RequestIDFrom(r.Context()),
		"peer", httpx.ClientIPFrom(r.Context()),
		"deviceId", device.ID,
		"subject", logging.Sanitize(device.Subject),
		"roles", resolved.Principal.Roles,
		"outcome", "enrolled")

	httpx.WriteJSON(w, r, http.StatusCreated, bindResponse{
		DeviceID:    device.ID,
		Subject:     device.Subject,
		DisplayName: resolved.Principal.DisplayName,
		Roles:       resolved.Principal.Roles,
		Scopes:      device.Scopes,
		BoundVia:    string(device.BoundVia),
	})
}

// --- per-session device authentication --------------------------------------

type challengeRequest struct {
	DeviceID string `json:"deviceId"`
}

type challengeResponse struct {
	Nonce     string    `json:"nonce"`
	ExpiresAt time.Time `json:"expiresAt"`
	// SigningInput removes the guesswork from the client: the device signs
	// SHA-256 of exactly these bytes. Publishing it is not a weakness — the
	// security comes from the private key, not from the format being obscure.
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
	// Resolve rather than merely look up: this re-applies the revocation check,
	// the principal check and the privileged-provider rule before a challenge
	// is even issued.
	if _, err := h.Devices.Resolve(req.DeviceID); err != nil {
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
	Subject     string   `json:"subject"`
	Roles       []string `json:"roles"`
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

	resolved, err := h.Devices.Resolve(req.DeviceID)
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
	pub, err := resolved.Device.PublicKey()
	if err != nil {
		h.Logger.Error("stored device key is unusable",
			"deviceId", resolved.Device.ID, "error", logging.Sanitize(err.Error()))
		httpx.WriteError(w, r, http.StatusForbidden, "device is not authorized")
		return
	}
	if err := auth.VerifyDeviceSignature(pub, req.DeviceID, req.Nonce, req.Signature); err != nil {
		h.logDeviceDenied(r, req.DeviceID, err)
		httpx.WriteError(w, r, http.StatusForbidden, "device is not authorized")
		return
	}

	token, claims, err := h.Tokens.Issue(resolved.Device.ID, resolved.Device.Scopes, now)
	if err != nil {
		httpx.WriteError(w, r, http.StatusInternalServerError, "internal error")
		return
	}
	h.Devices.TouchLastSeen(resolved.Device.ID, now)

	h.Logger.Info("device authenticated",
		"requestId", httpx.RequestIDFrom(r.Context()),
		"peer", httpx.ClientIPFrom(r.Context()),
		"deviceId", resolved.Device.ID,
		"subject", logging.Sanitize(resolved.Device.Subject),
		"roles", resolved.Principal.Roles,
		"jti", claims.JTI,
		"outcome", "token_issued")

	httpx.WriteJSON(w, r, http.StatusOK, tokenResponse{
		AccessToken: token,
		TokenType:   "Bearer",
		ExpiresIn:   int64(h.Tokens.TTL().Seconds()),
		Subject:     resolved.Device.Subject,
		Roles:       resolved.Principal.Roles,
		Scopes:      resolved.Device.Scopes,
	})
}

// --- authenticated reads ----------------------------------------------------

type authedHandler func(w http.ResponseWriter, r *http.Request, resolved *devices.Resolved)

func (h *Handler) authenticated(next authedHandler) http.Handler {
	return httpx.Chain(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		now := h.now()

		raw, ok := auth.BearerFromHeader(r.Header.Get("Authorization"))
		if !ok {
			httpx.WriteError(w, r, http.StatusUnauthorized, "authentication required")
			return
		}
		claims, err := h.Tokens.Verify(raw, now, time.Time{})
		if err != nil {
			h.Logger.Warn("access token rejected",
				"requestId", httpx.RequestIDFrom(r.Context()),
				"peer", httpx.ClientIPFrom(r.Context()),
				"outcome", "denied")
			httpx.WriteError(w, r, http.StatusUnauthorized, "authentication required")
			return
		}
		// Resolve against live state on every request. Roles are read from the
		// principal, never from the token, so a demotion or a revocation pushed
		// by secman one second ago takes effect now rather than when the token
		// would have expired.
		resolved, err := h.Devices.Resolve(claims.DeviceID)
		if err != nil {
			h.logDeviceDenied(r, claims.DeviceID, err)
			httpx.WriteError(w, r, http.StatusForbidden, "device is not authorized")
			return
		}
		if !resolved.Device.TokensValidAfter.IsZero() && claims.IssuedAt <= resolved.Device.TokensValidAfter.Unix() {
			h.logDeviceDenied(r, claims.DeviceID, errors.New("token predates revocation"))
			httpx.WriteError(w, r, http.StatusForbidden, "device is not authorized")
			return
		}

		h.Devices.TouchLastSeen(resolved.Device.ID, now)
		next(w, r, resolved)
	}), httpx.Limit(h.Limiter, "read", h.Logger))
}

type sessionResponse struct {
	DeviceID    string   `json:"deviceId"`
	Subject     string   `json:"subject"`
	DisplayName string   `json:"displayName,omitempty"`
	Roles       []string `json:"roles"`
	Scopes      []string `json:"scopes"`
	BoundVia    string   `json:"boundVia"`
	Provider    string   `json:"provider,omitempty"`
	Sections    []string `json:"sections"`
}

// handleSession tells the app who it is and what it may see, so the UI can be
// built from the server's answer instead of from a client-side guess.
func (h *Handler) handleSession(w http.ResponseWriter, r *http.Request, resolved *devices.Resolved) {
	httpx.WriteJSON(w, r, http.StatusOK, sessionResponse{
		DeviceID:    resolved.Device.ID,
		Subject:     resolved.Device.Subject,
		DisplayName: resolved.Principal.DisplayName,
		Roles:       resolved.Principal.Roles,
		Scopes:      resolved.Device.Scopes,
		BoundVia:    string(resolved.Device.BoundVia),
		Provider:    resolved.Device.Provider,
		Sections:    h.Store.VisibleSections(resolved.Principal.Roles, resolved.Device.Scopes),
	})
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
	Subject       string    `json:"subject"`
	Roles         []string  `json:"roles"`
	Scopes        []string  `json:"scopes"`
}

func (h *Handler) handleMeta(w http.ResponseWriter, r *http.Request, resolved *devices.Resolved) {
	now := h.now()
	meta, err := h.Store.Metadata(now, h.MaxAge)
	if errors.Is(err, store.ErrEmpty) {
		httpx.WriteJSON(w, r, http.StatusServiceUnavailable, map[string]any{
			"error":         "no snapshot has been received yet",
			"deviceId":      resolved.Device.ID,
			"roles":         resolved.Principal.Roles,
			"maxAgeSeconds": int64(h.MaxAge.Seconds()),
		})
		return
	}
	httpx.WriteJSON(w, r, http.StatusOK, metaResponse{
		InstanceID:    meta.InstanceID,
		SchemaVersion: meta.SchemaVersion,
		GeneratedAt:   meta.GeneratedAt.UTC(),
		ReceivedAt:    meta.ReceivedAt.UTC(),
		AgeSeconds:    meta.AgeSeconds,
		Stale:         meta.Stale,
		MaxAgeSeconds: int64(h.MaxAge.Seconds()),
		Sections:      h.Store.VisibleSections(resolved.Principal.Roles, resolved.Device.Scopes),
		DeviceID:      resolved.Device.ID,
		Subject:       resolved.Device.Subject,
		Roles:         resolved.Principal.Roles,
		Scopes:        resolved.Device.Scopes,
	})
}

type statusResponse struct {
	InstanceID    string                     `json:"instanceId"`
	SchemaVersion int                        `json:"schemaVersion"`
	GeneratedAt   time.Time                  `json:"generatedAt"`
	AgeSeconds    int64                      `json:"ageSeconds"`
	Stale         bool                       `json:"stale"`
	Roles         []string                   `json:"roles"`
	Sections      map[string]json.RawMessage `json:"sections"`
}

func (h *Handler) handleStatus(w http.ResponseWriter, r *http.Request, resolved *devices.Resolved) {
	now := h.now()
	meta, sections, err := h.Store.Sections(resolved.Principal.Roles, resolved.Device.Scopes, now, h.MaxAge)
	if errors.Is(err, store.ErrEmpty) {
		httpx.WriteError(w, r, http.StatusServiceUnavailable, "no snapshot has been received yet")
		return
	}
	// ErrStale is not a failure: the payload is returned with stale=true so the
	// app can grey the screen and show the age.
	httpx.WriteJSON(w, r, http.StatusOK, statusResponse{
		InstanceID:    meta.InstanceID,
		SchemaVersion: meta.SchemaVersion,
		GeneratedAt:   meta.GeneratedAt.UTC(),
		AgeSeconds:    meta.AgeSeconds,
		Stale:         meta.Stale,
		Roles:         resolved.Principal.Roles,
		Sections:      sections,
	})
}

func (h *Handler) handleSection(w http.ResponseWriter, r *http.Request, resolved *devices.Resolved) {
	now := h.now()
	section := r.PathValue("section")

	if err := model.ValidateSectionName(section); err != nil {
		httpx.WriteError(w, r, http.StatusBadRequest, "invalid section name")
		return
	}

	raw, allowed, err := h.Store.Section(section, resolved.Principal.Roles, resolved.Device.Scopes, now, h.MaxAge)
	if errors.Is(err, store.ErrEmpty) {
		httpx.WriteError(w, r, http.StatusServiceUnavailable, "no snapshot has been received yet")
		return
	}
	if !allowed {
		// One answer for "not permitted", "out of scope" and "does not exist".
		// Distinguishing them would turn the authorization boundary into a map
		// of what the relay holds.
		h.Logger.Warn("section access denied",
			"requestId", httpx.RequestIDFrom(r.Context()),
			"deviceId", resolved.Device.ID,
			"subject", logging.Sanitize(resolved.Device.Subject),
			"roles", resolved.Principal.Roles,
			"section", logging.Sanitize(section),
			"outcome", "denied")
		httpx.WriteError(w, r, http.StatusForbidden, "not permitted for this account")
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

// --- helpers ----------------------------------------------------------------

func (h *Handler) verifyBinding(der []byte, nonce, signature string) error {
	pub, err := devices.ParsePublicKeyDER(der)
	if err != nil {
		return err
	}
	return auth.VerifyBindingSignature(pub, nonce, idp.Fingerprint(der), signature)
}

// redirectToApp emits the custom-scheme redirect that ends the browser session.
func (h *Handler) redirectToApp(w http.ResponseWriter, r *http.Request, target string) {
	// The target is built entirely from relay-side values (a configured scheme
	// plus a relay-generated ticket), so this is not an open redirect.
	http.Redirect(w, r, target, http.StatusFound)
}

func (h *Handler) denyLogin(r *http.Request, provider, reason string) {
	h.Logger.Warn("login attempt denied",
		"requestId", httpx.RequestIDFrom(r.Context()),
		"peer", httpx.ClientIPFrom(r.Context()),
		"provider", logging.Sanitize(provider),
		"reason", logging.Sanitize(reason),
		"outcome", "denied")
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

func sortStrings(s []string) {
	for i := 1; i < len(s); i++ {
		for j := i; j > 0 && s[j] < s[j-1]; j-- {
			s[j], s[j-1] = s[j-1], s[j]
		}
	}
}
