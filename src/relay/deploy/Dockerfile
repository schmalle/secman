# Build a static secman-relay and ship it in an image that contains nothing
# else. No shell, no package manager, no libc: the attack surface of the
# container is the binary, and the binary has no third-party dependencies.

FROM golang:1.24-alpine AS build
WORKDIR /src
# go.mod has no require block, so there is nothing to download — the build
# works with no module proxy reachable.
COPY go.mod ./
COPY cmd ./cmd
COPY internal ./internal
ARG VERSION=dev
RUN CGO_ENABLED=0 GOOS=linux go build \
      -trimpath \
      -ldflags "-s -w -X main.version=${VERSION}" \
      -o /out/secman-relay ./cmd/secman-relay

FROM scratch
# Root certificates for the outbound calls the relay makes: the ACME directory
# and the identity providers' JWKS endpoints. Nothing else needs them.
COPY --from=build /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/
COPY --from=build /out/secman-relay /secman-relay

# Unprivileged. Port 80 for the ACME HTTP-01 challenge needs either a port above
# 1024 with a redirect in front, or CAP_NET_BIND_SERVICE granted by the runtime.
USER 65532:65532

# The state directory must be a writable volume: it holds the device registry
# and the ACME cache, and losing it means every device re-enrols.
VOLUME ["/var/lib/secman-relay"]
ENV RELAY_STATE_DIR=/var/lib/secman-relay

EXPOSE 8443
ENTRYPOINT ["/secman-relay"]
