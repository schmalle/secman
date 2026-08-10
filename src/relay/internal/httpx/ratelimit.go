package httpx

import (
	"log/slog"
	"net/http"
	"strconv"
	"sync"
	"time"

	"github.com/schmalle/secman/src/relay/internal/logging"
)

// RateLimiter is a per-client token bucket.
//
// Hand-rolled rather than golang.org/x/time/rate so the relay keeps its
// zero-dependency property. The algorithm is the standard one: a bucket refills
// at rps tokens per second up to burst, and a request costs one token.
//
// It matters most on the two unauthenticated routes — enroll and
// auth/challenge — where it is the only thing standing between a scanner and
// an unbounded enrollment-code guessing loop.
type RateLimiter struct {
	mu      sync.Mutex
	rps     float64
	burst   float64
	buckets map[string]*bucket
	// maxBuckets bounds memory under a source-address flood. On overflow the
	// limiter sheds the oldest entries rather than growing without limit.
	maxBuckets int
	now        func() time.Time
}

type bucket struct {
	tokens   float64
	lastSeen time.Time
}

// NewRateLimiter builds a limiter.
func NewRateLimiter(rps float64, burst int) *RateLimiter {
	return &RateLimiter{
		rps:        rps,
		burst:      float64(burst),
		buckets:    make(map[string]*bucket),
		maxBuckets: 50_000,
		now:        time.Now,
	}
}

// Allow reports whether a request from key may proceed.
func (l *RateLimiter) Allow(key string) bool {
	now := l.now()

	l.mu.Lock()
	defer l.mu.Unlock()

	b, ok := l.buckets[key]
	if !ok {
		if len(l.buckets) >= l.maxBuckets {
			l.evictLocked(now)
		}
		if len(l.buckets) >= l.maxBuckets {
			// Still full: refuse rather than grow. Under an address flood the
			// relay degrades into "no new clients" instead of into an OOM.
			return false
		}
		b = &bucket{tokens: l.burst, lastSeen: now}
		l.buckets[key] = b
	}

	elapsed := now.Sub(b.lastSeen).Seconds()
	if elapsed > 0 {
		b.tokens += elapsed * l.rps
		if b.tokens > l.burst {
			b.tokens = l.burst
		}
	}
	b.lastSeen = now

	if b.tokens < 1 {
		return false
	}
	b.tokens--
	return true
}

// Sweep drops buckets that have been full and idle. Called on a timer.
func (l *RateLimiter) Sweep() {
	now := l.now()
	l.mu.Lock()
	defer l.mu.Unlock()
	l.evictLocked(now)
}

func (l *RateLimiter) evictLocked(now time.Time) {
	// A bucket idle for long enough to have fully refilled carries no state.
	idle := time.Duration(float64(time.Second) * (l.burst / l.rps))
	if idle < time.Minute {
		idle = time.Minute
	}
	for k, b := range l.buckets {
		if now.Sub(b.lastSeen) > idle {
			delete(l.buckets, k)
		}
	}
}

// Size reports the tracked-client count, for the ops plane.
func (l *RateLimiter) Size() int {
	l.mu.Lock()
	defer l.mu.Unlock()
	return len(l.buckets)
}

// Limit is the middleware form. `cost` names the bucket family so that, for
// example, enrollment attempts and status polls do not share a budget.
func Limit(l *RateLimiter, family string, logger *slog.Logger) Middleware {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			key := family + "|" + ClientIPFrom(r.Context())
			if !l.Allow(key) {
				logger.Warn("rate limit exceeded",
					"requestId", RequestIDFrom(r.Context()),
					"family", family,
					"peer", ClientIPFrom(r.Context()),
					"path", logging.Sanitize(r.URL.Path))
				w.Header().Set("Retry-After", strconv.Itoa(retryAfterSeconds(l.rps)))
				WriteError(w, r, http.StatusTooManyRequests, "too many requests")
				return
			}
			next.ServeHTTP(w, r)
		})
	}
}

func retryAfterSeconds(rps float64) int {
	if rps <= 0 {
		return 60
	}
	s := int(1/rps) + 1
	if s > 60 {
		return 60
	}
	return s
}
