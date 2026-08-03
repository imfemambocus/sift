package dev.emambocus.sift.gitlab;

import java.util.List;
import java.util.UUID;
import org.springframework.data.repository.Repository;

/*
 * top level on purpose: spring data's scanner does not pick up repository interfaces nested inside
 * another class, and the failure is a missing-bean error at startup rather than anything obvious.
 */
interface GitLabWatchedResourceRepository extends Repository<GitLabWatchedResource, UUID> {

	List<GitLabWatchedResource> findByUserId(UUID userId);

	<S extends GitLabWatchedResource> List<S> saveAll(Iterable<S> resources);
}
