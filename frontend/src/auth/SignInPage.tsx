import type { FormEvent } from "react";
import { Link } from "react-router";
import { Button } from "../components/Button";
import { Field } from "../components/Field";
import { FormError } from "../components/FormError";
import { AuthLayout } from "./AuthLayout";
import { errorMessage } from "../lib/api";
import { useSignIn } from "./session";

export function SignInPage() {
	const signIn = useSignIn();
	const message = errorMessage(signIn.error);

	function handleSubmit(event: FormEvent<HTMLFormElement>) {
		event.preventDefault();
		const data = new FormData(event.currentTarget);
		signIn.mutate({
			email: String(data.get("email") ?? ""),
			password: String(data.get("password") ?? ""),
		});
	}

	return (
		<AuthLayout
			title="Sign in"
			footer={
				<>
					No account yet?{" "}
					<Link to="/create-account" className="text-fg underline decoration-border underline-offset-4 hover:decoration-accent">
						Create one
					</Link>
				</>
			}
		>
			<form onSubmit={handleSubmit} className="flex flex-col gap-4">
				<Field label="Email" name="email" type="email" autoComplete="email" required />
				<Field label="Password" name="password" type="password" autoComplete="current-password" required />
				{message !== null && <FormError>{message}</FormError>}
				<Button type="submit" disabled={signIn.isPending} className="mt-1">
					{signIn.isPending ? "Signing in" : "Sign in"}
				</Button>
			</form>
		</AuthLayout>
	);
}
