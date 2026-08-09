// Command secman-relay is the zero-trust relay that sits between secman and
// the secman mobile apps.
//
// It receives signed, authenticated pushes from a secman instance, keeps the
// most recent snapshot in memory, and serves it read-only to enrolled mobile
// devices. It never connects to secman, never holds a secman credential, and
// has no write path into anything.
//
// See ../../README.md and ../../../../docs/RELAY.md.
package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"io"
	"log"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/schmalle/secman/src/relay/internal/api"
	"github.com/schmalle/secman/src/relay/internal/auth"
	"github.com/schmalle/secman/src/relay/internal/config"
	"github.com/schmalle/secman/src/relay/internal/devices"
	"github.com/schmalle/secman/src/relay/internal/httpx"
	"github.com/schmalle/secman/src/relay/internal/ingest"
	"github.com/schmalle/secman/src/relay/internal/logging"
	"github.com/schmalle/secman/src/relay/internal/store"
	"github.com/schmalle/secman/src/relay/internal/tlsx"
)

// version is stamped at build time:
//
//	go build -ldflags "-X main.version=$(git describe --tags --always)"
var version = "dev"

func main() {
	showVersion := flag.Bool("version", false, "print the version and exit")
	checkConfig := flag.Bool("check-config", false, "validate the configuration and exit without listening")
	flag.Parse()

	if *showVersion {
		fmt.Println("secman-relay", version)
		return
	}

	if err := run(*checkConfig); err != nil {
		// Config and startup errors are written plainly: at this point the
		// structured logger may not exist yet, and an operator needs the text.
		fmt.Fprintln(os.Stderr, "secman-relay: "+err.Error())
		os.Exit(1)
	}
}

