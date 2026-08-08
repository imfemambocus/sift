package dev.emambocus.sift.gitlab;

import dev.emambocus.sift.credential.SourceCredential;

/**
 * One credential reduced to what a call to GitLab needs: which instance, and the token to send.
 *
 * <p>The header is not a detail. GitLab reads an OAuth token from {@code Authorization: Bearer} and
 * refuses it in {@code PRIVATE-TOKEN}, which is the header for a personal access token. Every
 * credential here is an OAuth grant, so the choice is settled once rather than carried per token.
 */
record GitLabAccess(String instanceUrl, String token) {

	static GitLabAccess of(SourceCredential credential) {
		return new GitLabAccess(credential.getInstanceUrl(), credential.getAccessToken());
	}

	String headerValue() {
		return "Bearer " + token;
	}
}
