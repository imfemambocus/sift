import { useEffect, useRef } from "react";
import { totalUnread, useFeedSummary } from "./feed";

const STORAGE_KEY = "sift.sound";

/*
 * two notes synthesised rather than an audio file: it puts no asset in the repo and nothing in the
 * bundle, and a sine pair with a decay is the quiet end of what a notification can sound like.
 */
const NOTES: readonly { readonly hz: number; readonly after: number }[] = [
	{ hz: 784, after: 0 },
	{ hz: 1046.5, after: 0.09 },
];

const PEAK = 0.12;
const DECAY = 0.28;

const UNLOCK_EVENTS = ["pointerdown", "keydown"] as const;

let context: AudioContext | null = null;
let enabled: boolean | null = null;

export function soundEnabled(): boolean {
	enabled ??= readStored();
	return enabled;
}

export function setSoundEnabled(on: boolean) {
	// kept in memory as well, so the choice still holds where storage is refused
	enabled = on;
	try {
		window.localStorage.setItem(STORAGE_KEY, on ? "on" : "off");
	} catch {
		// private browsing can refuse storage, and the choice then lasts for this session only
	}
}

function readStored(): boolean {
	try {
		return window.localStorage.getItem(STORAGE_KEY) === "on";
	} catch {
		// silence is the safer answer for something nobody asked for yet
		return false;
	}
}

export function playUnreadSound() {
	const audio = ready();
	if (audio === null) {
		return;
	}

	const start = audio.currentTime;
	for (const note of NOTES) {
		const oscillator = audio.createOscillator();
		const gain = audio.createGain();
		oscillator.type = "sine";
		oscillator.frequency.value = note.hz;

		const at = start + note.after;
		// ramped rather than stepped, or the note opens on a click
		gain.gain.setValueAtTime(0, at);
		gain.gain.linearRampToValueAtTime(PEAK, at + 0.012);
		gain.gain.exponentialRampToValueAtTime(0.0001, at + DECAY);

		oscillator.connect(gain).connect(audio.destination);
		oscillator.start(at);
		oscillator.stop(at + DECAY + 0.02);
	}
}

function ready(): AudioContext | null {
	if (context === null) {
		if (typeof AudioContext === "undefined") {
			return null;
		}
		context = new AudioContext();
	}
	if (context.state === "suspended") {
		context.resume().catch(() => {
			// a browser refuses audio until the page is interacted with, and the next try costs nothing
		});
	}
	return context;
}

function unlock() {
	ready();
}

/**
 * A sound for something new, mounted once in the app frame beside the count on the tab. It reads the
 * same summary, so the sound and the number can never disagree.
 *
 * <p>It rings only while the window does not have focus. That is the rule the badge already follows:
 * with Sift in front of you the row arrives on screen, and a sound says nothing the list has not
 * said. It is also what keeps your own actions quiet, since you mark a row unread while looking at it.
 *
 * <p>Honest limit, the same one the badge has: a browser slows a timer in a background tab, so the
 * sound follows the arrival by up to about a minute, and a tab must be open for it at all.
 */
export function useUnreadSound() {
	const { data } = useFeedSummary();
	const unread = totalUnread(data);
	const previous = useRef<number | null>(null);

	/*
	 * a browser refuses audio until the page has been interacted with, so the first click or key press
	 * is what makes any sound possible. it is built here rather than on the first ring, which happens
	 * when the window is not focused and therefore carries no interaction of its own.
	 */
	useEffect(() => {
		for (const event of UNLOCK_EVENTS) {
			window.addEventListener(event, unlock, { once: true });
		}
		return () => {
			for (const event of UNLOCK_EVENTS) {
				window.removeEventListener(event, unlock);
			}
		};
	}, []);

	/*
	 * `data` is a dependency as well as the count: the first summary of a session usually holds the
	 * same number as the empty state, and without it the baseline would stay unset and the next
	 * arrival would be taken for the first one.
	 */
	useEffect(() => {
		if (data === undefined) {
			return;
		}

		const before = previous.current;
		previous.current = unread;

		if (before !== null && unread > before && !document.hasFocus() && soundEnabled()) {
			playUnreadSound();
		}
	}, [data, unread]);
}
