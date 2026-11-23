package main

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/json"
	"io"
	"math/big"
	"net/http"
	"net/http/cookiejar"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"golang.org/x/crypto/bcrypt"
)

func TestSupplierJourney(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "state")
	s, err := openStore(dir)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = s.lock.Close() })
	if duplicate, err := openStore(dir); err == nil {
		_ = duplicate.lock.Close()
		t.Fatal("second writer acquired store")
	}

	public := httptest.NewTLSServer(publicHandler(s, "https://portal.example.test"))
	t.Cleanup(public.Close)
	integration, machine := integrationServer(t, s)
	questionID, _, _ := randomValues()
	id, secret, input := newInvitation(questionID)
	push := "/integration/v1/invitations/" + id

	assertStatus(t, api(t, public.Client(), public.URL+push, http.MethodPut, input), http.StatusNotFound)
	anonymous := integration.Client()
	if response, err := anonymous.Get(integration.URL + push + "/submission"); err == nil {
		response.Body.Close()
		t.Fatal("integration allowed connection without client certificate")
	}
	assertStatus(t, api(t, machine, integration.URL+push, http.MethodPut, input), http.StatusNoContent)
	assertStatus(t, api(t, machine, integration.URL+push, http.MethodPut, input), http.StatusNoContent)

	browser := public.Client()
	browser.Jar, _ = cookiejar.New(nil)
	browser.CheckRedirect = func(_ *http.Request, _ []*http.Request) error { return http.ErrUseLastResponse }
	preview, err := browser.Get(public.URL + "/#" + id + "." + secret)
	if err != nil {
		t.Fatal(err)
	}
	if len(preview.Cookies()) != 0 || strings.Contains(body(t, preview), input.Questionnaire.ServiceScope) {
		t.Fatal("link preview created a session or disclosed questionnaire content")
	}
	assertStatus(t, form(t, browser, public.URL+"/redeem", "https://attacker.example.test", url.Values{"invitation": {id + "." + secret}}), http.StatusForbidden)
	continued := form(t, browser, public.URL+"/redeem", "https://portal.example.test", url.Values{"invitation": {id + "." + secret}})
	if continued.Header.Get("Location") != "/questionnaire" || len(continued.Cookies()) != 1 {
		t.Fatal("redemption did not produce a clean URL and session")
	}
	cookie := continued.Cookies()[0]
	if !cookie.HttpOnly || !cookie.Secure || cookie.SameSite != http.SameSiteStrictMode {
		t.Fatal("session cookie is missing security attributes")
	}
	assertStatus(t, continued, http.StatusSeeOther)
	assertStatus(t, form(t, browser, public.URL+"/questionnaire", "https://portal.example.test", url.Values{"action": {"complete"}, "revision": {"0"}}), http.StatusUnprocessableEntity)
	assertStatus(t, form(t, browser, public.URL+"/questionnaire", "https://portal.example.test", url.Values{
		"action": {"review"}, "revision": {"0"}, "answer-" + questionID: {"N_A"}, "comment-" + questionID: {"<script>text</script>"},
	}), http.StatusSeeOther)
	page, err := browser.Get(public.URL + "/questionnaire?review=1")
	if err != nil {
		t.Fatal(err)
	}
	rendered := body(t, page)
	if strings.Contains(rendered, "<script>text</script>") || !strings.Contains(rendered, "&lt;script&gt;text") {
		t.Fatal("review did not escape supplier text")
	}
	assertStatus(t, form(t, browser, public.URL+"/questionnaire", "https://portal.example.test", url.Values{"action": {"save"}, "revision": {"0"}}), http.StatusConflict)

	complete := url.Values{"action": {"complete"}, "revision": {"1"}}
	assertStatus(t, form(t, browser, public.URL+"/questionnaire", "https://portal.example.test", complete), http.StatusSeeOther)
	first := api(t, machine, integration.URL+push+"/submission", http.MethodGet, nil)
	reference := body(t, first)
	assertStatus(t, form(t, browser, public.URL+"/questionnaire", "https://portal.example.test", complete), http.StatusSeeOther)
	if body(t, api(t, machine, integration.URL+push+"/submission", http.MethodGet, nil)) != reference {
		t.Fatal("duplicate completion changed the receipt")
	}
	if err := s.lock.Close(); err != nil {
		t.Fatal(err)
	}
	reopened, err := openStore(dir)
	if err != nil {
		t.Fatal(err)
	}
	defer reopened.lock.Close()
	restored, err := reopened.load(id)
	if err != nil || restored.Submission == nil || !strings.Contains(reference, restored.Submission.SubmissionID) {
		t.Fatal("submission did not survive restart")
	}
}

