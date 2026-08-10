#!/usr/bin/env bash
set -euo pipefail

install -d -o postgres -g postgres -m 0700 /var/lib/safedb-tls
install -o postgres -g postgres -m 0644 /safedb-certs/ca.crt /var/lib/safedb-tls/ca.crt
install -o postgres -g postgres -m 0644 /safedb-certs/server.crt /var/lib/safedb-tls/server.crt
install -o postgres -g postgres -m 0600 /safedb-certs/server.key /var/lib/safedb-tls/server.key

exec /usr/local/bin/docker-entrypoint.sh "$@"
