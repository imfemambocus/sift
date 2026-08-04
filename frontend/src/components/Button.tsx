import type { ComponentPropsWithoutRef } from "react";

/*
 * five weights rather than three, because actions that sit next to each other have to look
 * different: filling in a token, checking a source and disconnecting it are not the same risk, and
 * a row of identical outlined buttons says they are.
 */
type Variant = "primary" | "ghost" | "subtle" | "danger" | "dangerSolid";

const VARIANT: Record<Variant, string> = {
	primary: "bg-accent text-accent-fg hover:brightness-110 disabled:hover:brightness-100",
	ghost: "border border-border text-fg hover:bg-raised",
	subtle: "text-fg-muted hover:bg-raised hover:text-fg",
	danger: "border border-danger/55 text-danger hover:bg-danger/10",
	dangerSolid: "bg-danger text-danger-fg hover:brightness-110 disabled:hover:brightness-100",
};

type ButtonProps = ComponentPropsWithoutRef<"button"> & {
	readonly variant?: Variant;
};

export function Button({ variant = "primary", className = "", ...props }: ButtonProps) {
	return (
		<button
			{...props}
			className={`flex h-10 items-center justify-center gap-2 rounded-control px-4 text-[13px] font-medium transition-[filter,background-color,color] disabled:cursor-not-allowed disabled:opacity-55 ${VARIANT[variant]} ${className}`}
		/>
	);
}
