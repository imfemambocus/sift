import type { ReactNode } from "react";

export function FormError({ children }: { readonly children: ReactNode }) {
	return (
		<p role="alert" className="rounded-control border border-danger/35 bg-danger/8 px-3 py-2 text-[13px] text-danger">
			{children}
		</p>
	);
}
