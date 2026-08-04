package dev.emambocus.sift.gitlab;

import java.util.Optional;

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

	/** GitLab's own name for the thing a note hangs off, as the activity feed spells it. */
	static Optional<GitLabResourceType> ofNoteable(String noteableType) {
		return switch (noteableType == null ? "" : noteableType) {
			case "MergeRequest" -> Optional.of(MERGE_REQUEST);
			case "Issue" -> Optional.of(ISSUE);
			// commits, snippets and epics get comments too, and none of them is a thing Sift watches
			default -> Optional.empty();
		};
	}
}
