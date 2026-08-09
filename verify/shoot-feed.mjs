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
 * approves at once and sends it back to the callback, which stores the credential, starts the first
 * read and redirects to home without waiting for it. the token never touches this page, which is the
 * point of the flow.
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

/*
 * what the prefixes are is on a panel behind an icon in the field, since a placeholder cannot hold
 * eight of them. clicking into the field must not open it: the field is focusable too, and a panel
 * that opened on every click would cover the results it was narrowing.
 */
const helpVisible = () => page.$eval('[role="tooltip"]', (panel) => getComputedStyle(panel).visibility === "visible");
await page.click(SEARCH);
await settle(300);
if (await helpVisible()) problems.push("the search help opened just from clicking into the field");
await page.hover('button[aria-label="What you can search for"]');
await settle(400);
const helpOpen = await helpVisible();
const helpSays = await page.$eval('[role="tooltip"]', (panel) => panel.textContent ?? "");
console.log(`  search help on hover: ${helpOpen}, naming ${["is:unread", "has:attachment", "after:7d", "from:"].filter((t) => helpSays.includes(t)).length} of 4 prefixes`);
if (!helpOpen) problems.push("hovering the search help icon showed nothing");
if (!helpSays.includes("has:attachment")) problems.push("the search help does not name every prefix");
await shot("f06h-search-help-dark");
// off the icon, or the panel stays open over the controls the next checks press
await page.mouse.move(0, 0);
await settle(300);

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
  /*
   * the card is an article, and its link covers it from the heading rather than wrapping it, so the
   * refresh button can be a sibling of that link. the rail links to /gitlab too, so the href never
   * identified the card on its own.
   */
  await page.waitForFunction(() => [...document.querySelectorAll("article")]
    .some((el) => el.textContent.includes("waiting")), { timeout: 15000 });
  return page.evaluate(() => {
    const card = [...document.querySelectorAll("article")]
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

/*
 * the card refreshes its own source. the button is a sibling of the card's link rather than inside it,
 * which is what this checks: nested, the browser would follow the link on every press.
 */
const cardRefresh = await page.$('article button[aria-label^="Check GitLab"]');
if (cardRefresh === null) {
  problems.push("the Home card offers no way to check the source again");
} else {
  // hold the read open, so the picture below is of a card being read rather than one that has been
  writeFileSync(`${WORK}/feed-slow`, "");
  await cardRefresh.click();
  /*
   * Home must not skeleton for a refresh somebody pressed. the card already turns its own icon and
   * says so in words, and a skeleton would take away the card that was asked about, every other
   * card, and the offers to connect. so the cards stay, and only the card's own words change.
   */
  /*
   * waited for rather than read once, since the read itself has to still be running. it settles on
   * whichever happens first, a skeleton or a card saying so, so each way of getting this wrong is
   * reported as itself rather than as "nothing appeared".
   */
  const duringRefresh = await page.waitForFunction(() => {
    const skeleton = document.querySelector("div.animate-pulse") !== null;
    const card = document.querySelector("article");
    const icon = document.querySelector('article button[aria-label^="Check"] svg');
    const saying = card?.textContent?.includes("Syncing now") === true;
    const turning = icon?.classList.contains("animate-spin") === true;
    // the turning icon is the earliest of the three, so waiting on it reports the other two as
    // themselves rather than as the read having finished before anything was looked at
    if (!skeleton && !turning) return false;
    return { skeleton, saying, turning, cards: document.querySelectorAll("article").length };
  }, { timeout: 5000 }).then((handle) => handle.jsonValue()).catch(() => null);

  if (duringRefresh === null) {
    problems.push("Home showed nothing at all while it read a source");
  } else {
    console.log(`  Home during a pressed refresh: ${duringRefresh.cards} card(s) kept, skeleton ${duringRefresh.skeleton}, saying ${duringRefresh.saying}, turning ${duringRefresh.turning}`);
    if (duringRefresh.skeleton) problems.push("pressing refresh on Home replaced the cards with a skeleton");
    if (duringRefresh.cards === 0) problems.push("pressing refresh on Home took the cards away");
    if (!duringRefresh.saying) problems.push("the Home card does not say it is syncing while it syncs");
    if (!duringRefresh.turning) problems.push("the Home card's refresh icon does not turn while it syncs");
  }
  await shot("f06g-home-refresh-in-flight-dark");
  try { unlinkSync(`${WORK}/feed-slow`); } catch { /* already gone */ }

  await settle(5000);
  const landed = new URL(page.url()).pathname;
  console.log(`  the Home card's refresh left the browser on ${landed}`);
  if (landed !== "/") problems.push(`the Home card's refresh navigated to ${landed} instead of refreshing`);
}

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
const railBox = async () => page.$eval('nav[aria-label="Sections"]', (nav) => {
  const box = nav.getBoundingClientRect();
  return { left: box.left, top: box.top, width: box.width, height: box.height };
});

const onWide = await railBox();
if (onWide.left !== 0 || onWide.height < 500) {
  problems.push(`the rail is not a column beside the content: ${JSON.stringify(onWide)}`);
}

await page.setViewport({ width: 430, height: 900, deviceScaleFactor: 2 });
await page.goto(`${BASE}/`, { waitUntil: "networkidle0" });
await page.waitForSelector('a[href="/gitlab"]', { timeout: 15000 });
await shot("f07-home-cards-mobile");

// the rail crosses the bottom of a narrow screen, where a thumb reaches it
const onNarrow = await railBox();
if (onNarrow.width < 430 || onNarrow.top + onNarrow.height < 890) {
  problems.push(`the rail is not a bar along the bottom: ${JSON.stringify(onNarrow)}`);
}
// the bar is fixed, so the room it takes has to be given back: the last row must clear it
await page.goto(`${BASE}/gitlab`, { waitUntil: "networkidle0" });
await settle(600);
await page.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight));
await settle(400);
const clearance = await page.evaluate(() => {
  const rows = [...document.querySelectorAll('a[href^="https://gitlab.example.org"]')];
  const last = rows.at(-1);
  const nav = document.querySelector('nav[aria-label="Sections"]');
  if (last === undefined || nav === null) return null;
  return nav.getBoundingClientRect().top - last.getBoundingClientRect().bottom;
});
await shot("f07b-gitlab-feed-mobile");
if (clearance === null) problems.push("no feed rows on a narrow screen");
else if (clearance < 0) problems.push(`the last row sits ${-clearance}px under the rail`);

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
