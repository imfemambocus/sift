import { Plus } from "lucide-react";
import { Link } from "react-router";
import { Spinner } from "../components/Spinner";
import { sourceIcon, sourceName, sourceOffer } from "../sources/labels";
import type { Connector } from "../sources/sources";
import { useStartOAuth } from "../sources/sources";

/*
 * the same shape and the same place in the grid as a connected source's card, so the dashboard shows
 * what Sift could hold rather than only what it already does. dashed rather than solid, because a
 * card offering something is not a card reporting something.
 */
const CARD =
	"flex min-h-40 flex-col items-start gap-3 rounded-panel border border-dashed border-border bg-surface/40 px-5 py-4 text-left transition-colors hover:border-accent/50 hover:bg-surface";

export function ConnectCard({ connector }: { readonly connector: Connector }) {
	const start = useStartOAuth(connector.source);
	const Icon = sourceIcon(connector.source);
	const name = sourceName(connector.source);

	/*
	 * an unconfigured source cannot be connected by anybody clicking here: it needs an application
	 * registered and four values in .env. so the card says where to read about that rather than
	 * offering a button that would answer 404.
	 */
	if (!connector.configured) {
		return (
			<Link to="/settings" className={CARD}>
				<Header icon={<Icon size={16} strokeWidth={1.75} aria-hidden />} name={name} />
				<p className="text-[13px] leading-relaxed text-fg-muted">{sourceOffer(connector.source)}</p>
				<span className="mt-auto text-[12px] text-fg-muted underline decoration-border underline-offset-4">
					Needs setting up first
				</span>
			</Link>
		);
	}

	return (
		<button type="button" onClick={() => start.mutate()} disabled={start.isPending} className={CARD}>
			<Header icon={<Icon size={16} strokeWidth={1.75} aria-hidden />} name={name} />
			<p className="text-[13px] leading-relaxed text-fg-muted">{sourceOffer(connector.source)}</p>

			<span className="mt-auto flex items-center gap-1.5 text-[13px] text-accent">
				{start.isPending ? <Spinner /> : <Plus size={14} strokeWidth={2} aria-hidden />}
				{start.isPending ? `Opening ${name}` : `Connect ${name}`}
			</span>
		</button>
	);
}

type HeaderProps = {
	readonly icon: React.ReactNode;
	readonly name: string;
};

function Header({ icon, name }: HeaderProps) {
	return (
		<span className="flex items-center gap-2 text-fg-muted">
			{icon}
			<span className="text-[14px] font-semibold tracking-tight text-fg">{name}</span>
		</span>
	);
}
