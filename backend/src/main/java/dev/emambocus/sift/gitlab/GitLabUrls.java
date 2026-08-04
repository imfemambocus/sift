package dev.emambocus.sift.gitlab;

/** Shared because both the merge request rows and the participation rows need the same two facts. */
final class GitLabUrls {

	private GitLabUrls() {
	}

	// the merge request API never returns the project path directly, only inside references.full
	static String projectPath(GitLabResponses.References references) {
		if (references == null || references.full() == null) {
			return null;
		}
		int separator = references.full().indexOf('!');
		return separator < 0 ? references.full() : references.full().substring(0, separator);
	}

	static String projectUrl(String mergeRequestUrl) {
		if (mergeRequestUrl == null) {
			return null;
		}
		int marker = mergeRequestUrl.indexOf("/-/merge_requests");
		return marker < 0 ? null : mergeRequestUrl.substring(0, marker);
	}
}
