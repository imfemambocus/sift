package dev.emambocus.sift.credential;

public enum CredentialType {

	/** A token the user pasted in, which never expires on its own and has no refresh path. */
	PERSONAL_ACCESS_TOKEN,

	/** Issued by an authorisation server, so it carries an expiry and usually a refresh token. */
	OAUTH
}
