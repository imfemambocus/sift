package dev.emambocus.sift.sync;

import dev.emambocus.sift.credential.SourceCredential;
import dev.emambocus.sift.credential.SourceCredentialRepository;
import dev.emambocus.sift.credential.SourceType;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Carries a read decision out to whichever source can take one.
 *
 * <p>It holds no transaction, for the reason every other pair in this app is split: the row is
 * already written by the time this runs, and an HTTP call must not be made with a transaction open.
 *
 * <p>A failure here is logged and dropped. Sift's own read state is what the feed shows, so a source
 * that will not take the decision leaves the two out of step rather than losing the decision or
 * failing the request that carried it.
 */
@Service
public class SourceReadSync {

	private static final Logger log = LoggerFactory.getLogger(SourceReadSync.class);

	private final Map<SourceType, SourceReadWriter> writers;
	private final SourceCredentialRepository credentials;

	SourceReadSync(List<SourceReadWriter> writers, SourceCredentialRepository credentials) {
		this.writers = writers.stream().collect(Collectors.toMap(SourceReadWriter::id, Function.identity()));
		this.credentials = credentials;
	}

	public void push(UUID userId, SourceType source, List<String> sourceIds, boolean read) {
		SourceReadWriter writer = writers.get(source);
		if (writer == null || sourceIds.isEmpty()) {
			return;
		}

		SourceCredential credential = credentials.findByUserIdAndSource(userId, source).orElse(null);
		if (credential == null) {
			return;
		}

		try {
			writer.applyRead(credential, sourceIds, read);
		}
		catch (SourceAuthException | SourceUnavailableException ex) {
			log.warn("could not tell {} that {} row(s) are {}: {}",
					source, sourceIds.size(), read ? "read" : "unread", ex.getMessage());
		}
	}
}
