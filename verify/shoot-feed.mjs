import { mkdirSync, readFileSync, writeFileSync, unlinkSync } from "node:fs";
import { createRequire } from "node:module";

const require = createRequire(`${process.env.SIFT_FRONTEND ?? new URL("../frontend/", import.meta.url).pathname}`);
const puppeteer = require("puppeteer");

const BASE = "http://localhost:5174";
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
/*
 * the session probe answers 401 before anyone has signed in, by design, and chromium logs every failed
 * request to the console. ignoring that one url is what makes a clean run actually read as clean.
 */
const EXPECTED_401 = "/api/auth/me";
page.on("console", (m) => {
  if (m.type() !== "error") return;
  if (m.location().url?.includes(EXPECTED_401)) return;
  problems.push(`console: ${m.text()}`);
});
page.on("pageerror", (e) => problems.push(`pageerror: ${e.message}`));

/*
 * a page load costs one page of the feed and one read of the counts, and they are counted apart:
 * the summary is a second endpoint, so lumping them together would hide a page that quietly
 * fires two list queries. GET only, so marking read does not count.
 */
let feedReads = 0;
let summaryReads = 0;
page.on("request", (r) => {
  if (r.method() !== "GET") return;
  if (r.url().includes("/api/feed/summary")) summaryReads += 1;
  else if (r.url().includes("/api/feed")) feedReads += 1;
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

// the connect screen, which offers one thing: approving on GitLab
await page.click('a[aria-label="Settings"]');
const connectButton = 'button::-p-text(Connect with GitLab)';
await page.waitForSelector(connectButton, { timeout: 10000 });
await shot("f02-connect-authorize-dark");

/*
 * the whole authorization, in a real browser: the app hands it to the stand-in instance, which
 * approves at once and sends it back to the callback, which stores the credential, syncs inline and
 * redirects to home. the token never touches this page, which is the point of the flow.
 */
await Promise.all([
  page.waitForNavigation({ waitUntil: "networkidle0", timeout: 30000 }),
  page.click(connectButton),
]);
/*
 * the rail link is the proof of both rules at once: the source connected, and a connected source is
 * what puts an icon in the rail. it also says where it actually landed rather than throwing a
 * selector timeout, which named no cause.
 */
const connected = await page.waitForSelector('a[href="/gitlab"]', { timeout: 30000 }).catch(() => null);
if (connected === null) {
  problems.push(`the authorization did not connect anything; it ended on ${page.url()}`);
  console.log(`  NOT CONNECTED, ended on ${page.url()}`);
  await shot("f03-authorization-failed");
} else {
  const landed = new URL(page.url()).pathname;
  if (landed !== "/") problems.push(`connecting landed on ${landed}, not on home`);
  console.log(`  authorized on the stand-in instance and came back connected, on ${landed}`);
  await shot("f03a-home-connected-dark");

  await page.goto(`${BASE}/settings`, { waitUntil: "networkidle0" });
  await page.waitForSelector('button::-p-text(Disconnect)', { timeout: 15000 });
  await shot("f03-settings-connected-dark");
}

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
const summariesBefore = summaryReads;
await page.goto(`${BASE}/gitlab`, { waitUntil: "domcontentloaded" });
const skeleton = await page.waitForSelector("div.animate-pulse", { timeout: 5000 }).catch(() => null);
await page.waitForSelector("time", { timeout: 15000 });
// long enough that a second query would have fired, and well short of the 30s poll
await settle(1500);
console.log(`  skeleton ${skeleton === null ? "NEVER APPEARED" : "appeared"}, on load: ${feedReads - readsBefore} page reads, ${summaryReads - summariesBefore} count reads`);
if (skeleton === null) problems.push("the loading skeleton never appeared, so a fast load flashes instead");
if (feedReads - readsBefore > 1) problems.push(`opening a source tab cost ${feedReads - readsBefore} feed reads, not one`);
if (summaryReads - summariesBefore > 1) problems.push(`opening a source tab read the counts ${summaryReads - summariesBefore} times, not one`);
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
/*
 * the previous query is selected through setSelectionRange, not a triple click or cmd+A: both left it
 * in the field and the next query was appended to it, so a scoped search silently became nonsense.
 * headless chromium does not implement the platform select-all shortcut at all.
 */
async function searchFor(text) {
  await page.click(SEARCH);
  await page.$eval(SEARCH, (el) => el.setSelectionRange(0, el.value.length));
  await page.keyboard.press("Backspace");
  const left = await page.$eval(SEARCH, (el) => el.value);
  if (left !== "") problems.push(`the search field would not clear, so "${text}" was typed onto "${left}"`);
  await page.type(SEARCH, text);
  // the search waits 250ms for the typing to stop and then asks the server, so this must outlast both
  await settle(1200);
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

// a refresh someone pressed must skeleton the list, which a background sweep must not
const refresh = await page.$('button[aria-label^="Check GitLab"]');
if (refresh === null) {
  problems.push("no refresh button on the feed page");
} else {
  await refresh.click();
  const onRefresh = await page.waitForSelector("div.animate-pulse", { timeout: 3000 }).catch(() => null);
  console.log(`  refresh skeleton ${onRefresh === null ? "NEVER APPEARED" : "appeared"}`);
  if (onRefresh === null) problems.push("pressing refresh did not skeleton the list");
  await page.waitForSelector("time", { timeout: 20000 });
  await shot("f06d-refresh-skeleton-gone-dark");
}

/*
 * a theme swap fades, and the point is that everything fades on one clock. these three normally differ
 * (a row has transition-colors, the rail nav has none, the search input has its own), so them agreeing
 * during a swap is the invariant worth asserting. the flag going on and off is racy to catch from out
 * here, so this sets it directly and checks the rule rather than the timing that turns it on.
 */
const swap = await page.evaluate(() => {
  const parts = {
    row: document.querySelector('a[href^="https://gitlab.example.org"]')?.parentElement,
    rail: document.querySelector('nav[aria-label="Sections"]'),
    search: document.querySelector('input[type="search"]'),
  };
  if (!parts.row || !parts.rail || !parts.search) return null;
  const read = () => Object.fromEntries(
    Object.entries(parts).map(([name, el]) => [name, getComputedStyle(el).transitionDuration]),
  );
  document.documentElement.setAttribute("data-theme-switching", "");
  const during = read();
  document.documentElement.removeAttribute("data-theme-switching");
  return { during, after: read(), fade: getComputedStyle(document.documentElement).getPropertyValue("--theme-fade").trim() };
});
if (swap === null) {
  problems.push("could not find the elements to check the theme fade against");
} else {
  const durations = [...new Set(Object.values(swap.during))];
  console.log(`  theme fade ${swap.fade}; during a swap ${JSON.stringify(swap.during)}`);
  if (durations.length !== 1) problems.push(`the theme fade is not uniform: ${JSON.stringify(swap.during)}`);
  if (durations[0] === "0s") problems.push("the theme fade is instant, so nothing fades");
  if (swap.after.rail !== "0s") problems.push("the fade rule leaked past the swap onto the rail");
}

// the tab itself carries the unread count until real notifications exist
const tab = await page.evaluate(() => ({
  title: document.title,
  icon: document.querySelector('link[rel="icon"]')?.getAttribute("href"),
}));
console.log(`  tab title "${tab.title}", favicon ${tab.icon}`);
if (!/^Sift \(\d+\)$/.test(tab.title)) problems.push(`the tab title carries no unread count: "${tab.title}"`);

/*
 * a to-do somebody completes upstream is history, not something that disappears: the row stays in the
 * feed, says so, and stops counting as unread. the tab badge is what proves the second half.
 */
const FINISHED = "Add rate limiting to the sync sweep";
const todosPath = `${WORK}/todos.json`;
const todos = JSON.parse(readFileSync(todosPath, "utf8"));
writeFileSync(todosPath, JSON.stringify(todos.filter((t) => t.target?.title !== FINISHED)));
await page.goto(`${BASE}/gitlab`, { waitUntil: "networkidle0" });
await page.click('button[aria-label^="Check GitLab"]');
await page.waitForSelector("time", { timeout: 20000 });
await settle(1200);

const settled = await page.evaluate((title) => {
  const row = [...document.querySelectorAll('a[href^="https://gitlab.example.org"]')]
    .find((a) => a.textContent.includes(title));
  if (row === undefined) return null;
  return {
    done: [...row.querySelectorAll("span")].some((s) => s.textContent === "done"),
    title: document.title,
  };
}, FINISHED);

if (settled === null) {
  problems.push(`"${FINISHED}" left the feed when it was completed, instead of staying as history`);
} else {
  console.log(`  completed upstream: still listed, done tag ${settled.done}, tab "${settled.title}"`);
  if (!settled.done) problems.push("a resolved row does not say it is done, so it reads as still waiting");
  if (settled.title !== "Sift (11)") problems.push(`the unread count still holds finished work: "${settled.title}"`);
}
await shot("f06e-resolved-history-dark");

/*
 * Home leads with unread and keeps waiting as context, so both numbers are checked. they answer
 * different questions: reading every row leaves the source still reporting all of them, so waiting
 * does not move and only unread says whether anything has been dealt with.
 */
/*
 * read off the elements, never off the card's textContent: the family counts abut the footer, so
 * "Everything else" with 2 followed by "11 waiting" reads as one run of "211 waiting" and a regex
 * over the whole string quietly answers 211.
 */
async function homeCounts() {
  await page.click('a[aria-label="Home"]');
  // the rail links to /gitlab as well, so the card is the one that talks about waiting
  await page.waitForFunction(() => [...document.querySelectorAll('a[href="/gitlab"]')]
    .some((el) => el.textContent.includes("waiting")), { timeout: 15000 });
  return page.evaluate(() => {
    const card = [...document.querySelectorAll('a[href="/gitlab"]')]
      .find((el) => el.textContent.includes("waiting"));
    const spans = [...card.querySelectorAll("span")].map((el) => ({ el, text: el.textContent.trim() }));
    const label = spans.find((s) => s.text === "unread" || s.text === "unread item");
    const waiting = spans.map((s) => s.text).find((t) => /^\d+ waiting$/.test(t));
    return {
      unread: label?.el.previousElementSibling?.textContent.trim() ?? null,
      waiting: waiting?.split(" ")[0] ?? null,
    };
  });
}

const home = await homeCounts();
console.log(`  home says ${home.unread} unread and ${home.waiting} waiting, out of 12 in the feed`);
if (home.waiting !== "11") problems.push(`Home counts history as waiting: ${JSON.stringify(home)}`);
if (home.unread !== "11") problems.push(`Home leads with the wrong number: ${JSON.stringify(home)}`);

// and reading everything must take the headline to zero while waiting stays exactly where it was
await page.click('a[href="/gitlab"]');
await page.waitForSelector('button::-p-text(Mark all read)', { timeout: 15000 });
await page.click('button::-p-text(Mark all read)');
await settle(1500);
const afterReading = await homeCounts();
console.log(`  after mark-all-read: ${afterReading.unread} unread, ${afterReading.waiting} still waiting`);
if (afterReading.unread !== "0") problems.push(`reading everything left Home at ${afterReading.unread} unread`);
if (afterReading.waiting !== "11") problems.push(`reading everything moved waiting to ${afterReading.waiting}`);
await shot("f06f-home-all-read-dark");

// mobile
await page.setViewport({ width: 430, height: 900, deviceScaleFactor: 2 });
await page.goto(`${BASE}/`, { waitUntil: "networkidle0" });
await page.waitForSelector('a[href="/gitlab"]', { timeout: 15000 });
await shot("f07-home-cards-mobile");
await page.setViewport({ width: 1440, height: 1000, deviceScaleFactor: 2 });

/*
 * the Gmail leg. verify-gmail.sh drives the same flow over real HTTP, so what is here is only what
 * needs a browser: the offer on Home, the redirect landing back on the app's own origin, mail on
 * screen, and the count the rail carries once there is more than one source to tell apart.
 */
await page.goto(`${BASE}/`, { waitUntil: "networkidle0" });
const gmailOffer = await page.waitForSelector("span::-p-text(Connect Gmail)", { timeout: 10000 }).catch(() => null);
if (gmailOffer === null) {
  problems.push("Home offered no way to connect Gmail");
} else {
  await shot("f11-home-gmail-offer-dark");
  await Promise.all([
    page.waitForNavigation({ waitUntil: "networkidle0", timeout: 30000 }).catch(() => null),
    gmailOffer.click(),
  ]);
  const railGmail = await page.waitForSelector('a[href="/gmail"]', { timeout: 30000 }).catch(() => null);
  if (railGmail === null) {
    problems.push(`connecting Gmail from Home connected nothing; it ended on ${page.url()}`);
  } else {
    const landed = new URL(page.url()).pathname;
    console.log(`  connected Gmail from the Home card, on ${landed}`);
    if (landed !== "/") problems.push(`connecting Gmail landed on ${landed}, not on home`);
    await shot("f12-home-both-sources-dark");
  }
}

await page.goto(`${BASE}/gmail`, { waitUntil: "networkidle0" });
await page.waitForSelector("time", { timeout: 20000 }).catch(() => null);
const mail = await page.evaluate(() => {
  const rows = [...document.querySelectorAll('a[href^="https://mail.google.com"]')];
  return { rows: rows.length, sent: rows.filter((a) => a.textContent.includes("Sent")).length };
});
console.log(`  ${mail.rows} mail row(s) on screen, ${mail.sent} of them sent`);
if (mail.rows === 0) problems.push("the Gmail feed shows no messages");
if (mail.sent === 0) problems.push("mail you sent never reaches the feed");
await shot("f13-gmail-feed-dark");

/*
 * the rail badge. the accessible name is the assertion, because that is the contract: the number is
 * in the link's label and the painted badge is aria-hidden, so it is announced once and not twice.
 */
const railCount = await page.evaluate(() => {
  const link = document.querySelector('a[href="/gmail"]');
  if (link === null) return null;
  const badge = link.querySelector("span.rounded-full");
  return { label: link.getAttribute("aria-label"), badge: badge === null ? null : badge.textContent };
});
console.log(`  rail Gmail link: ${JSON.stringify(railCount)}`);
if (railCount === null || !/, \d+ unread$/.test(railCount.label ?? "")) {
  problems.push(`the rail link carries no unread count in its label: ${JSON.stringify(railCount)}`);
}
if (railCount !== null && railCount.badge === null) {
  problems.push("the rail shows no badge while Gmail has unread mail");
}

// withdraw the approval upstream and let the fast sweep notice, to capture the alert
writeFileSync(`${WORK}/revoked`, "");
console.log("  withdrew the approval upstream, waiting for the sweep");
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
