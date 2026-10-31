#!/usr/bin/env bash
# Once-per-worker prerequisites for the preview and capture tooling.
set -euo pipefail

PLAYWRIGHT_ROOT="${OMGITHUB_PLAYWRIGHT_ROOT:-$HOME/.local/share/omgithub-playwright}"
RUNTIME_DIR="${RUNTIME_DIR:-/home/runner/work/_temp/omgithub-runtime}"

# Install the browser runtime only when this worker does not already have it.
if [[ ! -d "$PLAYWRIGHT_ROOT/node_modules/playwright" ]]; then
  echo "Playwright runtime missing; installing once for this worker."
  /usr/bin/time -p bash "$RUNTIME_DIR/scripts/setup-linux-playwright.sh"
else
  echo "Playwright runtime already present at $PLAYWRIGHT_ROOT"
fi

/usr/bin/time -p node --version
/usr/bin/time -p python3 --version
/usr/bin/time -p test -x "$PLAYWRIGHT_ROOT/node_modules/.bin/playwright"
echo "Startup prerequisites satisfied."
