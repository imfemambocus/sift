package dev.emambocus.sift.sources;

import dev.emambocus.sift.credential.SourceCredential;
import dev.emambocus.sift.credential.SourceType;
import dev.emambocus.sift.credential.UnknownSourceException;
import dev.emambocus.sift.sync.FeedSyncService;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Listing, reading and disconnecting sources. Not transactional: it makes network calls, and the
 * writes live in {@link SourceCredentialStore}.
 *
 * <p>Connecting is not here. A source is authorized through its own OAuth flow, which is the only
 * way a credential is ever created (see {@code GitLabOAuthController}).
 */
@Service
public class SourceService {

	private static final Logger log = LoggerFactory.getLogger(SourceService.class);

	private final SourceCredentialStore store;
	private final FeedSyncService syncService;
	private final List<SourceOAuthFlow> flows;

	SourceService(SourceCredentialStore store, FeedSyncService syncService, List<SourceOAuthFlow> flows) {
		this.store = store;
		this.syncService = syncService;
		this.flows = flows;
	}

	public SourceType resolve(String slug) {
		return SourceType.parse(slug).orElseThrow(() -> new UnknownSourceException(slug));
	}

	/**
	 * Reads a source right now instead of waiting for the sweep. Any source failure propagates, so a
	 * manual check reports the real reason rather than quietly doing nothing.
	 *
	 * <p>A read that is already running is left to finish rather than joined by a second one. It is
	 * fetching the same rows, and the answer says the source is syncing.
	 */
	public Optional<SourceStatusResponse> syncNow(UUID userId, SourceType source) {
		Optional<SourceCredential> credential = store.forUser(userId, source);
		if (credential.isEmpty()) {
			return Optional.empty();
		}

		syncService.sync(credential.get());

		SourceCredential synced = store.forUser(userId, source).orElse(credential.get());
		return Optional.of(status(userId, synced));
	}

	public List<SourceStatusResponse> statuses(UUID userId) {
		return store.forUser(userId).stream()
				.map(credential -> status(userId, credential))
				.toList();
	}

	private SourceStatusResponse status(UUID userId, SourceCredential credential) {
		return SourceStatusResponse.of(credential,
				store.itemCount(userId, credential.getSource()),
				syncService.historyComplete(credential),
				syncService.isSyncing(credential.getId()));
	}

	/**
	 * Every source that can be connected, and whether this user has. Home needs the ones they do not
	 * have as much as the ones they do, so it can offer them rather than hiding what exists.
	 */
	public List<ConnectorResponse> connectors(UUID userId) {
		Set<SourceType> connected = store.forUser(userId).stream()
				.map(SourceCredential::getSource)
				.collect(Collectors.toUnmodifiableSet());

		return flows.stream()
				.map(flow -> new ConnectorResponse(
						flow.source().slug(),
						connected.contains(flow.source()),
						flow.configured(),
						flow.configured() ? flow.target() : null))
				.sorted(Comparator.comparing(ConnectorResponse::source))
				.toList();
	}

	/**
	 * Withdraws the grant at the source first, then deletes what Sift holds.
	 *
	 * <p>The revoke is best effort and deliberately cannot fail the disconnect. If the provider is
	 * unreachable, the token still expires on its own and the approval can be withdrawn there by hand,
	 * which is a far better outcome than refusing to let somebody disconnect.
	 */
	public boolean disconnect(UUID userId, SourceType source) {
		store.forUser(userId, source).ifPresent(credential -> flows.stream()
				.filter(flow -> flow.source() == source)
				.findFirst()
				.ifPresent(flow -> revokeQuietly(flow, credential)));

		return store.disconnect(userId, source);
	}

	private static void revokeQuietly(SourceOAuthFlow flow, SourceCredential credential) {
		try {
			flow.revoke(credential);
		}
		catch (RuntimeException ex) {
			log.warn("could not withdraw the {} grant while disconnecting: {}",
					credential.getSource(), ex.getMessage());
		}
	}
}