func TestReplacementAndTombstone(t *testing.T) {
	s, err := openStore(filepath.Join(t.TempDir(), "state"))
	if err != nil {
		t.Fatal(err)
	}
	defer s.lock.Close()
	key, _, _ := randomValues()
	id, _, first := newInvitation(key)
	if err := applyProvision(s, id, first); err != nil {
		t.Fatal(err)
	}
	record, _ := s.load(id)
	record.Draft = []answer{{key, "YES", "Saved"}}
	record.Revision = 1
	if err := s.save(id, record); err != nil {
		t.Fatal(err)
	}
	nextID, _, next := newInvitation(key)
	next.PreviousInvitationID, next.RetainDraft = id, true
	next.Questionnaire.Generation = 2
	if err := applyProvision(s, nextID, next); err != nil {
		t.Fatal(err)
	}
	replaced, _ := s.load(nextID)
	old, _ := s.load(id)
	if !old.Revoked || replaced.Revision != 1 || len(replaced.Draft) != 1 {
		t.Fatal("reissue did not revoke the old link and retain the same-contact draft")
	}
	thirdID, _, third := newInvitation(key)
	third.PreviousInvitationID = nextID
	third.Questionnaire.Generation = 3
	if err := applyProvision(s, thirdID, third); err != nil {
		t.Fatal(err)
	}
	fresh, _ := s.load(thirdID)
	if len(fresh.Draft) != 0 {
		t.Fatal("replacement contact inherited a draft")
	}
	if err := revoke(s, thirdID); err != nil {
		t.Fatal(err)
	}
	if err := applyProvision(s, thirdID, third); err != errConflict {
		t.Fatal("late provisioning recreated a revoked invitation")
	}
}

func TestRestoreReconcilesOutstandingInvitations(t *testing.T) {
	backup, err := openStore(filepath.Join(t.TempDir(), "backup"))
	if err != nil {
		t.Fatal(err)
	}
	defer backup.lock.Close()
	key, _, _ := randomValues()
	oldID, oldSecret, original := newInvitation(key)
	if err := applyProvision(backup, oldID, original); err != nil {
		t.Fatal(err)
	}
	oldIntegration, oldMachine := integrationServer(t, backup)
	assertStatus(t, api(t, oldMachine, oldIntegration.URL+"/integration/v1/invitations/"+oldID+"/submission", http.MethodGet, nil), http.StatusNoContent)
	content, err := os.ReadFile(filepath.Join(backup.dir, oldID+".json"))
	if err != nil {
		t.Fatal(err)
	}
	restored, err := openStore(filepath.Join(t.TempDir(), "restored"))
	if err != nil {
		t.Fatal(err)
	}
	defer restored.lock.Close()
	if err := os.WriteFile(filepath.Join(restored.dir, oldID+".json"), content, 0600); err != nil {
		t.Fatal(err)
	}

	integration, machine := integrationServer(t, restored)
	target := integration.URL + "/integration/v1/invitations/" + oldID
	if response, err := oldMachine.Get(target + "/submission"); err == nil {
		response.Body.Close()
		t.Fatal("restored integration accepted the retired client identity")
	}
	assertStatus(t, api(t, machine, target+"/revocation", http.MethodPut, nil), http.StatusNoContent)
	assertStatus(t, api(t, machine, target, http.MethodPut, original), http.StatusConflict)
	newID, newSecret, replacement := newInvitation(key)
	replacement.PreviousInvitationID = oldID
	replacement.Questionnaire.Generation = 2
	replacement.Questionnaire.ServiceScope = "Replacement engagement"
	assertStatus(t, api(t, machine, integration.URL+"/integration/v1/invitations/"+newID, http.MethodPut, replacement), http.StatusNoContent)

	public := httptest.NewTLSServer(publicHandler(restored, "https://portal.example.test"))
	t.Cleanup(public.Close)
	browser := public.Client()
	browser.Jar, _ = cookiejar.New(nil)
	browser.CheckRedirect = func(_ *http.Request, _ []*http.Request) error { return http.ErrUseLastResponse }
	redeem := func(id, secret string) *http.Response {
		return form(t, browser, public.URL+"/redeem", "https://portal.example.test", url.Values{"invitation": {id + "." + secret}})
	}
	assertStatus(t, redeem(oldID, oldSecret), http.StatusNotFound)
	assertStatus(t, redeem(newID, oldSecret), http.StatusNotFound)
	assertStatus(t, redeem(newID, newSecret), http.StatusSeeOther)
	page, err := browser.Get(public.URL + "/questionnaire?invitation=" + oldID)
	if err != nil {
		t.Fatal(err)
	}
	if rendered := body(t, page); !strings.Contains(rendered, "Replacement engagement") || strings.Contains(rendered, "Payroll service") {
		t.Fatal("the replacement session could read the old engagement")
	}
}

