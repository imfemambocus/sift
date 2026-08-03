package dev.emambocus.sift.sync;

/** Who a credential turned out to belong to, so the UI can confirm what it just connected. */
public record SourceAccount(String username, String displayName, String avatarUrl, String webUrl) {
}
