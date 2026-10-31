#!/usr/bin/env bash
# Build and serve the Vanillara preview site.
#
# Source and build output stay inside PROJECT_DIR (./dist). Only worker
# metadata (deployment-output.json) goes to OPENCODE_WEB_DIR.
set -euo pipefail

PROJECT_DIR="${PROJECT_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)}"
PORT="${PORT:-3000}"
DIST_DIR="$PROJECT_DIR/dist"
OPENCODE_WEB_DIR="${OPENCODE_WEB_DIR:-$PROJECT_DIR/.omgithub-web}"

[[ "$PORT" =~ ^[0-9]+$ ]] || { echo "PORT must be numeric, got: $PORT" >&2; exit 1; }

/usr/bin/time -p mkdir -p "$DIST_DIR" "$OPENCODE_WEB_DIR"

# Regenerate the static page from source on every start so the preview always
# matches this checkout. No external assets or network requests are used.
/usr/bin/time -p cat > "$DIST_DIR/index.html" <<'HTML'
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="theme-color" content="#0b0d0f">
<title>Vanillara — Red Alert on Android</title>
<link rel="icon" href="data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 64 64'%3E%3Crect width='64' height='64' rx='14' fill='%23e8452c'/%3E%3Cpath d='M18 44V20h14a8 8 0 0 1 0 16h-6v8zm8-16v4h6a2 2 0 0 0 0-4z' fill='%23fff'/%3E%3C/svg%3E">
<style>
  :root {
    --bg: #0b0d0f;
    --panel: #14181c;
    --panel-2: #191f24;
    --line: #283038;
    --text: #e9eef2;
    --muted: #9aa8b3;
    --accent: #e8452c;
    --accent-2: #f0a03c;
    --ok: #55c98d;
  }
  * { box-sizing: border-box; }
  html { scroll-behavior: smooth; }
  body {
    margin: 0;
    background:
      radial-gradient(1100px 560px at 82% -12%, rgba(232, 69, 44, .20), transparent 62%),
      radial-gradient(800px 500px at -10% 30%, rgba(240, 160, 60, .07), transparent 60%),
      var(--bg);
    color: var(--text);
    font-family: ui-sans-serif, system-ui, -apple-system, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
    line-height: 1.55;
    -webkit-font-smoothing: antialiased;
  }
  a { color: inherit; }
  .wrap { width: min(1120px, 100% - 48px); margin-inline: auto; }

  header.site {
    position: sticky; top: 0; z-index: 10;
    border-bottom: 1px solid rgba(40, 48, 56, .8);
    background: rgba(11, 13, 15, .82);
    backdrop-filter: blur(10px);
  }
  .nav { display: flex; align-items: center; justify-content: space-between; gap: 24px; height: 68px; }
  .brand { display: flex; align-items: center; gap: 11px; font-weight: 800; letter-spacing: .14em; font-size: 15px; text-transform: uppercase; text-decoration: none; }
  .brand .mark {
    display: grid; place-items: center; width: 30px; height: 30px; border-radius: 8px;
    background: linear-gradient(145deg, var(--accent), #b52d1c);
    box-shadow: 0 6px 18px rgba(232, 69, 44, .35);
    font-size: 15px; letter-spacing: 0;
  }
  .nav-links { display: flex; align-items: center; gap: 26px; font-size: 14px; color: var(--muted); }
  .nav-links a { text-decoration: none; transition: color .15s ease; }
  .nav-links a:hover { color: var(--text); }

  .hero { display: grid; grid-template-columns: minmax(0, 1.15fr) minmax(0, .85fr); gap: 56px; align-items: center; padding: 84px 0 68px; }
  .eyebrow {
    display: inline-flex; align-items: center; gap: 9px;
    padding: 7px 13px; border: 1px solid rgba(232, 69, 44, .38); border-radius: 999px;
    background: rgba(232, 69, 44, .09); color: #ffb3a6;
    font-size: 12px; font-weight: 700; letter-spacing: .12em; text-transform: uppercase;
  }
  .eyebrow::before { content: ""; width: 7px; height: 7px; border-radius: 50%; background: var(--accent); box-shadow: 0 0 12px var(--accent); }
  h1 { margin: 22px 0 18px; font-size: clamp(42px, 6vw, 68px); line-height: 1.02; letter-spacing: -.035em; font-weight: 850; }
  h1 span { color: var(--accent-2); }
  .lede { margin: 0 0 28px; max-width: 54ch; font-size: 17px; color: var(--muted); }
  .lede strong { color: var(--text); font-weight: 650; }
  .actions { display: flex; flex-wrap: wrap; gap: 12px; }
  .btn {
    display: inline-flex; align-items: center; justify-content: center; gap: 9px;
    min-height: 48px; padding: 0 22px; border-radius: 11px;
    font-size: 15px; font-weight: 700; text-decoration: none;
    border: 1px solid transparent; transition: transform .12s ease, background .15s ease, border-color .15s ease;
  }
  .btn:hover { transform: translateY(-1px); }
  .btn.primary { background: linear-gradient(145deg, var(--accent), #c7341f); color: #fff; box-shadow: 0 12px 28px rgba(232, 69, 44, .30); }
  .btn.primary:hover { background: linear-gradient(145deg, #f0523a, #d13a24); }
  .btn.ghost { background: var(--panel); color: var(--text); border-color: var(--line); }
  .btn.ghost:hover { border-color: #3a464f; background: var(--panel-2); }
  .chips { display: flex; flex-wrap: wrap; gap: 9px; padding: 0; margin: 26px 0 0; list-style: none; }
  .chips li { padding: 6px 11px; border: 1px solid var(--line); border-radius: 8px; background: rgba(20, 24, 28, .7); font-size: 12px; color: var(--muted); }
  .chips b { color: var(--text); font-weight: 650; }

  .panel {
    border: 1px solid var(--line); border-radius: 18px; background: linear-gradient(180deg, rgba(25, 31, 36, .95), rgba(16, 20, 24, .95));
    box-shadow: 0 30px 70px rgba(0, 0, 0, .45);
    overflow: hidden;
  }
  .panel-bar { display: flex; align-items: center; gap: 7px; padding: 13px 16px; border-bottom: 1px solid var(--line); background: rgba(11, 13, 15, .55); }
  .dot { width: 9px; height: 9px; border-radius: 50%; background: #39434b; }
  .dot.r { background: #e8452c; } .dot.y { background: #f0a03c; } .dot.g { background: #55c98d; }
  .panel-title { margin-left: 8px; font-size: 12px; letter-spacing: .08em; text-transform: uppercase; color: var(--muted); }
  .tree { margin: 0; padding: 22px 20px 24px; font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: 12.5px; line-height: 1.85; color: #b9c6cf; overflow-x: auto; }
  .tree .dir { color: var(--accent-2); }
  .tree .file { color: #8fd8b4; }
  .tree .note { color: #6f7d87; }
  .panel-foot { display: flex; align-items: center; gap: 8px; padding: 13px 18px; border-top: 1px solid var(--line); background: rgba(11, 13, 15, .45); font-size: 12px; color: var(--muted); }
  .panel-foot .ok { width: 8px; height: 8px; border-radius: 50%; background: var(--ok); box-shadow: 0 0 10px rgba(85, 201, 141, .7); }

  .notice {
    display: flex; gap: 14px; align-items: flex-start;
    margin-top: 34px; padding: 16px 18px; border: 1px solid rgba(240, 160, 60, .28); border-radius: 13px;
    background: rgba(240, 160, 60, .06);
  }
  .notice .icon { flex: none; display: grid; place-items: center; width: 30px; height: 30px; border-radius: 8px; background: rgba(240, 160, 60, .14); color: var(--accent-2); font-weight: 800; }
  .notice p { margin: 0; font-size: 13.5px; color: #cbbba3; }
  .notice strong { color: #f2d9b4; }

  section.block { padding: 26px 0 10px; }
  .section-head { display: flex; align-items: baseline; gap: 16px; margin-bottom: 26px; }
  .section-head h2 { margin: 0; font-size: clamp(26px, 3.4vw, 36px); letter-spacing: -.02em; }
  .section-head p { margin: 0; color: var(--muted); font-size: 14px; }
  .steps { display: grid; grid-template-columns: repeat(3, 1fr); gap: 16px; }
  .step { padding: 22px; border: 1px solid var(--line); border-radius: 15px; background: var(--panel); }
  .step .num { display: inline-grid; place-items: center; width: 27px; height: 27px; margin-bottom: 14px; border-radius: 8px; background: rgba(232, 69, 44, .14); color: #ff9d8b; font-size: 13px; font-weight: 800; }
  .step h3 { margin: 0 0 8px; font-size: 16px; }
  .step p { margin: 0; font-size: 13.5px; color: var(--muted); }
  .step code, .inline-code { padding: 2px 6px; border: 1px solid var(--line); border-radius: 5px; background: #0e1215; font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: 12px; color: #d7e2e9; }

  .pathbox { margin-top: 18px; border: 1px solid var(--line); border-radius: 13px; background: #0e1215; overflow: hidden; }
  .pathbox .label { padding: 10px 16px; border-bottom: 1px solid var(--line); font-size: 11px; letter-spacing: .1em; text-transform: uppercase; color: var(--muted); }
  .pathbox code { display: block; padding: 16px; overflow-x: auto; white-space: nowrap; font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: 12.5px; color: #8fd8b4; }

  .controls { margin-top: 58px; padding-bottom: 20px; }
  .table-wrap { border: 1px solid var(--line); border-radius: 15px; overflow: hidden; }
  table { width: 100%; border-collapse: collapse; font-size: 14px; }
  th, td { padding: 13px 18px; text-align: left; border-bottom: 1px solid var(--line); }
  th { background: rgba(20, 24, 28, .9); color: var(--muted); font-size: 11px; letter-spacing: .1em; text-transform: uppercase; }
  tr:last-child td { border-bottom: 0; }
  td:first-child { color: #ffb3a6; font-weight: 650; width: 44%; }

  footer { margin-top: 64px; border-top: 1px solid var(--line); background: rgba(9, 11, 13, .6); }
  .foot { display: flex; flex-wrap: wrap; gap: 18px; justify-content: space-between; padding: 30px 0 38px; color: var(--muted); font-size: 12.5px; }
  .foot p { margin: 0; max-width: 72ch; }
  .foot a { color: #c8d3da; text-decoration: none; border-bottom: 1px solid #3a464f; }
  .foot a:hover { color: #fff; border-color: var(--accent); }

  @media (max-width: 860px) {
    .hero { grid-template-columns: 1fr; gap: 38px; padding: 54px 0 46px; }
    .steps { grid-template-columns: 1fr; }
    .nav-links { gap: 16px; font-size: 13px; }
    .nav-links a.hide-sm { display: none; }
  }
  @media (max-width: 520px) {
    .wrap { width: calc(100% - 32px); }
    h1 { font-size: 42px; }
    .lede { font-size: 15.5px; }
    .btn { flex: 1 1 auto; }
    .nav-links { display: none; }
    .hero { padding-top: 40px; }
    .section-head { display: block; }
    .section-head p { margin-top: 6px; }
    th, td { padding: 11px 13px; font-size: 13px; }
  }
</style>
</head>
<body>
<header class="site">
  <div class="wrap nav">
    <a class="brand" href="#top"><span class="mark">V</span> Vanillara</a>
    <nav class="nav-links">
      <a href="#setup">Setup</a>
      <a href="#controls">Controls</a>
      <a class="hide-sm" href="https://github.com/Psychoghost/Vanillara_android_port">Source</a>
      <a href="https://github.com/Psychoghost/Vanillara_android_port/releases/latest">Download</a>
    </nav>
  </div>
</header>

<main class="wrap" id="top">
  <section class="hero">
    <div>
      <span class="eyebrow">Native Android source port</span>
      <h1 id="app-ready">Red Alert.<br><span>On your phone.</span></h1>
      <p class="lede">
        A native <strong>arm64 Android port</strong> of Command &amp; Conquer: Red Alert,
        built from Electronic Arts' GPLv3 source release. Touch controls, campaigns,
        skirmish, and movies — running directly on your device.
      </p>
      <div class="actions">
        <a class="btn primary" href="https://github.com/Psychoghost/Vanillara_android_port/releases/latest">
          Download APK
        </a>
        <a class="btn ghost" href="https://github.com/Psychoghost/Vanillara_android_port">View source</a>
      </div>
      <ul class="chips">
        <li><b>arm64-v8a</b></li>
        <li>Android <b>6.0+</b></li>
        <li><b>Debug-signed</b> sideload</li>
        <li><b>GPLv3</b> + EA terms</li>
      </ul>

      <div class="notice">
        <span class="icon">!</span>
        <p><strong>No game data is included.</strong> You must supply your own legally
        obtained Allied and Soviet Red Alert files. The app creates the drop folders
        and shows you exactly where they go.</p>
      </div>
    </div>

    <aside class="panel" aria-label="Game data folder layout">
      <div class="panel-bar">
        <span class="dot r"></span><span class="dot y"></span><span class="dot g"></span>
        <span class="panel-title">on-device data folder</span>
      </div>
      <pre class="tree"><span class="dir">Android/data/com.vanillara.redalert/</span>
└─ <span class="dir">files/redalert-root/</span>
   └─ <span class="dir">assets/redalert/</span>
      ├─ <span class="dir">allies/</span>
      │  ├─ <span class="file">INSTALL/REDALERT.INI</span>
      │  └─ <span class="file">*.MIX</span>
      └─ <span class="dir">soviet/</span>
         ├─ <span class="file">INSTALL/REDALERT.INI</span>
         └─ <span class="file">*.MIX</span>

<span class="note"># created on first launch</span>
<span class="note"># copy your own discs here</span></pre>
      <div class="panel-foot"><span class="ok"></span> Ready when REDALERT.MIX is found</div>
    </aside>
  </section>

  <section class="block" id="setup">
    <div class="section-head">
      <h2>Three steps to play</h2>
      <p>First launch creates the folders for you.</p>
    </div>
    <div class="steps">
      <article class="step">
        <span class="num">1</span>
        <h3>Install the APK</h3>
        <p>Download the latest release and allow installs from your browser or file
        manager. The build is debug-signed for sideloading.</p>
      </article>
      <article class="step">
        <span class="num">2</span>
        <h3>Copy your files</h3>
        <p>Put your Allied disc contents in <code>allies/</code> and Soviet contents
        in <code>soviet/</code>. Each side needs <code>REDALERT.MIX</code> and its
        <code>.MIX</code> files.</p>
      </article>
      <article class="step">
        <span class="num">3</span>
        <h3>Rescan and play</h3>
        <p>Return to Vanillara, tap <b>Rescan</b> until the status says
        <b>Ready</b>, then tap <b>Play</b>.</p>
      </article>
    </div>
    <div class="pathbox">
      <div class="label">Allied and Soviet drop folders</div>
      <code>…/Android/data/com.vanillara.redalert/files/redalert-root/assets/redalert/{allies,soviet}</code>
    </div>
  </section>

  <section class="block controls" id="controls">
    <div class="section-head">
      <h2>Touch controls</h2>
      <p>Designed for a phone or tablet in landscape.</p>
    </div>
    <div class="table-wrap">
      <table>
        <thead><tr><th>Gesture</th><th>Action</th></tr></thead>
        <tbody>
          <tr><td>Tap</td><td>Left click</td></tr>
          <tr><td>Drag</td><td>Left-button drag / selection box</td></tr>
          <tr><td>Long press or two-finger tap</td><td>Right click / deselect</td></tr>
          <tr><td>Two-finger drag</td><td>Scroll the map or pan the zoomed view</td></tr>
          <tr><td>Pinch</td><td>Zoom up to 3×</td></tr>
          <tr><td>Back button / gesture</td><td>Escape</td></tr>
        </tbody>
      </table>
    </div>
  </section>
</main>

<footer>
  <div class="wrap foot">
    <p>
      Unofficial modified source port based on
      <a href="https://github.com/electronicarts/CnC_Red_Alert">EA's CnC_Red_Alert</a>
      and <a href="https://github.com/dk8827/ra-port">dk8827/ra-port</a>.
      “Command &amp; Conquer” and “Red Alert” are trademarks of Electronic Arts Inc.
      This project is not affiliated with or endorsed by Electronic Arts.
    </p>
    <p><a href="https://github.com/Psychoghost/Vanillara_android_port">GitHub</a> · GPLv3 + EA terms</p>
  </div>
</footer>
</body>
</html>
HTML

# Worker metadata: where the controller can find the built static directory.
/usr/bin/time -p node -e '
  const fs = require("fs");
  fs.writeFileSync(process.argv[3], JSON.stringify({
    project: process.argv[1],
    directory: process.argv[2]
  }, null, 2) + "\n");
' "$PROJECT_DIR" "$DIST_DIR" "$OPENCODE_WEB_DIR/deployment-output.json"

/usr/bin/time -p test -f "$DIST_DIR/index.html"

echo "Serving $DIST_DIR on 0.0.0.0:$PORT"
exec /usr/bin/time -p python3 -m http.server "$PORT" --bind 0.0.0.0 --directory "$DIST_DIR"
