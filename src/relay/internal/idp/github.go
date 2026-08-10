package idp

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"
)

// GitHub has no OpenID Connect ID token, so the relay runs the OAuth 2.0
// authorization-code flow as a **confidential client**: the client secret lives
// on the relay and never reaches the device, and the short-lived GitHub access
// token is used once, server-side, to read the account id and is then dropped.
//
// The app therefore never holds a GitHub credential of any kind. What it gets
// back is a single-use binding ticket that is worthless without the device key
// it was issued against.
//
// PKCE is not used because GitHub's OAuth Apps do not support it; the
// protection that matters for a confidential client — an unguessable, single-use
// `state` bound to the requesting device key — is present and is checked before
// the code is ever exchanged.

const (
	githubAuthorizeURL = "https://github.com/login/oauth/authorize"
	githubTokenURL     = "https://github.com/login/oauth/access_token"
	githubUserURL      = "https://api.github.com/user"
	maxGitHubResponse  = 256 << 10
)

// GitHubConfig configures the OAuth client.
type GitHubConfig struct {
	ClientID     string
	ClientSecret string
	// RedirectURI must exactly match the callback registered on the GitHub
	// OAuth App, and must be an https URL on the relay itself.
	RedirectURI string
	// AppCallbackScheme is the app's custom URL scheme, e.g. "secman-relay".
	// The browser session is handed back to the app at
	// "<scheme>://auth/github?ticket=…".
	AppCallbackScheme string
}

// Validate checks the configuration at boot.
func (c GitHubConfig) Validate() error {
	if c.ClientID == "" || c.ClientSecret == "" {
		return errors.New("idp: GitHub client id and secret are both required")
	}
	if err := ValidateProviderURL(c.RedirectURI); err != nil {
		return fmt.Errorf("idp: GitHub redirect URI: %w", err)
	}
	if err := ValidateCallbackScheme(c.AppCallbackScheme); err != nil {
		return err
	}
	return nil
}

// ValidateCallbackScheme constrains the app's custom URL scheme.
//
// The scheme is interpolated into a Location header, so it is validated rather
// than trusted: an unconstrained value is a response-splitting and open-redirect
// primitive.
func ValidateCallbackScheme(scheme string) error {
	if scheme == "" {
		return errors.New("idp: the app callback scheme is required")
	}
	if len(scheme) > 64 {
		return errors.New("idp: the app callback scheme is too long")
	}
	if scheme == "http" || scheme == "https" {
		return errors.New("idp: the app callback scheme must be a custom scheme, not http(s)")
	}
	for i, r := range scheme {
		isAlpha := (r >= 'a' && r <= 'z')
		isDigit := r >= '0' && r <= '9'
		if i == 0 && !isAlpha {
			return errors.New("idp: the app callback scheme must start with a lowercase letter")
		}
		if !isAlpha && !isDigit && r != '-' && r != '.' && r != '+' {
			return errors.New("idp: the app callback scheme contains an invalid character")
		}
	}
	return nil
}

// GitHubClient performs the server side of the flow.
type GitHubClient struct {
	cfg  GitHubConfig
	http *http.Client
	// Endpoint overrides exist only so the test suite can point the flow at a
	// local mock. There is no exported way to set them, so a production build
	// always talks to github.com.
	authorizeURL string
	tokenURL     string
	userURL      string
}

// NewGitHubClient builds a client.
func NewGitHubClient(cfg GitHubConfig, client *http.Client) (*GitHubClient, error) {
	if err := cfg.Validate(); err != nil {
		return nil, err
	}
	if client == nil {
		return nil, errors.New("idp: an HTTP client is required")
	}
	return &GitHubClient{
		cfg:          cfg,
		http:         client,
		authorizeURL: githubAuthorizeURL,
		tokenURL:     githubTokenURL,
		userURL:      githubUserURL,
	}, nil
}

// AuthorizeURL builds the URL the app opens in ASWebAuthenticationSession.
//
// `read:user` is the narrowest scope that returns the account id. The relay
// never asks for repository, organisation or email write access, because it
// only needs to answer "which GitHub account is this".
func (c *GitHubClient) AuthorizeURL(state string) string {
	q := url.Values{}
	q.Set("client_id", c.cfg.ClientID)
	q.Set("redirect_uri", c.cfg.RedirectURI)
	q.Set("scope", "read:user")
	q.Set("state", state)
	q.Set("allow_signup", "false")
	return c.authorizeURL + "?" + q.Encode()
}

