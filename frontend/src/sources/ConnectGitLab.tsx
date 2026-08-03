import { useState } from "react";
import type { FormEvent } from "react";
import { Button } from "../components/Button";
import { Field } from "../components/Field";
import { FormError } from "../components/FormError";
import { errorMessage } from "../lib/api";
import { shortAgo } from "../lib/time";
import type { SourceStatus } from "./sources";
import { useConnectSource, useDisconnectSource, useSource } from "./sources";

const SOURCE = "gitlab";

type Tone = "ok" | "warn" | "error";

const TONE_CLASS: Record<Tone, string> = {
	ok: "text-fg",
	warn: "text-fg-muted",
	error: "text-danger",
};

function statusLine(source: SourceStatus): { tone: Tone; text: string } {
	if (source.status === "AUTH_FAILED") {
		return { tone: "error", text: "GitLab rejected the token. Replace it below to fix this." };
	}
	if (source.status === "ERROR") {
		return { tone: "warn", text: "The last read failed. Sift will try again on its own." };
	}
	if (source.status === "NEVER_RUN") {
		return { tone: "warn", text: "Connected. Waiting for the first read." };
	}
	if (source.lastSyncAt === null) {
		return { tone: "ok", text: "Connected." };
	}
	return { tone: "ok", text: `Connected. Last read ${shortAgo(source.lastSyncAt)} ago.` };
}

/*
 * gitlab can prefill its token form from a link. the settings path moved in recent versions, so
 * this is a convenience and not a promise; typing the token by hand works either way.
 */
function tokenPageUrl(instanceUrl: string): string | null {
	const trimmed = instanceUrl.trim().replace(/\/+$/, "");
	if (!/^https?:\/\/\S+$/i.test(trimmed)) {
		return null;
	}
	return `${trimmed}/-/user_settings/personal_access_tokens?name=Sift&scopes=read_api`;
}

type SourceCardProps = {
	readonly source: SourceStatus;
	readonly canReplace: boolean;
	readonly onReplace: () => void;
	readonly onDisconnect: () => void;
	readonly disconnecting: boolean;
};

function SourceCard({ source, canReplace, onReplace, onDisconnect, disconnecting }: SourceCardProps) {
	const [confirming, setConfirming] = useState(false);
	const status = statusLine(source);

	return (
		<div className="flex w-full flex-col gap-3 rounded-panel border border-border bg-surface px-4 py-3.5">
			<p className="font-mono text-[12px] text-fg">{source.instanceUrl}</p>

			<p className={`text-[13px] ${TONE_CLASS[status.tone]}`}>{status.text}</p>

			{source.lastError !== null && (
				<p className="font-mono text-[11px] leading-relaxed text-fg-muted">{source.lastError}</p>
			)}

			<p className="text-[13px] text-fg-muted">
				{source.itemCount} {source.itemCount === 1 ? "item" : "items"} in your feed.
			</p>

			<div className="flex items-center gap-2">
				{canReplace && (
					<Button variant="ghost" onClick={onReplace}>
						Replace token
					</Button>
				)}
				{confirming ? (
					<Button variant="danger" onClick={onDisconnect} disabled={disconnecting}>
						Confirm disconnect
					</Button>
				) : (
					<Button variant="ghost" onClick={() => setConfirming(true)}>
						Disconnect
					</Button>
				)}
			</div>
		</div>
	);
}

export function ConnectGitLab() {
	const { data: source } = useSource(SOURCE);
	const connect = useConnectSource(SOURCE);
	const disconnect = useDisconnectSource(SOURCE);

	const [instanceUrl, setInstanceUrl] = useState("");
	const [replacing, setReplacing] = useState(false);

	// a rejected token opens the form on its own: there is nothing else the user can usefully do
	const showForm = source === undefined || replacing || source.status === "AUTH_FAILED";
	const message = errorMessage(connect.error) ?? errorMessage(disconnect.error);
	const tokenLink = tokenPageUrl(instanceUrl);

	function handleSubmit(event: FormEvent<HTMLFormElement>) {
		event.preventDefault();
		// captured now: currentTarget is nulled once the handler returns, well before onSuccess runs
		const form = event.currentTarget;
		const token = String(new FormData(form).get("token") ?? "");

		connect.mutate(
			{ instanceUrl, token },
			{
				onSuccess: () => {
					setReplacing(false);
					form.reset();
				},
			},
		);
	}

	return (
		<section className="flex w-full flex-col items-start gap-3">
			<h2 className="eyebrow">GitLab</h2>

			{source !== undefined && (
				<SourceCard
					source={source}
					canReplace={!showForm}
					onReplace={() => setReplacing(true)}
					onDisconnect={() => disconnect.mutate()}
					disconnecting={disconnect.isPending}
				/>
			)}

			{showForm && (
				<form onSubmit={handleSubmit} className="flex w-full max-w-sm flex-col gap-4">
					{source === undefined && (
						<p className="text-[13px] leading-relaxed text-fg-muted">
							Sift reads your GitLab to-do list, which is already only about you. It needs a personal
							access token with the <span className="font-mono text-[12px] text-fg">read_api</span> scope,
							which cannot change anything.
						</p>
					)}

					<Field
						label="Instance URL"
						name="instanceUrl"
						type="url"
						placeholder="https://gitlab.example.org"
						value={instanceUrl}
						onChange={(event) => setInstanceUrl(event.target.value)}
						required
					/>

					<Field
						label="Access token"
						name="token"
						type="password"
						autoComplete="off"
						placeholder="glpat-..."
						required
						hint={tokenLink === null ? "Fill in your instance URL and a link to create one appears." : undefined}
					/>

					{tokenLink !== null && (
						<a
							href={tokenLink}
							target="_blank"
							rel="noreferrer"
							className="text-[13px] text-fg underline decoration-border underline-offset-4 hover:decoration-accent"
						>
							Create a read_api token on your instance
						</a>
					)}

					{message !== null && <FormError>{message}</FormError>}

					<div className="flex items-center gap-2">
						<Button type="submit" disabled={connect.isPending}>
							{connect.isPending ? "Connecting" : "Connect GitLab"}
						</Button>
						{replacing && (
							<Button type="button" variant="ghost" onClick={() => setReplacing(false)}>
								Cancel
							</Button>
						)}
					</div>
				</form>
			)}
		</section>
	);
}
