#!/usr/bin/env bash
# One-command launcher for macOS / Linux: checks Java 17+, then starts ChargeFlow and opens the browser.
# Usage: bash run.sh            (optional: PORT=9090 bash run.sh)
set -e
cd "$(dirname "$0")"
PORT="${PORT:-8080}"
URL="http://localhost:$PORT"

# true only if ChargeFlow itself answers (not just any web server on that port)
is_chargeflow_up() { curl -s --max-time 2 "$URL/api/health" 2>/dev/null | grep -q '"UP"'; }
# true if anything is listening on the port (bash built-in, no lsof needed)
port_busy() { (exec 3<>"/dev/tcp/127.0.0.1/$PORT") 2>/dev/null; }

open_browser() {
  if [ "$(uname)" = "Darwin" ]; then open "$URL" >/dev/null 2>&1 || true; fi
}

# Already running (e.g. started in another Terminal window)? Open it instead of failing on a busy port.
if is_chargeflow_up; then
  echo ">>> ChargeFlow is already running at $URL (opening it). To stop it, press Ctrl+C in the Terminal window where it runs."
  open_browser
  exit 0
fi

# Find Java: PATH first, then Homebrew's keg-only OpenJDK locations.
if ! java -version >/dev/null 2>&1; then
  for d in /opt/homebrew/opt/openjdk@21 /opt/homebrew/opt/openjdk /usr/local/opt/openjdk@21 /usr/local/opt/openjdk; do
    if [ -x "$d/bin/java" ]; then export PATH="$d/bin:$PATH"; break; fi
  done
fi
# take the line that carries the version (some setups print "Picked up JAVA_TOOL_OPTIONS ..." first)
major=$(java -version 2>&1 | grep -m1 'version "' | sed -nE 's/.*version "([0-9]+).*/\1/p' || true)
case "$major" in ''|*[!0-9]*) major=0 ;; esac
if [ "$major" -lt 17 ]; then
  if [ "$major" -eq 0 ]; then echo "Java was not found."; else echo "Found Java $major, but ChargeFlow needs Java 17 or newer."; fi
  echo "On macOS:  brew install openjdk@21   then run:  bash run.sh"
  exit 1
fi

# Port taken (by ChargeFlow still starting in another window, or by another program)?
if port_busy; then
  echo "Port $PORT is busy: ChargeFlow may still be starting in another Terminal window, or another program uses it."
  echo "Wait a few seconds and run  bash run.sh  again, or use another port:  PORT=9090 bash run.sh"
  exit 1
fi

echo "Starting ChargeFlow (Java $major) on $URL"
echo "The first run downloads Maven + libraries (1-2 min). The browser opens once the server is ready. Ctrl+C to stop."
# Open the browser only when the server actually answers (waits up to 5 minutes).
( for _ in $(seq 1 150); do
    if is_chargeflow_up; then
      echo ">>> ChargeFlow is ready: $URL"
      open_browser
      exit 0
    fi
    sleep 2
  done
  echo ">>> The server did not answer within 5 minutes; check the messages above." ) &
export PORT
exec ./mvnw -q spring-boot:run