// AppRedirect builds the URL that hands control back to the app.
func (c *GitHubClient) AppRedirect(ticket string) string {
	// ticket is relay-generated hex, but encode anyway: building a URL by
	// concatenation is how open redirects start.
	return c.cfg.AppCallbackScheme + "://auth/github?ticket=" + url.QueryEscape(ticket)
}

// AppErrorRedirect hands a failure back to the app without leaking detail.
func (c *GitHubClient) AppErrorRedirect(reason string) string {
	return c.cfg.AppCallbackScheme + "://auth/github?error=" + url.QueryEscape(reason)
}

// Exchange trades an authorization code for the caller's GitHub identity.
//
// The access token is used exactly once, here, and never leaves this function.
func (c *GitHubClient) Exchange(ctx context.Context, code string) (*Identity, string, error) {
	if code == "" || len(code) > 512 {
		return nil, "bad_code", ErrVerification
	}

	form := url.Values{}
	form.Set("client_id", c.cfg.ClientID)
	form.Set("client_secret", c.cfg.ClientSecret)
	form.Set("code", code)
	form.Set("redirect_uri", c.cfg.RedirectURI)

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.tokenURL, strings.NewReader(form.Encode()))
	if err != nil {
		return nil, "build_token_request", ErrVerification
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.Header.Set("Accept", "application/json")

	resp, err := c.http.Do(req)
	if err != nil {
		return nil, "token_request_failed", ErrVerification
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(io.LimitReader(resp.Body, maxGitHubResponse))
	if err != nil {
		return nil, "token_read_failed", ErrVerification
	}
	if resp.StatusCode != http.StatusOK {
		return nil, "token_status_" + strconv.Itoa(resp.StatusCode), ErrVerification
	}

	var token struct {
		AccessToken string `json:"access_token"`
		TokenType   string `json:"token_type"`
		Scope       string `json:"scope"`
		Error       string `json:"error"`
	}
	if err := json.Unmarshal(body, &token); err != nil {
		return nil, "token_not_json", ErrVerification
	}
	if token.Error != "" || token.AccessToken == "" {
		return nil, "token_rejected", ErrVerification
	}

	return c.fetchUser(ctx, token.AccessToken)
}

func (c *GitHubClient) fetchUser(ctx context.Context, accessToken string) (*Identity, string, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, c.userURL, nil)
	if err != nil {
		return nil, "build_user_request", ErrVerification
	}
	req.Header.Set("Authorization", "Bearer "+accessToken)
	req.Header.Set("Accept", "application/vnd.github+json")
	req.Header.Set("X-GitHub-Api-Version", "2022-11-28")

	resp, err := c.http.Do(req)
	if err != nil {
		return nil, "user_request_failed", ErrVerification
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(io.LimitReader(resp.Body, maxGitHubResponse))
	if err != nil {
		return nil, "user_read_failed", ErrVerification
	}
	if resp.StatusCode != http.StatusOK {
		return nil, "user_status_" + strconv.Itoa(resp.StatusCode), ErrVerification
	}

	var user struct {
		ID    int64  `json:"id"`
		Login string `json:"login"`
		Name  string `json:"name"`
		Email string `json:"email"`
	}
	if err := json.Unmarshal(body, &user); err != nil {
		return nil, "user_not_json", ErrVerification
	}
	if user.ID == 0 {
		return nil, "user_has_no_id", ErrVerification
	}

	// The numeric id is the subject, never the login. A login can be renamed
	// and — once released — claimed by somebody else, which would silently
	// transfer a principal's access to a stranger.
	return &Identity{
		Provider:    "github",
		Subject:     strconv.FormatInt(user.ID, 10),
		Email:       user.Email,
		DisplayName: firstNonEmpty(user.Name, user.Login),
	}, "", nil
}

// StateTTL is how long a GitHub browser round trip may take.
const StateTTL = 10 * time.Minute

// TicketTTL is how long the app has to redeem a binding ticket after the
// browser hands control back. Short: the app redeems it immediately.
const TicketTTL = 2 * time.Minute

func firstNonEmpty(values ...string) string {
	for _, v := range values {
		if v != "" {
			return v
		}
	}
	return ""
}
