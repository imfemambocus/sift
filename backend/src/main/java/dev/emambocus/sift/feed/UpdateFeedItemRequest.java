package dev.emambocus.sift.feed;

import jakarta.validation.constraints.NotNull;

/** Boxed rather than primitive so an absent field is a 400 instead of silently meaning "unread". */
public record UpdateFeedItemRequest(@NotNull Boolean read) {
}
