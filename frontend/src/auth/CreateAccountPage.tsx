import type { FormEvent } from "react";
import { Link } from "react-router";
import { Button } from "../components/Button";
import { Field } from "../components/Field";
import { FormError } from "../components/FormError";
import { AuthLayout } from "./AuthLayout";
import { errorMessage } from "../lib/api";
import { useCreateAccount } from "./session";

const MINIMUM_PASSWORD_LENGTH = 12;

export function CreateAccountPage() {
	const createAccount = useCreateAccount();
	const message = errorMessage(createAccount.error);

	function handleSubmit(event: FormEvent<HTMLFormElement>) {
		event.preventDefault();
		const data = new FormData(event.currentTarget);
		createAccount.mutate({
			email: String(data.get("email") ?? ""),
			displayName: String(data.get("displayName") ?? ""),
			password: String(data.get("password") ?? ""),
		});
	}

	return (
		<AuthLayout
			title="Create your account"
			footer={
				<>
					Already set up?{" "}
					<Link to="/sign-in" className="text-fg underline decoration-border underline-offset-4 hover:decoration-accent">
						Sign in
					</Link>
				</>
			}
		>
			<form onSubmit={handleSubmit} className="flex flex-col gap-4">
				<Field label="Name" name="displayName" type="text" autoComplete="name" maxLength={100} required />
				<Field label="Email" name="email" type="email" autoComplete="email" required />
				<Field
					label="Password"
					name="password"
					type="password"
					autoComplete="new-password"
					minLength={MINIMUM_PASSWORD_LENGTH}
					required
					hint={`At least ${MINIMUM_PASSWORD_LENGTH} characters.`}
				/>
				{message !== null && <FormError>{message}</FormError>}
				<Button type="submit" disabled={createAccount.isPending} className="mt-1">
					{createAccount.isPending ? "Creating account" : "Create account"}
				</Button>
			</form>
		</AuthLayout>
	);
}
