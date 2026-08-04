import type { ReactNode } from "react";
import { SiftMark } from "../components/SiftMark";
import { SiftedPanel } from "./SiftedPanel";

type AuthLayoutProps = {
	readonly title: string;
	readonly children: ReactNode;
	readonly footer: ReactNode;
};

export function AuthLayout({ title, children, footer }: AuthLayoutProps) {
	return (
		<div className="grid min-h-dvh lg:grid-cols-[minmax(0,1fr)_minmax(0,1.05fr)]">
			<main className="flex items-center px-7 py-14 sm:px-14">
				<div className="flex w-full max-w-88 flex-col gap-9">
					<div className="flex items-center gap-2.5 text-accent">
						<SiftMark className="size-4.5" />
						<span className="text-[15px] font-semibold tracking-[-0.02em] text-fg">Sift</span>
					</div>

					<div className="flex flex-col gap-7">
						<h1 className="text-[26px] font-semibold tracking-tight text-fg">{title}</h1>
						{children}
					</div>

					<div className="text-[13px] text-fg-muted">{footer}</div>
				</div>
			</main>

			<SiftedPanel />
		</div>
	);
}
