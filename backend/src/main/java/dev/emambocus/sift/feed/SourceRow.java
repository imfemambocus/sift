package dev.emambocus.sift.feed;

import dev.emambocus.sift.credential.SourceType;

/** Which source a row came from and what that source calls it. */
public record SourceRow(SourceType source, String sourceId) {
}
