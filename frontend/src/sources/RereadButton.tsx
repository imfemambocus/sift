import { History } from "lucide-react";
import { useState } from "react";
import { Button } from "../components/Button";
import { FormError } from "../components/FormError";
import { Spinner } from "../components/Spinner";
import { errorMessage } from "../lib/api";
import { sourceName } from "./labels";
import type { SourceStatus } from "./sources";
import { useRereadSource } from "./sources";

/*
 * for a source whose history Sift walks a chunk at a time: it reads a message once and never looks
 * at it again, so anything the rows learned to carry after that message arrived is missing from it.
 * this is the way to fill those in without disconnecting, which would throw the rows away instead.
 *
 * it sits below the row that connects and disconnects rather than in it. those three are about the
 * connection and this is about what Sift holds, and one sentence of warning belongs with it.
 */
export function RereadButton({ source }: { readonly source: SourceStatus }) {
	const [confirming, setConfirming] = useState(false);
	const reread = useRereadSource(source.source);
	const message = errorMessage(reread.error);

	if (!source.canReread) {
		return null;
	}

	return (
		<div className="flex flex-col items-start gap-2">
			{confirming && (
				<p className="text-[12px] leading-relaxed text-fg-muted">
					Every row stays where it is, and what you have read stays read. Sift reads all of{" "}
					{sourceName(source.source)} again from the newest end, which takes as long as it did the first
					time.
				</p>
			)}

			<div className="flex flex-wrap items-center gap-2">
				{confirming ? (
					<>
						<Button
							onClick={() => {
								reread.mutate();
								setConfirming(false);
							}}
							disabled={reread.isPending}
						>
							{reread.isPending && <Spinner />}
							Yes, read it all again
						</Button>
						<Button variant="subtle" onClick={() => setConfirming(false)} disabled={reread.isPending}>
							Cancel
						</Button>
					</>
				) : (
					/*
					 * a read already running would claim this one: the state is forgotten and nothing reads
					 * it until the next pass, so the press would look like it did nothing at all.
					 */
					<Button variant="subtle" onClick={() => setConfirming(true)} disabled={source.syncing}>
						<History size={14} strokeWidth={1.75} aria-hidden />
						Read it all again
					</Button>
				)}
			</div>

			{message !== null && <FormError>{message}</FormError>}
		</div>
	);
}
