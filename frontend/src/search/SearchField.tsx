import { CircleHelp, Search, X } from "lucide-react";
import { Fragment, useId } from "react";
import type { KeyboardEvent } from "react";

type SearchFieldProps = {
	readonly value: string;
	readonly onChange: (value: string) => void;
};

/** What a scope prefix narrows on, said once here rather than in a placeholder that cannot hold it. */
const SCOPES: readonly { readonly tokens: string; readonly says: string }[] = [
	{ tokens: "is:unread is:read", says: "What you have not looked at, or what you have." },
	{ tokens: "is:mr is:issue", says: "Merge requests, or issues." },
	{ tokens: "is:mail is:sent", says: "Any kind of event, called by its own name." },
	{ tokens: "project:web", says: "The project, or a sender's address." },
	{ tokens: "from:ada", says: "The name it is from." },
	{ tokens: "has:attachment", says: "Only rows that carry a file." },
	{ tokens: "after:7d", says: "Active since then. Units are h, d, w, m and y." },
	{ tokens: "before:2026-08-01", says: "Active before then. A date or a span." },
];

/**
 * A field in the app frame, not a Cmd+K palette: a shortcut nobody is told about is a feature nobody
 * finds. It is on every page and always searches every connected source, whichever tab you are on.
 */
export function SearchField({ value, onChange }: SearchFieldProps) {
	const helpId = useId();

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
				placeholder="Search everything, or narrow it: is:unread, after:7d, has:attachment"
				className="h-10 w-full rounded-control border border-border bg-surface pl-9 pr-16 text-[13px] text-fg transition-colors placeholder:text-fg-muted/70"
			/>
			{value !== "" && (
				<button
					type="button"
					onClick={() => onChange("")}
					aria-label="Clear the search"
					className="absolute right-9 flex size-6 items-center justify-center rounded-control text-fg-muted/70 transition-colors hover:text-fg"
				>
					<X size={14} strokeWidth={2} />
				</button>
			)}

			{/*
			  * its own group, and never the whole field: the input is focusable too, so a group around
			  * that would open this every time somebody clicked into the search.
			  */}
			<span className="group/help absolute right-2 flex items-center">
				<button
					type="button"
					aria-label="What you can search for"
					aria-describedby={helpId}
					className="flex size-6 items-center justify-center rounded-control text-fg-muted/70 transition-colors hover:text-fg"
				>
					<CircleHelp size={14} strokeWidth={1.75} />
				</button>

				{/*
				  * it takes no pointer at all: it is text, it opens over the controls under the field, and
				  * a panel that ate the click somebody aimed at one of them would be worse than one that
				  * closes as they reach for it.
				  */}
				<div
					id={helpId}
					role="tooltip"
					className="pointer-events-none invisible absolute -right-2 top-full z-20 mt-2 w-88 max-w-[calc(100vw-2rem)] rounded-panel border border-border bg-surface p-3.5 opacity-0 shadow-lg transition-opacity group-hover/help:visible group-hover/help:opacity-100 group-focus-within/help:visible group-focus-within/help:opacity-100"
				>
					<p className="mb-3 text-[12px] leading-snug text-fg-muted">
						Every word has to match, in any order, and one typo in each is forgiven. Each prefix
						below narrows further, so two of them keep only what answers both.
					</p>
					<dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1.5">
						{SCOPES.map((scope) => (
							<Fragment key={scope.tokens}>
								<dt className="font-mono text-[11px] whitespace-nowrap text-fg">{scope.tokens}</dt>
								<dd className="text-[12px] leading-snug text-fg-muted">{scope.says}</dd>
							</Fragment>
						))}
					</dl>
				</div>
			</span>
		</search>
	);
}
