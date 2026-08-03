/*
 * Regenerates docs/banner-{dark,light}.png, the README header.
 *
 * Puppeteer is deliberately not a dependency of this project. Install it transiently, run this,
 * and leave it out of package.json:
 *
 *   npm --prefix ../frontend install --no-save puppeteer
 *   node banner.mjs
 *   npm --prefix ../frontend uninstall puppeteer     # only if you want it gone
 *
 * Rendered through a real browser rather than written as an SVG so the wordmark uses the app's
 * actual typeface. GitHub sanitises SVG and would not load the font.
 */
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { createRequire } from "node:module";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";

const HERE = import.meta.dirname;
const FRONTEND = resolve(HERE, "..", "frontend");
const require = createRequire(join(FRONTEND, "/"));
const puppeteer = require("puppeteer");

const font = (path) => readFileSync(join(FRONTEND, "node_modules", path)).toString("base64");
const SANS = font("@fontsource-variable/instrument-sans/files/instrument-sans-latin-wght-normal.woff2");
const MONO = font("@fontsource/ibm-plex-mono/files/ibm-plex-mono-latin-500-normal.woff2");

// the app's signature motif, compressed to banner height: a flood thinning into what got through
const NOISE = [
	[300, 58, 0.05], [232, 82, 0.055], [274, 50, 0.06], [198, 92, 0.07],
	[312, 46, 0.08], [252, 70, 0.09], [186, 56, 0.1], [288, 80, 0.115],
	[222, 48, 0.13], [258, 88, 0.15], [170, 62, 0.17],
];
const REACHED = [[236, "accent"], [188, "border"]];

const noiseRows = NOISE.map(
	([title, meta, opacity], index) =>
		`<div class="row" style="opacity:${opacity};margin-top:${index === 0 ? 0 : 2 + index}px">
       <span class="bar" style="width:${title}px"></span><span class="bar" style="width:${meta}px"></span>
     </div>`,
).join("");

const reachedRows = REACHED.map(
	([width, kind]) => `<div class="reached ${kind}"><span class="bar solid" style="width:${width}px"></span></div>`,
).join("");

const html = `<!doctype html>
<html data-theme="dark"><head><meta charset="utf-8"><style>
@font-face{font-family:"Sans";src:url(data:font/woff2;base64,${SANS}) format("woff2-variations");font-weight:100 900}
@font-face{font-family:"Mono";src:url(data:font/woff2;base64,${MONO}) format("woff2");font-weight:500}

html[data-theme="dark"]{--bg:#0f1013;--fg:#f2f3f5;--muted:#8e939d;--accent:#d6a250;--border:#262a31}
html[data-theme="light"]{--bg:#fbfaf8;--fg:#1b1d22;--muted:#6b7078;--accent:#8a5f22;--border:#e3e0da}

*{margin:0;padding:0;box-sizing:border-box}
body{width:1280px;height:360px;background:var(--bg);color:var(--fg);font-family:"Sans",sans-serif;
  display:flex;align-items:center;justify-content:space-between;padding:0 76px;overflow:hidden;
  -webkit-font-smoothing:antialiased}

.left{display:flex;flex-direction:column;gap:22px}
.brand{display:flex;align-items:center;gap:13px}
.brand svg{width:30px;height:30px;color:var(--accent)}
.brand span{font-size:37px;font-weight:600;letter-spacing:-0.035em}
.tagline{font-size:17px;line-height:1.5;color:var(--muted);max-width:23ch}
.eyebrow{font-family:"Mono",monospace;font-size:10px;font-weight:500;letter-spacing:0.13em;
  text-transform:uppercase;color:var(--muted)}

/* left aligned with ragged right ends, so the block reads as text the way the app's panel does */
.motif{display:flex;flex-direction:column;gap:18px;width:430px;flex:none}
.stack{display:flex;flex-direction:column}
.row{display:flex;gap:9px}
.bar{height:3px;border-radius:999px;background:var(--fg)}
.bar.solid{opacity:0.32}
/* the row is otherwise only as tall as its 3px bar, which turns the edge marker into a dot */
.reached{display:flex;align-items:center;min-height:17px;border-left:2px solid;padding-left:14px;margin-top:7px}
.reached.accent{border-color:var(--accent)}
.reached.border{border-color:var(--border)}
</style></head><body>
  <div class="left">
    <div class="brand">
      <svg viewBox="0 0 32 32" fill="none" aria-hidden="true">
        <g stroke="currentColor" stroke-width="3.2" stroke-linecap="round">
          <line x1="5" y1="9" x2="27" y2="9"/><line x1="9.5" y1="16" x2="22.5" y2="16"/><line x1="14" y1="23" x2="18" y2="23"/>
        </g>
      </svg>
      <span>Sift</span>
    </div>
    <p class="tagline">The few things that actually need you, in one place.</p>
    <p class="eyebrow">GitLab today &nbsp;&middot;&nbsp; Outlook later</p>
  </div>
  <div class="motif">
    <div class="stack">${noiseRows}</div>
    <div class="stack">${reachedRows}</div>
  </div>
</body></html>`;

const scratch = join(tmpdir(), "sift-banner.html");
writeFileSync(scratch, html);
mkdirSync(HERE, { recursive: true });

const browser = await puppeteer.launch({ headless: true });
const page = await browser.newPage();
await page.setViewport({ width: 1280, height: 360, deviceScaleFactor: 2 });

for (const theme of ["dark", "light"]) {
	await page.goto(`file://${scratch}`, { waitUntil: "networkidle0" });
	await page.evaluate((value) => {
		document.documentElement.dataset.theme = value;
	}, theme);
	await page.evaluate(() => document.fonts.ready);
	await page.screenshot({ path: join(HERE, `banner-${theme}.png`) });
	console.log(`wrote docs/banner-${theme}.png`);
}

await browser.close();
