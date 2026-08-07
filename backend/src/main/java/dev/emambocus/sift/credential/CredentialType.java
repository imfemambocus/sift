package dev.emambocus.sift.credential;

/**
 * One value today, and the column stays anyway. It is the same bet {@code source_credentials} makes
 * by being keyed on {@code (user_id, source)} before a second source existed: a credential kind that
 * is not an OAuth grant (an app password, say) then needs no migration.
 *
 * <p>{@code PERSONAL_ACCESS_TOKEN} was removed on 2026-08-07 with the pasted-token path itself.
 * {@code V9__oauth_only.sql} deletes the rows that carried it.
 */
public enum CredentialType {

	/** Issued by an authorisation server, so it carries an expiry and usually a refresh token. */
	OAUTH
}