func TestPublicPauseConfiguration(t *testing.T) {
	if paused, err := publicPaused("true"); err != nil || !paused {
		t.Fatal("public pause was not enabled")
	}
	if paused, err := publicPaused(""); err != nil || paused {
		t.Fatal("missing pause config did not preserve normal startup")
	}
	if _, err := publicPaused("invalid"); err == nil {
		t.Fatal("invalid configuration did not fail closed")
	}
}

func TestSameOriginWithNoReferrer(t *testing.T) {
	p := publicPortal{origin: "https://portal.example.test"}
	for _, test := range []struct {
		origin, site string
		allowed      bool
	}{
		{"null", "same-origin", true},
		{"null", "same-site", false},
		{"null", "cross-site", false},
		{"null", "", false},
		{p.origin, "", true},
		{p.origin, "cross-site", false},
		{"https://other.example.test", "same-origin", false},
	} {
		r := httptest.NewRequest(http.MethodPost, p.origin+"/redeem", nil)
		r.Header.Set("Origin", test.origin)
		r.Header.Set("Sec-Fetch-Site", test.site)
		if actual := p.sameOrigin(r); actual != test.allowed {
			t.Errorf("origin %q and site %q allowed = %t, want %t", test.origin, test.site, actual, test.allowed)
		}
	}
}

func newInvitation(questionID string) (string, string, provision) {
	id, secret, _ := randomValues()
	digest, err := bcrypt.GenerateFromPassword([]byte(secret), bcrypt.DefaultCost)
	if err != nil {
		panic(err)
	}
	input := provision{Questionnaire: questionnaire{
		SchemaVersion: 1, InvitationID: id, Generation: 1, RequesterLabel: "Procurement",
		ServiceScope: "Payroll service", Deadline: time.Now().UTC().Add(time.Hour),
		SnapshotHash: strings.Repeat("a", 64), Questions: []question{{questionID, "Is access reviewed?"}},
	}, Verifier: string(digest), ExpiresAt: time.Now().UTC().Add(30 * time.Minute)}
	return id, secret, input
}

func integrationServer(t *testing.T, s *store) (*httptest.Server, *http.Client) {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	ca := &x509.Certificate{
		SerialNumber: big.NewInt(1), NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(time.Hour),
		Subject: pkix.Name{CommonName: "Supplier test CA"},
		IsCA:    true, BasicConstraintsValid: true, KeyUsage: x509.KeyUsageCertSign | x509.KeyUsageDigitalSignature,
	}
	caDER, err := x509.CreateCertificate(rand.Reader, ca, ca, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	root, err := x509.ParseCertificate(caDER)
	if err != nil {
		t.Fatal(err)
	}
	leaf := &x509.Certificate{
		SerialNumber: big.NewInt(2), NotBefore: ca.NotBefore, NotAfter: ca.NotAfter,
		Subject:  pkix.Name{CommonName: "Supplier test worker"},
		KeyUsage: x509.KeyUsageDigitalSignature, ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageClientAuth},
	}
	leafKey, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	leafDER, err := x509.CreateCertificate(rand.Reader, leaf, root, &leafKey.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	pool := x509.NewCertPool()
	pool.AddCert(root)
	server := httptest.NewUnstartedServer(integrationHandler(s))
	server.TLS = &tls.Config{MinVersion: tls.VersionTLS12, ClientAuth: tls.RequireAndVerifyClientCert, ClientCAs: pool}
	server.StartTLS()
	t.Cleanup(server.Close)
	transport := server.Client().Transport.(*http.Transport).Clone()
	transport.TLSClientConfig.Certificates = []tls.Certificate{{Certificate: [][]byte{leafDER}, PrivateKey: leafKey}}
	return server, &http.Client{Transport: transport}
}

func api(t *testing.T, client *http.Client, target, method string, payload any) *http.Response {
	t.Helper()
	data, err := json.Marshal(payload)
	if err != nil {
		t.Fatal(err)
	}
	request, err := http.NewRequest(method, target, strings.NewReader(string(data)))
	if err != nil {
		t.Fatal(err)
	}
	resp, err := client.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	return resp
}

func form(t *testing.T, client *http.Client, target, origin string, fields url.Values) *http.Response {
	t.Helper()
	req, err := http.NewRequest(http.MethodPost, target, strings.NewReader(fields.Encode()))
	if err != nil {
		t.Fatal(err)
	}
	req.Header.Set("Origin", origin)
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	response, err := client.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	return response
}

func body(t *testing.T, response *http.Response) string {
	t.Helper()
	defer response.Body.Close()
	data, err := io.ReadAll(response.Body)
	if err != nil {
		t.Fatal(err)
	}
	return string(data)
}

func assertStatus(t *testing.T, response *http.Response, status int) {
	t.Helper()
	text := body(t, response)
	if response.StatusCode != status {
		t.Fatalf("status %d, want %d (%s)", response.StatusCode, status, text)
	}
}
