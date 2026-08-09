package dev.emambocus.sift.gmail;

import dev.emambocus.sift.credential.SourceCredential;
import dev.emambocus.sift.credential.SourceType;
import dev.emambocus.sift.sync.SourceReadWriter;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Tells Gmail what you have read here, by taking the {@code UNREAD} label off the message or putting
 * it back on.
 *
 * <p>Sift only ever writes that one label. The grant allows more, because Google offers nothing
 * narrower that can write a label at all, and the difference between what a scope permits and what
 * the code does is worth keeping visible.
 */
@Component
class GmailReadWriter implements SourceReadWriter {

	private static final String ID_PREFIX = "msg:";

	private final GmailClient client;
	private final GmailOAuth oauth;

	GmailReadWriter(GmailClient client, GmailOAuth oauth) {
		this.client = client;
		this.oauth = oauth;
	}

	@Override
	public SourceType id() {
		return SourceType.GMAIL;
	}

	@Override
	public void applyRead(SourceCredential credential, List<String> sourceIds, boolean read) {
		List<String> messageIds = sourceIds.stream()
				.filter(sourceId -> sourceId.startsWith(ID_PREFIX))
				.map(sourceId -> sourceId.substring(ID_PREFIX.length()))
				.toList();
		if (messageIds.isEmpty()) {
			return;
		}

		String accessToken = oauth.accessTokenFor(credential);
		for (int from = 0; from < messageIds.size(); from += GmailClient.BATCH_LIMIT) {
			int to = Math.min(from + GmailClient.BATCH_LIMIT, messageIds.size());
			client.setUnread(accessToken, messageIds.subList(from, to), !read);
		}
	}
}
