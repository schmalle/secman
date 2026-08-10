module github.com/schmalle/secman/src/relay

// The relay is an internet-facing process in a DMZ. It is deliberately
// dependency-free: everything below is the Go standard library, so the
// binary's supply chain is the Go release itself and `go build` needs no
// module proxy. See README.md "No third-party dependencies".
go 1.24
