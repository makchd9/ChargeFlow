#!/usr/bin/env bash
# One-command launcher for macOS / Linux: checks Java 17+, then starts ChargeFlow on http://localhost:8080
set -e
cd "$(dirname "$0")"

if ! command -v java >/dev/null 2>&1; then
  # Homebrew installs OpenJDK "keg-only"; pick it up if present
  for d in /opt/homebrew/opt/openjdk@21 /opt/homebrew/opt/openjdk /usr/local/opt/openjdk@21 /usr/local/opt/openjdk; do
    [ -x "$d/bin/java" ] && export PATH="$d/bin:$PATH" && break
  done
fi
if ! command -v java >/dev/null 2>&1; then
  echo "Java 17+ is required. On macOS:  brew install openjdk@21   (then re-run ./run.sh)"
  exit 1
fi
major=$(java -version 2>&1 | head -1 | sed -E 's/.*version "([0-9]+).*/\1/')
if [ "${major:-0}" -lt 17 ]; then
  echo "Found Java $major, but ChargeFlow needs Java 17 or newer:  brew install openjdk@21"
  exit 1
fi

echo "Starting ChargeFlow (Java $major). The first run downloads Maven + libraries (1-2 min)."
echo "The browser opens automatically once the server is ready. Ctrl+C to stop."
# open the browser only when the server actually answers (waits up to 5 minutes)
( for i in $(seq 1 150); do
    if curl -s -o /dev/null http://localhost:8080/api/health; then
      echo ">>> ChargeFlow is ready: http://localhost:8080"
      command -v open >/dev/null && open http://localhost:8080
      exit 0
    fi
    sleep 2
  done ) &
exec ./mvnw -q spring-boot:run
