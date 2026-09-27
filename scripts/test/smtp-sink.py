#!/usr/bin/env python3
"""Minimal loopback SMTP sink for isolated E2E tests; discards message bodies."""

import socketserver


class Handler(socketserver.StreamRequestHandler):
    def handle(self):
        self.wfile.write(b"220 secman-e2e-sink\r\n")
        in_data = False
        for raw in self.rfile:
            line = raw.rstrip(b"\r\n")
            if in_data:
                if line == b".":
                    in_data = False
                    self.wfile.write(b"250 accepted\r\n")
                continue
            command = line.split(b" ", 1)[0].upper()
            if command in (b"EHLO", b"HELO"):
                self.wfile.write(b"250 secman-e2e-sink\r\n")
            elif command in (b"MAIL", b"RCPT", b"RSET", b"NOOP"):
                self.wfile.write(b"250 ok\r\n")
            elif command == b"DATA":
                in_data = True
                self.wfile.write(b"354 end with .\r\n")
            elif command == b"QUIT":
                self.wfile.write(b"221 bye\r\n")
                return
            else:
                self.wfile.write(b"502 unsupported\r\n")


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


if __name__ == "__main__":
    with Server(("127.0.0.1", 1925), Handler) as server:
        server.serve_forever()
