import type { ReactNode } from "react";

type PageProps = {
	readonly title: string;
	readonly description?: string;
	readonly children: ReactNode;
};

export function Page({ title, description, children }: PageProps) {
	return (
		<div className="mx-auto flex w-full max-w-3xl flex-col gap-8 px-8 py-12">
			<header className="flex flex-col gap-1.5">
				<h1 className="text-[20px] font-semibold tracking-[-0.02em] text-fg">{title}</h1>
				{description !== undefined && <p className="text-[13px] text-fg-muted">{description}</p>}
			</header>
			{children}
		</div>
	);
}
