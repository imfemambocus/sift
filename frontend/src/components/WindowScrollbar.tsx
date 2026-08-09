import { useCallback, useEffect, useRef, useState } from "react";

const MIN_THUMB = 36;
const EDGE_INSET = 4;
// the thumb stops short of the top and bottom edges rather than running into them
const TRACK_INSET = 12;
const FADE_AFTER = 1100;

type Metrics = { readonly height: number; readonly top: number };

/*
 * The document stays the scroller and only its native scrollbar is hidden, rather than moving
 * content into an inner overflow container. That keeps every native behaviour intact: space and
 * page keys, arrow keys without needing focus, scroll anchoring, and scroll-into-view on tab.
 * An inner scroll container silently breaks keyboard scrolling until something inside it is focused.
 */
export function WindowScrollbar() {
	const [metrics, setMetrics] = useState<Metrics>({ height: 0, top: 0 });
	const [visible, setVisible] = useState(false);
	const [dragging, setDragging] = useState(false);
	const fadeTimer = useRef<number | undefined>(undefined);

	const measure = useCallback(() => {
		const doc = document.documentElement;
		const viewport = window.innerHeight;
		const total = doc.scrollHeight;

		if (total <= viewport + 1) {
			setMetrics({ height: 0, top: 0 });
			return;
		}

		const track = viewport - TRACK_INSET * 2;
		const height = Math.min(track, Math.max(MIN_THUMB, (viewport / total) * track));
		const travel = track - height;
		const progress = Math.min(1, Math.max(0, window.scrollY / (total - viewport)));
		setMetrics({ height, top: TRACK_INSET + travel * progress });
	}, []);

	const reveal = useCallback(() => {
		setVisible(true);
		window.clearTimeout(fadeTimer.current);
		fadeTimer.current = window.setTimeout(() => setVisible(false), FADE_AFTER);
	}, []);

	useEffect(() => {
		measure();

		const onScroll = () => {
			measure();
			reveal();
		};
		window.addEventListener("scroll", onScroll, { passive: true });
		window.addEventListener("resize", measure);

		// the document grows when a feed loads, which changes the thumb without any scrolling
		const observer = new ResizeObserver(measure);
		observer.observe(document.documentElement);

		return () => {
			window.removeEventListener("scroll", onScroll);
			window.removeEventListener("resize", measure);
			observer.disconnect();
			window.clearTimeout(fadeTimer.current);
		};
	}, [measure, reveal]);

	function handlePointerDown(event: React.PointerEvent<HTMLDivElement>) {
		event.preventDefault();
		setDragging(true);
		setVisible(true);
		window.clearTimeout(fadeTimer.current);

		const startY = event.clientY;
		const startScroll = window.scrollY;
		const viewport = window.innerHeight;
		const scrollable = document.documentElement.scrollHeight - viewport;
		const travel = viewport - TRACK_INSET * 2 - metrics.height;

		const onMove = (move: PointerEvent) => {
			const moved = move.clientY - startY;
			window.scrollTo({ top: startScroll + (moved / travel) * scrollable });
		};
		const onUp = () => {
			setDragging(false);
			reveal();
			window.removeEventListener("pointermove", onMove);
			window.removeEventListener("pointerup", onUp);
		};

		window.addEventListener("pointermove", onMove);
		window.addEventListener("pointerup", onUp);
	}

	if (metrics.height === 0) {
		return null;
	}

	return (
		<div
			aria-hidden
			onPointerDown={handlePointerDown}
			style={{ height: metrics.height, top: metrics.top, right: EDGE_INSET }}
			// hidden on a touch-sized screen, where the browser draws nothing to replace and a thumb
			// this narrow is a target nobody can hit
			className={`fixed z-50 hidden w-1.5 cursor-grab rounded-full bg-fg/25 transition-opacity duration-200 hover:bg-fg/40 sm:block ${
				visible || dragging ? "opacity-100" : "opacity-0"
			}`}
		/>
	);
}
