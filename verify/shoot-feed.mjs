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

// the app holds one feed query for every page; GET only, so marking read does not count
let feedReads = 0;
page.on("request", (r) => {
  if (r.method() === "GET" && r.url().includes("/api/feed")) feedReads += 1;
});

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

// a cold load of a source tab: the skeleton must be seen, and it must cost exactly one feed read
await setTheme("dark");
const readsBefore = feedReads;
await page.goto(`${BASE}/gitlab`, { waitUntil: "domcontentloaded" });
const skeleton = await page.waitForSelector("div.animate-pulse", { timeout: 5000 }).catch(() => null);
await page.waitForSelector("time", { timeout: 15000 });
// long enough that a second query would have fired, and well short of the 30s poll
await settle(1500);
console.log(`  skeleton ${skeleton === null ? "NEVER APPEARED" : "appeared"}, feed reads on load: ${feedReads - readsBefore}`);
if (skeleton === null) problems.push("the loading skeleton never appeared, so a fast load flashes instead");
if (feedReads - readsBefore > 1) problems.push(`opening a source tab cost ${feedReads - readsBefore} feed reads, not one`);
await shot("f06-gitlab-feed-dark");

// the reply lands on the same merge request as its review request, so there must be a group
const chevron = await page.$('button[aria-expanded="true"]');
if (chevron === null) {
  problems.push("no expandable group on the feed, so grouping did not happen");
} else {
  await chevron.click();
  await shot("f06a-group-collapsed-dark");
  await chevron.click();
  await settle(400);
}

// the search field: one place, every source, forgiving of how you typed it
const SEARCH = 'input[type="search"]';
const heading = () => page.$eval("h1", (el) => el.textContent);
async function searchFor(text) {
  await page.click(SEARCH, { clickCount: 3 });
  await page.keyboard.press("Backspace");
  await page.type(SEARCH, text);
  await settle(500);
  return { heading: await heading(), rows: (await page.$$('a[href^="https://gitlab.example.org"]')).length };
}

const typo = await searchFor("reveiw requsted");
console.log(`  fuzzy "reveiw requsted": ${typo.rows} rows under "${typo.heading}"`);
if (typo.rows === 0) problems.push("a two-typo query matched nothing");
await shot("f06b-search-fuzzy-dark");

const scoped = await searchFor("project:frontend is:unread");
console.log(`  scoped "project:frontend is:unread": ${scoped.rows} rows`);
if (scoped.rows === 0) problems.push("the scope prefixes matched nothing");
await shot("f06c-search-scoped-dark");

await page.keyboard.press("Escape");
await settle(500);
const handedBack = await heading();
console.log(`  escape handed the page back to "${handedBack}"`);
if (handedBack !== "GitLab") problems.push(`escape left the page on "${handedBack}"`);

// the tab itself carries the unread count until real notifications exist
const tab = await page.evaluate(() => ({
  title: document.title,
  icon: document.querySelector('link[rel="icon"]')?.getAttribute("href"),
}));
console.log(`  tab title "${tab.title}", favicon ${tab.icon}`);
if (!/^\(\d+\) Sift$/.test(tab.title)) problems.push(`the tab title carries no unread count: "${tab.title}"`);

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
