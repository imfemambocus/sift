package dev.emambocus.sift.gitlab;

import java.util.List;
import java.util.UUID;
import org.springframework.data.repository.Repository;

interface GitLabWatchedDiscussionRepository extends Repository<GitLabWatchedDiscussion, UUID> {

	List<GitLabWatchedDiscussion> findByUserId(UUID userId);

	<S extends GitLabWatchedDiscussion> List<S> saveAll(Iterable<S> discussions);
}
