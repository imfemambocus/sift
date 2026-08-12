/*
 * The verdict of a merge request's own pipeline, so a later sweep can tell a failure from a fix.
 *
 * pipeline_status holds only a verdict, so it stays 'failed' while a replacement pipeline is still
 * running. Without that, a pipeline that fails and is then retried would lose the memory of the
 * failure the moment it started again, and the success after it would announce nothing.
 *
 * pipeline_pending is what makes the next sweep look again. GitLab does not promise to move a merge
 * request's updated_at when its pipeline finishes, so a sweep that saw one still running cannot wait
 * for the list to tell it the result.
 */
alter table gitlab_watched_resources
    add column pipeline_id bigint,
    add column pipeline_status text,
    add column pipeline_pending boolean not null default false;

alter table gitlab_watched_resources
    add constraint gitlab_watched_resources_pipeline_status_verdict
        check (pipeline_status is null or pipeline_status in ('success', 'failed'));
