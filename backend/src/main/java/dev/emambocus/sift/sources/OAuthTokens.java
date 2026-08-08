package dev.emambocus.sift.sources;

import java.time.Instant;

/**
 * What an authorisation server hands back, reduced to the three things Sift stores.
 *
 * <p>{@code refreshToken} is null when the server did not send one. Google only issues one on the
 * first consent, so a renewal there keeps the token it already has rather than overwriting it with
 * nothing.
 */
public record OAuthTokens(String accessToken, String refreshToken, Instant expiresAt) {
}
