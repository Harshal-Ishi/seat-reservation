#!/usr/bin/env bash
# One-command on-sale stampede against a running service, with PASS/FAIL checks.
#   ./burst.sh <BASE_URL> [--requests 20000] [--users 3000] [--seats 1000] [--concurrency 1000] [--admin-secret S]
# The admin secret can also come from the ADMIN_SECRET environment variable (default: the local compose value).
set -euo pipefail

if [[ $# -lt 1 ]]; then
  echo "usage: ./burst.sh <BASE_URL> [options]   e.g. ./burst.sh http://localhost:8080" >&2
  exit 2
fi
BASE_URL="$1"; shift
DIR="$(cd "$(dirname "$0")" && pwd)"

java_major() {
  java -version 2>&1 | sed -nE 's/.*version "([0-9]+).*/\1/p' | head -1
}

if command -v java >/dev/null 2>&1 && [[ "$(java_major)" -ge 21 ]]; then
  exec java "$DIR/burst/Burst.java" "$BASE_URL" "$@"
fi

# No local JDK 21+: run the same file in a JDK container. Inside the container "localhost" is the container
# itself, so a local target is rewritten to the host.
echo "No local JDK 21+ found; running in Docker (eclipse-temurin:21-jdk)." >&2
DOCKER_URL="${BASE_URL/localhost/host.docker.internal}"
DOCKER_URL="${DOCKER_URL/127.0.0.1/host.docker.internal}"
exec docker run --rm --add-host=host.docker.internal:host-gateway -e ADMIN_SECRET \
  -v "$DIR/burst:/burst:ro" eclipse-temurin:21-jdk java /burst/Burst.java "$DOCKER_URL" "$@"
