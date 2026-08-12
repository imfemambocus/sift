import { Volume2, VolumeOff } from "lucide-react";
import type { LucideIcon } from "lucide-react";
import { useState } from "react";
import { playUnreadSound, setSoundEnabled, soundEnabled } from "./unreadSound";

const OPTIONS: readonly { readonly on: boolean; readonly label: string; readonly icon: LucideIcon }[] = [
	{ on: false, label: "Off", icon: VolumeOff },
	{ on: true, label: "On", icon: Volume2 },
];

export function SoundChoice() {
	const [on, setOn] = useState(soundEnabled);

	const choose = (next: boolean) => {
		setOn(next);
		setSoundEnabled(next);
		/*
		 * turning it on plays it once. the sound is only ever heard while the window is not focused,
		 * so without this nobody can find out what they have just asked for.
		 */
		if (next) {
			playUnreadSound();
		}
	};

	return (
		<div className="flex flex-col gap-2">
			<div className="inline-flex self-start rounded-control border border-border bg-surface p-1">
				{OPTIONS.map((option) => {
					const Icon = option.icon;
					const selected = option.on === on;
					return (
						<button
							key={option.label}
							type="button"
							onClick={() => choose(option.on)}
							aria-pressed={selected}
							className={`flex items-center gap-2 rounded-sm px-3 py-1.5 text-[13px] transition-colors ${
								selected ? "bg-accent text-accent-fg" : "text-fg-muted hover:text-fg"
							}`}
						>
							<Icon size={14} strokeWidth={1.75} />
							{option.label}
						</button>
					);
				})}
			</div>
			<p className="text-[13px] leading-relaxed text-fg-muted">
				Sift makes a short sound when something new arrives and you are looking somewhere else. It
				stays quiet while Sift is the window in front of you. A tab must be open for it.
			</p>
		</div>
	);
}
