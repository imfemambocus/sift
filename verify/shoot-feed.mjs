import { mkdirSync, readFileSync, writeFileSync, unlinkSync } from "node:fs";
import { createRequire } from "node:module";

const require = createRequire(`${process.env.SIFT_FRONTEND ?? new URL("../frontend/", import.meta.url).pathname}`);
const puppeteer = require("puppeteer");

const BASE = "http://localhost:5174";
const STUB = "http://127.0.0.1:7788";
const WORK = process.env.SIFT_VERIFY_WORK;
const OUT = process.env.SIFT_VERIFY_SHOTS ?? `${WORK}/shots`;
mkdirSync(OUT, { recursive: true });

const settle = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const problems = [];

const browser = await puppeteer.launch({
  headless: true,
  defaultViewport: { width: 1440, height: 1000, deviceScaleFactor: 2 },
});
const page = await browser.newPage();
page.on("console", (m) => { if (m.type() === "error") problems.push(`console: ${m.text()}`); });
page.on("pageerror", (e) => problems.push(`pageerror: ${e.message}`));

const setTheme = (t) => page.evaluate((v) => window.localStorage.setItem("sift-theme", v), t);
async function shot(name) {
  await settle(900);
  await page.screenshot({ path: `${OUT}/${name}.png` });
  console.log(`  shot ${name}`);
}

// create the account
await page.goto(`${BASE}/create-account`, { waitUntil: "networkidle0" });
await setTheme("dark");
await page.reload({ waitUntil: "networkidle0" });
await page.type('input[name="displayName"]', "Isfaaq");
await page.type('input[name="email"]', "isfaaq@uni.lu");
await page.type('input[name="password"]', "correct-horse-battery");
await page.click('button[type="submit"]');
await page.waitForSelector('nav[aria-label="Sections"]', { timeout: 20000 });

// home with nothing connected
await shot("f01-home-nothing-connected");

// the connect form
await page.click('a[aria-label="Settings"]');
await page.waitForSelector('input[name="instanceUrl"]', { timeout: 10000 });
await page.type('input[name="instanceUrl"]', STUB);
await page.type('input[name="token"]', "good-token");
await shot("f02-connect-form-dark");

// connect, which syncs inline
await page.click('form button[type="submit"]');
await page.waitForFunction(
  () => !document.querySelector('input[name="instanceUrl"]'),
  { timeout: 30000 },
);
await shot("f03-settings-connected-dark");

// the two fixes: buttons must look clickable
for (const [label, selector] of [["theme toggle", 'button[aria-label^="Theme"]'], ["sign out", 'button[aria-label="Sign out"]']]) {
  const cursor = await page.$eval(selector, (el) => getComputedStyle(el).cursor);
  console.log(`  cursor on ${label}: ${cursor}${cursor === "pointer" ? "" : "   <-- WRONG"}`);
}

// a colleague replies, and the fast sweep should turn it into a row of its own
const discPath = `${WORK}/feed-disc.json`;
const disc = JSON.parse(readFileSync(discPath, "utf8"));
disc["merge_requests:5:12"][0].notes.push({
  id: 5002, body: "Good catch, batched them and pushed a fix.", system: false,
  created_at: new Date().toISOString(), author: { id: 9, username: "maxime", name: "Maxime" },
});
writeFileSync(discPath, JSON.stringify(disc));
console.log("  colleague replied, waiting for a sweep");
await settle(9000);

// the feed
await page.click('a[aria-label="Home"]');
await page.waitForSelector('a[href="/gitlab"]', { timeout: 15000 });
await shot("f04-home-cards-dark");

await setTheme("light");
await page.reload({ waitUntil: "networkidle0" });
await page.waitForSelector('a[href="/gitlab"]', { timeout: 15000 });
await shot("f05-home-cards-light");

await setTheme("dark");
await page.goto(`${BASE}/gitlab`, { waitUntil: "networkidle0" });
await page.waitForSelector("time", { timeout: 15000 });
await shot("f06-gitlab-feed-dark");

// mobile
await page.setViewport({ width: 430, height: 900, deviceScaleFactor: 2 });
await page.goto(`${BASE}/`, { waitUntil: "networkidle0" });
await page.waitForSelector('a[href="/gitlab"]', { timeout: 15000 });
await shot("f07-home-cards-mobile");
await page.setViewport({ width: 1440, height: 1000, deviceScaleFactor: 2 });

// revoke the token upstream and let the fast sweep notice, to capture the alert
writeFileSync(`${WORK}/revoked`, "");
console.log("  revoked the stub token, waiting for the sweep");
await settle(12000);
await page.goto(`${BASE}/`, { waitUntil: "networkidle0" });
await shot("f08-home-token-rejected");
await page.goto(`${BASE}/settings`, { waitUntil: "networkidle0" });
await shot("f09-settings-token-rejected");
try { unlinkSync(`${WORK}/revoked`); } catch { /* already gone */ }

// signing out must land on the sign-in screen without a manual refresh
await page.goto(`${BASE}/`, { waitUntil: "networkidle0" });
await page.click('button[aria-label="Sign out"]');
await page.waitForFunction(() => window.location.pathname === "/sign-in", { timeout: 10000 })
  .then(() => console.log("  sign out redirected to /sign-in"))
  .catch(() => console.log("  SIGN OUT DID NOT REDIRECT"));
await shot("f10-after-sign-out");

await browser.close();
console.log(problems.length === 0 ? "\nno console or network errors" : `\nPROBLEMS:\n${problems.join("\n")}`);
