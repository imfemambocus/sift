import { LoaderCircle } from "lucide-react";

/** A css keyframe rather than motion, so it needs no runtime and no variants. */
export function Spinner({ size = 14 }: { readonly size?: number }) {
	return <LoaderCircle size={size} strokeWidth={2} className="animate-spin" aria-hidden />;
}
