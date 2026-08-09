import { RefreshCw } from "lucide-react";
import { Button } from "../components/Button";
import { FormError } from "../components/FormError";
import { Spinner } from "../components/Spinner";
import { errorMessage } from "../lib/api";
import { isReading } from "./labels";
import type { SourceStatus } from "./sources";
import { useSyncSource } from "./sources";

/*
 * exists so nobody has to disconnect and reconnect a source to force it to catch up, which is what
 * people reach for when a background sweep is the only way anything updates. the feed pages use
 * LastSynced instead, which says when it last happened as well as offering to do it again.
 */
export function CheckNowButton({ source }: { readonly source: SourceStatus }) {
	const sync = useSyncSource(source.source);
	const message = errorMessage(sync.error);
	/*
	 * the spinner follows the source and the word follows this button, so a read the server started
	 * turns it without taking the name of the action away from somebody who wants to ask for another.
	 */
	const spinning = sync.isPending || isReading(source);

	return (
		<div className="flex flex-col items-start gap-2">
			<Button variant="ghost" onClick={() => sync.mutate()} disabled={sync.isPending}>
				{spinning ? <Spinner /> : <RefreshCw size={14} strokeWidth={1.75} aria-hidden />}
				{sync.isPending ? "Checking" : "Check now"}
			</Button>
			{message !== null && <FormError>{message}</FormError>}
		</div>
	);
}
