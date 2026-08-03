import { useId } from "react";
import type { ComponentPropsWithoutRef } from "react";

type FieldProps = ComponentPropsWithoutRef<"input"> & {
	readonly label: string;
	readonly hint?: string;
};

export function Field({ label, hint, className = "", ...inputProps }: FieldProps) {
	const generatedId = useId();
	const inputId = inputProps.id ?? generatedId;
	const hintId = `${inputId}-hint`;

	return (
		<div className="flex flex-col gap-1.5">
			<label htmlFor={inputId} className="text-[13px] font-medium text-fg">
				{label}
			</label>
			<input
				{...inputProps}
				id={inputId}
				aria-describedby={hint === undefined ? undefined : hintId}
				className={`h-10 rounded-control border border-border bg-surface px-3 text-[13px] text-fg transition-colors placeholder:text-fg-muted/70 hover:border-fg-muted/50 ${className}`}
			/>
			{hint !== undefined && (
				<p id={hintId} className="text-xs text-fg-muted">
					{hint}
				</p>
			)}
		</div>
	);
}
