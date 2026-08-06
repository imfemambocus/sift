package dev.emambocus.sift.feed;

import dev.emambocus.sift.credential.SourceType;

/**
 * The four numbers one source's rows add up to, counted in the database rather than by shipping the
 * rows and counting them in the browser.
 *
 * @param waiting rows the source still reports. The feed holds the whole history, so a merged merge
 *     request belongs in the list and does not belong in "11 waiting".
 */
public record FeedCounts(SourceType source, long total, long unread, long waiting, long waitingUnread) {
}