func run(checkConfigOnly bool) error {
	cfg, err := config.Load(config.OSGetenv)
	if err != nil {
		return fmt.Errorf("configuration is not usable:\n%w", err)
	}

	logger := logging.New(os.Stdout, cfg.LogLevel)
	logger.Info("secman-relay starting", "version", version, "config", cfg.Redacted(),
		"ingestTokenFingerprint", logging.Fingerprint(cfg.Ingest.Token))

	if checkConfigOnly {
		logger.Info("configuration is valid", "checkConfigOnly", true)
		return nil
	}

	registry, err := devices.Open(cfg.StateDir, cfg.Device.MaxDevices)
	if err != nil {
		return fmt.Errorf("opening the device registry: %w", err)
	}
	snapshots := store.New()

	verifier, err := auth.NewIngestVerifier(cfg.Ingest.Token, cfg.Ingest.HMACKey, cfg.Ingest.MaxClockSkew)
	if err != nil {
		return err
	}
	tokens, err := auth.NewTokenIssuer(cfg.Device.TokenSigningKey, cfg.Device.TokenTTL)
	if err != nil {
		return err
	}
	challenges := auth.NewChallengeStore(cfg.Device.ChallengeTTL)
	limiter := httpx.NewRateLimiter(cfg.Limits.RateLimitRPS, cfg.Limits.RateLimitBurst)

	certManager, err := tlsx.New(cfg.TLS, logger)
	if err != nil {
		return err
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	if err := certManager.Start(ctx); err != nil {
		return err
	}
	if !certManager.Enabled() {
		logger.Warn("serving plaintext HTTP",
			"reason", "RELAY_TLS_MODE=off",
			"requirement", "TLS must be terminated immediately in front of this process and the hop to it must not be observable")
	}

	apiHandler := &api.Handler{
		Store:      snapshots,
		Devices:    registry,
		Tokens:     tokens,
		Challenges: challenges,
		Limiter:    limiter,
		Logger:     logger,
		MaxAge:     cfg.Limits.SnapshotMaxAge,
	}
	ingestHandler := &ingest.Handler{
		Verifier: verifier,
		Store:    snapshots,
		Devices:  registry,
		Logger:   logger,
		MaxBody:  cfg.Limits.MaxBodyBytes,
	}

	publicMux := http.NewServeMux()
	apiHandler.Routes(publicMux)
	registerOps(publicMux, snapshots, registry, certManager, cfg)

	var servers []*http.Server

	if cfg.IngestListenAddr == "" {
		// Single-listener topology: the ingest plane rides on the public port
		// and is protected by token + HMAC + the optional CIDR allowlist.
		ingestMux := http.NewServeMux()
		ingestHandler.Routes(ingestMux)
		publicMux.Handle("/ingest/", httpx.Chain(ingestMux,
			httpx.AllowCIDRs(cfg.Ingest.AllowedCIDRs, logger)))
	} else {
		// Split-listener topology: bind the ingest plane to an interface the
		// internet cannot reach. This is the recommended production shape.
		ingestMux := http.NewServeMux()
		ingestHandler.Routes(ingestMux)
		ingestMux.HandleFunc("GET /healthz", func(w http.ResponseWriter, r *http.Request) {
			httpx.WriteJSON(w, r, http.StatusOK, map[string]string{"status": "ok"})
		})
		servers = append(servers, newServer(cfg, cfg.IngestListenAddr, httpx.Chain(ingestMux,
			httpx.RequestID(),
			httpx.ResolveClientIP(cfg.TrustedProxyCIDRs),
			httpx.Recover(logger),
			httpx.AccessLog(logger),
			httpx.SecurityHeaders(certManager.Enabled()),
			httpx.MaxBody(cfg.Limits.MaxBodyBytes),
			httpx.AllowCIDRs(cfg.Ingest.AllowedCIDRs, logger),
		), certManager))
	}

	publicHandler := httpx.Chain(publicMux,
		httpx.RequestID(),
		httpx.ResolveClientIP(cfg.TrustedProxyCIDRs),
		httpx.Recover(logger),
		httpx.AccessLog(logger),
		httpx.SecurityHeaders(certManager.Enabled()),
		httpx.NoCORS(),
		httpx.MaxBody(cfg.Limits.MaxBodyBytes),
	)
	servers = append(servers, newServer(cfg, cfg.ListenAddr, publicHandler, certManager))

	// ACME HTTP-01 needs port 80 reachable from the internet. It serves the
	// challenge path and redirects everything else to https; it is never an API.
	if h := certManager.HTTP01Handler(); h != nil {
		challengeServer := &http.Server{
			Addr:              cfg.TLS.ACMEHTTP01Addr,
			Handler:           h,
			ReadHeaderTimeout: 10 * time.Second,
			ReadTimeout:       15 * time.Second,
			WriteTimeout:      15 * time.Second,
			IdleTimeout:       30 * time.Second,
			ErrorLog:          quietErrorLog(),
		}
		servers = append(servers, challengeServer)
	}

	go maintenance(ctx, verifier, challenges, registry, limiter)

	errCh := make(chan error, len(servers))
	for _, srv := range servers {
		go func(s *http.Server) {
			logger.Info("listener started", "addr", s.Addr, "tls", s.TLSConfig != nil)
			var listenErr error
			if s.TLSConfig != nil {
				listenErr = s.ListenAndServeTLS("", "")
			} else {
				listenErr = s.ListenAndServe()
			}
			if listenErr != nil && !errors.Is(listenErr, http.ErrServerClosed) {
				errCh <- fmt.Errorf("listener %s: %w", s.Addr, listenErr)
				return
			}
			errCh <- nil
		}(srv)
	}

	select {
	case <-ctx.Done():
		logger.Info("shutdown signal received")
	case err := <-errCh:
		if err != nil {
			shutdown(context.Background(), servers, cfg.Limits.ShutdownTimeout, logger)
			return err
		}
	}

	shutdown(context.Background(), servers, cfg.Limits.ShutdownTimeout, logger)
	logger.Info("secman-relay stopped")
	return nil
}

func newServer(cfg *config.Config, addr string, handler http.Handler, certManager *tlsx.Manager) *http.Server {
	return &http.Server{
		Addr:    addr,
		Handler: handler,
		// ReadHeaderTimeout is the Slowloris control; the others bound how long
		// any single connection can hold a goroutine.
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       cfg.Limits.ReadTimeout,
		WriteTimeout:      cfg.Limits.WriteTimeout,
		IdleTimeout:       cfg.Limits.IdleTimeout,
		MaxHeaderBytes:    1 << 16,
		TLSConfig:         certManager.TLSConfig(),
		ErrorLog:          quietErrorLog(),
	}
}

// registerOps adds the liveness and readiness endpoints.
//
// Neither requires authentication — a load balancer health check cannot carry a
// credential — so neither returns anything sensitive: no version, no domain
// list, no counters, no snapshot age. The detailed status lives on the
// authenticated ingest plane (GET /ingest/v1/status), where secman reads it.
func registerOps(mux *http.ServeMux, snapshots *store.Store, registry *devices.Registry, certManager *tlsx.Manager, cfg *config.Config) {
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, r *http.Request) {
		httpx.WriteJSON(w, r, http.StatusOK, map[string]string{"status": "ok"})
	})

	// Readiness deliberately does NOT depend on having a snapshot. If it did,
	// a relay behind an ALB would be pulled out of the target group before
	// secman's first push and could then never receive one — a deadlock that
	// only shows up in production.
	mux.HandleFunc("GET /readyz", func(w http.ResponseWriter, r *http.Request) {
		ready := true
		if certManager.Enabled() {
			if _, err := certManager.GetCertificate(nil); err != nil {
				ready = false
			}
		}
		status := http.StatusOK
		if !ready {
			status = http.StatusServiceUnavailable
		}
		httpx.WriteJSON(w, r, status, map[string]bool{"ready": ready})
	})
	_ = snapshots
	_ = registry
	_ = cfg
}

// maintenance sweeps the bounded in-memory caches. Each of them is already
// self-limiting; this keeps a quiet relay from holding expired entries for the
// lifetime of the process.
func maintenance(ctx context.Context, verifier *auth.IngestVerifier, challenges *auth.ChallengeStore,
	registry *devices.Registry, limiter *httpx.RateLimiter) {
	ticker := time.NewTicker(time.Minute)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case now := <-ticker.C:
			verifier.SweepNonces(now)
			challenges.Sweep(now)
			registry.Prune(now)
			limiter.Sweep()
		}
	}
}

func shutdown(ctx context.Context, servers []*http.Server, timeout time.Duration, logger *slog.Logger) {
	shutdownCtx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	for _, s := range servers {
		if err := s.Shutdown(shutdownCtx); err != nil {
			logger.Warn("listener did not shut down cleanly", "addr", s.Addr,
				"error", logging.Sanitize(err.Error()))
		}
	}
}

// quietErrorLog silences net/http's default logger, which writes unstructured
// lines to stderr — including, on a TLS handshake failure, the peer address.
// Everything worth recording is already emitted by the access log.
func quietErrorLog() *log.Logger {
	return log.New(io.Discard, "", 0)
}
