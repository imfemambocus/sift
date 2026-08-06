package dev.emambocus.sift.feed;

import dev.emambocus.sift.credential.SourceType;

/**
 * How many waiting rows carry one action token.
 *
 * <p>By kind rather than by the family Home draws its bar from: which kinds make up a family is a
 * question about how the app words things, and that answer lives in the frontend. The backend
 * counting families instead would put the same taxonomy in two places.
 */
public record KindCount(SourceType source, String kind, long count) {
}
