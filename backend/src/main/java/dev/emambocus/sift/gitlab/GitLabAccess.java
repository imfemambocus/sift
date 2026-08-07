package dev.emambocus.sift.gitlab;

import dev.emambocus.sift.credential.SourceCredential;

/**
 * One credential reduced to what a call to GitLab needs: which instance, and the token to send.
 *
 * <p>The header is not a detail. GitLab reads an OAuth token from {@code Authorization: Bearer} and
 * will not accept it in {@code PRIVATE-TOKEN}, which is where a pasted token used to go. Only OAuth
 * grants exist now, so the choice is settled here rather than travelling with each credential.
 */
record GitLabAccess(String instanceUrl, String token) {

	static GitLabAccess of(SourceCredential credential) {
		return new GitLabAccess(credential.getInstanceUrl(), credential.getAccessToken());
	}

	String headerValue() {
		return "Bearer " + token;
	}
}
