package dev.emambocus.sift.gitlab;

public enum GitLabResourceType {

	MERGE_REQUEST("merge_requests"),
	ISSUE("issues");

	private final String pathSegment;

	GitLabResourceType(String pathSegment) {
		this.pathSegment = pathSegment;
	}

	String pathSegment() {
		return pathSegment;
	}
}
