package dev.emambocus.sift.sources;

import dev.emambocus.sift.credential.SourceCredential;
import dev.emambocus.sift.credential.SourceType;
import dev.emambocus.sift.credential.UnknownSourceException;
import dev.emambocus.sift.sync.FeedSyncService;
import dev.emambocus.sift.sync.NotificationSource;
import dev.emambocus.sift.sync.SourceAccount;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Connecting, listing and disconnecting sources. Not transactional: it makes network calls, and the
 * writes live in {@link SourceCredentialStore}.
 */
@Service
public class SourceService {

	private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");
	private static final String URL_ADVICE =
			"Use an http or https URL pointing at the instance root, for example https://gitlab.example.org";

	private final Map<SourceType, NotificationSource> adapters;
	private final SourceCredentialStore store;
	private final FeedSyncService syncService;

	SourceService(List<NotificationSource> adapters, SourceCredentialStore store, FeedSyncService syncService) {
		this.adapters = adapters.stream()
				.collect(Collectors.toUnmodifiableMap(NotificationSource::id, Function.identity()));
		this.store = store;
		this.syncService = syncService;
	}

	public SourceType resolve(String slug) {
		return SourceType.parse(slug).orElseThrow(() -> new UnknownSourceException(slug));
	}

	public SourceStatusResponse connect(UUID userId, SourceType source, ConnectSourceRequest request) {
		String instanceUrl = validatedInstanceUrl(request.instanceUrl());
		NotificationSource adapter = adapter(source);

		// prove the token against the live instance first, so a typo never becomes a stored credential
		SourceAccount account = adapter.verify(instanceUrl, request.token());

		SourceCredential credential =
				store.upsertPersonalAccessToken(userId, source, instanceUrl, request.token());

		// sync inline rather than waiting for the sweep: the feed should already have something in
		// it by the time this response reaches the browser
		syncService.sync(credential);

		SourceCredential synced = store.forUser(userId, source).orElse(credential);
		return SourceStatusResponse.of(synced, store.itemCount(userId, source), account);
	}

	/**
	 * Reads a source right now instead of waiting for the sweep. Any source failure propagates, so a
	 * manual check reports the real reason rather than quietly doing nothing.
	 */
	public Optional<SourceStatusResponse> syncNow(UUID userId, SourceType source) {
		Optional<SourceCredential> credential = store.forUser(userId, source);
		if (credential.isEmpty()) {
			return Optional.empty();
		}

		syncService.sync(credential.get());

		SourceCredential synced = store.forUser(userId, source).orElse(credential.get());
		return Optional.of(SourceStatusResponse.of(synced, store.itemCount(userId, source), null));
	}

	public List<SourceStatusResponse> statuses(UUID userId) {
		return store.forUser(userId).stream()
				.map(credential -> SourceStatusResponse.of(
						credential, store.itemCount(userId, credential.getSource()), null))
				.toList();
	}

	public boolean disconnect(UUID userId, SourceType source) {
		return store.disconnect(userId, source);
	}

	private NotificationSource adapter(SourceType source) {
		NotificationSource adapter = adapters.get(source);
		if (adapter == null) {
			throw new IllegalStateException("no adapter registered for source " + source);
		}
		return adapter;
	}

	static String validatedInstanceUrl(String raw) {
		String trimmed = raw.trim();
		while (trimmed.endsWith("/")) {
			trimmed = trimmed.substring(0, trimmed.length() - 1);
		}

		URI uri;
		try {
			uri = new URI(trimmed);
		}
		catch (URISyntaxException ex) {
			throw new InvalidSourceUrlException(URL_ADVICE);
		}

		if (uri.getScheme() == null || !ALLOWED_SCHEMES.contains(uri.getScheme()) || uri.getHost() == null) {
			throw new InvalidSourceUrlException(URL_ADVICE);
		}
		return trimmed;
	}
}
