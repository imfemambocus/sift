import { Search, X } from "lucide-react";
import type { KeyboardEvent } from "react";

type SearchFieldProps = {
	readonly value: string;
	readonly onChange: (value: string) => void;
};

/**
 * A field in the app frame, not a Cmd+K palette: a shortcut nobody is told about is a feature nobody
 * finds. It is on every page and always searches every connected source, whichever tab you are on.
 */
export function SearchField({ value, onChange }: SearchFieldProps) {
	function onKeyDown(event: KeyboardEvent<HTMLInputElement>) {
		// escape clears it and hands the page back, so there is a way out without reaching for the mouse
		if (event.key === "Escape") {
			onChange("");
		}
	}

	return (
		<search className="relative flex items-center">
			<Search
				aria-hidden
				size={15}
				strokeWidth={1.75}
				className="pointer-events-none absolute left-3 text-fg-muted/70"
			/>
			<input
				type="search"
				value={value}
				onChange={(event) => onChange(event.target.value)}
				onKeyDown={onKeyDown}
				aria-label="Search everything"
				placeholder="Search everything, or narrow it: is:unread, is:mr, project:web, from:maxime"
				className="h-10 w-full rounded-control border border-border bg-surface pl-9 pr-10 text-[13px] text-fg transition-colors placeholder:text-fg-muted/70 focus:border-accent focus:outline-none"
			/>
			{value !== "" && (
				<button
					type="button"
					onClick={() => onChange("")}
					aria-label="Clear the search"
					className="absolute right-2 flex size-6 items-center justify-center rounded-control text-fg-muted/70 transition-colors hover:text-fg"
				>
					<X size={14} strokeWidth={2} />
				</button>
			)}
		</search>
	);
}
