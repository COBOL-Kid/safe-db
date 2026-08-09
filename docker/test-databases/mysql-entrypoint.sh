#!/usr/bin/env bash
set -euo pipefail

install -d -o mysql -g mysql -m 0700 /var/lib/safedb-tls
install -o mysql -g mysql -m 0644 /safedb-certs/ca.crt /var/lib/safedb-tls/ca.crt
install -o mysql -g mysql -m 0644 /safedb-certs/server.crt /var/lib/safedb-tls/server.crt
install -o mysql -g mysql -m 0600 /safedb-certs/server.key /var/lib/safedb-tls/server.key

exec /usr/local/bin/docker-entrypoint.sh "$@"
