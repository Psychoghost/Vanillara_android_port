#!/usr/bin/env bash
# Capture desktop and mobile screenshots of the running preview.
#
#   CAPTURE_URL  exact URL to open (required)
#   CAPTURE_DIR  directory for final-desktop.png / final-mobile.png (required)
#
# Exit 75: temporary browser/navigation infrastructure failure.
# Exit 1:  script or rendering defect.
# The app server is never touched; it keeps running after capture.
set -euo pipefail

: "${CAPTURE_URL:?Set CAPTURE_URL to the exact URL to capture.}"
: "${CAPTURE_DIR:?Set CAPTURE_DIR to the screenshot output directory.}"

PLAYWRIGHT_ROOT="${OMGITHUB_PLAYWRIGHT_ROOT:-$HOME/.local/share/omgithub-playwright}"

/usr/bin/time -p mkdir -p "$CAPTURE_DIR"
/usr/bin/time -p test -d "$PLAYWRIGHT_ROOT/node_modules/playwright"

cat > "$CAPTURE_DIR/capture-run.mjs" <<'JS'
import { createRequire } from 'node:module'
import { join } from 'node:path'

const url = process.env.CAPTURE_URL
const outDir = process.env.CAPTURE_DIR
const runtime = process.env.OMGITHUB_PLAYWRIGHT_ROOT
  || join(process.env.HOME, '.local/share/omgithub-playwright')

const require = createRequire(join(runtime, 'package.json'))
const { chromium } = require('playwright')

const started = Date.now()
const timings = []
const timed = async (label, fn) => {
  const t = Date.now()
  try {
    return await fn()
  } finally {
    const ms = Date.now() - t
    timings.push([label, ms])
    console.log(`[timing] ${label}: ${(ms / 1000).toFixed(2)}s`)
  }
}
const transient = (error) => {
  error.exitCode = 75
  throw error
}
const defect = (error) => {
  error.exitCode = 1
  throw error
}

const views = [
  ['desktop', 1440, 900],
  ['mobile', 390, 844],
]

let browser
let exitCode = 0
try {
  browser = await timed('launch chromium', () =>
    chromium.launch({
      headless: true,
      timeout: 30000,
      args: ['--no-sandbox', '--disable-dev-shm-usage'],
    }).catch(transient)
  )

  for (const [name, width, height] of views) {
    const page = await timed(`open ${name} page`, () =>
      browser.newPage({
        viewport: { width, height },
        deviceScaleFactor: 1,
      }).catch(transient)
    )
    page.setDefaultTimeout(30000)

    const failures = []
    page.on('pageerror', (error) => {
      console.error(`[${name}] page error: ${error.message}`)
      failures.push(error.message)
    })
    page.on('response', (response) => {
      try {
        if (response.status() >= 400 && new URL(response.url()).origin === new URL(url).origin) {
          failures.push(`HTTP ${response.status()}: ${response.url()}`)
        }
      } catch {}
    })

    const response = await timed(`navigate ${name}`, () =>
      page.goto(url, { waitUntil: 'networkidle', timeout: 45000 }).catch(transient)
    )
    if (!response) {
      transient(new Error(`No HTTP response loading ${url}`))
    }
    if (!response.ok()) {
      const status = response.status()
      const error = new Error(`HTTP ${status} loading ${url}`)
      if ([408, 429, 500, 502, 503, 504].includes(status)) transient(error)
      defect(error)
    }

    await timed(`wait for rendered content (${name})`, () =>
      page.locator('#app-ready').waitFor({ state: 'visible', timeout: 15000 })
        .catch((error) => defect(
          new Error(`Rendered content missing (#app-ready): ${error.message}`)
        ))
    )
    await timed(`wait for fonts (${name})`, () =>
      page.waitForFunction(() => document.fonts.status === 'loaded', null, { timeout: 15000 })
        .catch((error) => defect(new Error(`Fonts did not load: ${error.message}`)))
    )
    await page.waitForTimeout(400)

    const screenshotPath = join(outDir, `final-${name}.png`)
    await timed(`screenshot ${name}`, () =>
      page.screenshot({ path: screenshotPath, timeout: 30000 }).catch((error) => {
        if (error.name === 'TimeoutError' || !browser.isConnected()) transient(error)
        defect(error)
      })
    )

    if (failures.length) {
      defect(new Error(`${name} view failed to render cleanly: ${failures.join('; ')}`))
    }
    await page.close()
  }
} catch (error) {
  console.error(error?.stack || error)
  exitCode = error?.exitCode === 75 ? 75 : 1
} finally {
  if (browser) {
    await browser.close().catch((error) => {
      console.error(`browser close failed: ${error.message}`)
      if (exitCode === 0) exitCode = 75
    })
  }
  const total = Date.now() - started
  console.log(`[timing] total: ${(total / 1000).toFixed(2)}s`)
  if (exitCode !== 0) process.exitCode = exitCode
}
JS

set +e
CAPTURE_URL="$CAPTURE_URL" \
CAPTURE_DIR="$CAPTURE_DIR" \
OMGITHUB_PLAYWRIGHT_ROOT="$PLAYWRIGHT_ROOT" \
  /usr/bin/time -p node "$CAPTURE_DIR/capture-run.mjs"
status=$?
set -e

if [[ "$status" -ne 0 ]]; then
  echo "Capture failed with exit $status." >&2
  exit "$status"
fi

/usr/bin/time -p test -s "$CAPTURE_DIR/final-desktop.png"
/usr/bin/time -p test -s "$CAPTURE_DIR/final-mobile.png"
echo "Captured $CAPTURE_DIR/final-desktop.png and $CAPTURE_DIR/final-mobile.png"
