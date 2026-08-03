import type { ComponentPropsWithoutRef } from "react";

type Variant = "primary" | "ghost";

const VARIANT: Record<Variant, string> = {
	primary: "bg-accent text-accent-fg hover:brightness-110 disabled:hover:brightness-100",
	ghost: "border border-border text-fg hover:bg-raised",
};

type ButtonProps = ComponentPropsWithoutRef<"button"> & {
	readonly variant?: Variant;
};

export function Button({ variant = "primary", className = "", ...props }: ButtonProps) {
	return (
		<button
			{...props}
			className={`flex h-10 items-center justify-center rounded-control px-4 text-[13px] font-medium transition-[filter,background-color] disabled:cursor-not-allowed disabled:opacity-55 ${VARIANT[variant]} ${className}`}
		/>
	);
}
