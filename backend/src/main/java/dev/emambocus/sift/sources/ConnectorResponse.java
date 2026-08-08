package dev.emambocus.sift.sources;

/**
 * One source the app knows how to connect, whether or not it is connected yet.
 *
 * <p>{@code GET /api/sources} answers only what is connected, which cannot tell Home what to offer.
 * This is the other half: the dashboard draws a card for every entry here, a summary for the
 * connected ones and an invitation for the rest.
 *
 * @param configured whether this deployment registered an application, so the button can work
 * @param target what the offer names: a GitLab instance, or the mail provider
 */
public record ConnectorResponse(String source, boolean connected, boolean configured, String target) {
}
