package main

import (
	"crypto/tls"
	"crypto/x509"
	"errors"
	"log/slog"
	"net/http"
	"net/url"
	"os"
	"strconv"
	"strings"
	"time"
)

func main() {
	if err := run(); err != nil {
		slog.Error("supplier portal stopped", "error", err)
		os.Exit(1)
	}
}

func run() error {
	publicAddr, integrationAddr := os.Getenv("SUPPLIER_PUBLIC_LISTEN"), os.Getenv("SUPPLIER_INTEGRATION_LISTEN")
	origin, err := httpsOrigin(os.Getenv("SUPPLIER_PUBLIC_ORIGIN"))
	if err != nil || publicAddr == "" || integrationAddr == "" || publicAddr == integrationAddr {
		return errors.New("configure distinct listeners and an HTTPS public origin")
	}
	paused, err := publicPaused(os.Getenv("SUPPLIER_PUBLIC_PAUSED"))
	if err != nil {
		return err
	}
	dir := os.Getenv("SUPPLIER_STATE_DIR")
	if dir == "" {
		return errors.New("SUPPLIER_STATE_DIR is required")
	}
	s, err := openStore(dir)
	if err != nil {
		return err
	}
	defer s.lock.Close()
	publicTLS, err := serverConfig("SUPPLIER_PUBLIC", "")
	if err != nil {
		return err
	}
	clientCA := os.Getenv("SUPPLIER_INTEGRATION_CLIENT_CA")
	if clientCA == "" {
		return errors.New("SUPPLIER_INTEGRATION_CLIENT_CA is required")
	}
	integrationTLS, err := serverConfig("SUPPLIER_INTEGRATION", clientCA)
	if err != nil {
		return err
	}
	integration := server(integrationAddr, integrationHandler(s), integrationTLS)
	if paused {
		slog.Info("supplier portal public listener paused")
		return integration.ListenAndServeTLS("", "")
	}
	public := server(publicAddr, publicHandler(s, origin), publicTLS)
	done := make(chan error, 2)
	go func() { done <- public.ListenAndServeTLS("", "") }()
	go func() { done <- integration.ListenAndServeTLS("", "") }()
	return <-done
}

func publicPaused(value string) (bool, error) {
	if value == "" {
		return false, nil
	}
	paused, err := strconv.ParseBool(value)
	if err != nil {
		return false, errors.New("SUPPLIER_PUBLIC_PAUSED must be a boolean")
	}
	return paused, nil
}

func httpsOrigin(raw string) (string, error) {
	u, err := url.Parse(raw)
	if err != nil || u.Scheme != "https" || u.Hostname() == "" || u.User != nil ||
		u.Path != "" || u.RawQuery != "" || u.ForceQuery || u.Fragment != "" || strings.Contains(raw, "#") {
		return "", errors.New("expected HTTPS origin")
	}
	return raw, nil
}

func serverConfig(prefix, clientCA string) (*tls.Config, error) {
	pair, err := tls.LoadX509KeyPair(os.Getenv(prefix+"_CERT"), os.Getenv(prefix+"_KEY"))
	if err != nil {
		return nil, errors.New(prefix + " TLS certificate or key is unreadable")
	}
	cfg := &tls.Config{MinVersion: tls.VersionTLS12, Certificates: []tls.Certificate{pair}}
	if clientCA != "" {
		pem, err := os.ReadFile(clientCA)
		cfg.ClientCAs = x509.NewCertPool()
		if err != nil || !cfg.ClientCAs.AppendCertsFromPEM(pem) {
			return nil, errors.New("integration client CA is invalid")
		}
		cfg.ClientAuth = tls.RequireAndVerifyClientCert
	}
	return cfg, nil
}

func server(address string, handler http.Handler, config *tls.Config) *http.Server {
	return &http.Server{
		Addr: address, Handler: handler, TLSConfig: config, ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout: 20 * time.Second, WriteTimeout: 30 * time.Second, IdleTimeout: time.Minute,
		MaxHeaderBytes: 16 << 10,
	}
}
