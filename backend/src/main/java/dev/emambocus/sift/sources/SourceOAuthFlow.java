package dev.emambocus.sift.sources;

import dev.emambocus.sift.credential.SourceCredential;
import dev.emambocus.sift.credential.SourceType;

/**
 * One source's authorization code flow, reduced to the parts that are the same for all of them.
 *
 * <p>Every flow agrees on what the controller does: make a state and a verifier, send the browser to
 * an authorize URL, take a code back, and store a pair of tokens. They disagree only on the URLs, the
 * scope and the parameters, which is exactly what stays behind the interface.
 *
 * <p>Renewing a token is deliberately not here. Each adapter renews inside its own fetch, because
 * only the adapter knows when it is about to make a call.
 */
public interface SourceOAuthFlow {

	SourceType source();

	/** Whether this deployment has an application registered, so anything can be connected at all. */
	boolean configured();

	/** What the connect screen names: a GitLab instance, or the mail provider. */
	String target();

	/** What goes in {@code instance_url}, which is the account's own home rather than an API base. */
	String accountUrl();

	String authorizeUrl(String state, String codeVerifier);

	OAuthTokens exchange(String code, String codeVerifier);

	/**
	 * Withdraws the grant at the source, so disconnecting here ends the access there too rather than
	 * leaving a live token until it expires on its own.
	 *
	 * <p>Best effort by contract: the caller is deleting the credential either way, and a provider
	 * that will not take the request must not stop somebody disconnecting.
	 */
	void revoke(SourceCredential credential);
}
