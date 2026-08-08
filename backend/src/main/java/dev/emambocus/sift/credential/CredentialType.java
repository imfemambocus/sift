package dev.emambocus.sift.credential;

/**
 * One value today, and the column stays anyway. It is the same bet {@code source_credentials} makes
 * by being keyed on {@code (user_id, source)}: a credential kind that is not an OAuth grant (an app
 * password, say) then needs no migration.
 */
public enum CredentialType {

	/** Issued by an authorisation server, so it carries an expiry and usually a refresh token. */
	OAUTH
}
