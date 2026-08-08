package dev.emambocus.sift.gmail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.emambocus.sift.SiftIntegrationTest;
import dev.emambocus.sift.credential.SourceCredential;
import dev.emambocus.sift.credential.SourceCredentialRepository;
import dev.emambocus.sift.credential.SourceType;
import dev.emambocus.sift.feed.FeedItem;
import dev.emambocus.sift.feed.FeedItemRepository;
import dev.emambocus.sift.feed.FeedService;
import dev.emambocus.sift.gmail.FakeGmail.Msg;
import dev.emambocus.sift.sync.FeedSyncService;
import dev.emambocus.sift.sync.IncomingItem;
import dev.emambocus.sift.sync.SourceAuthException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The mail adapter. Its rules are not GitLab's rules with different words: a mailbox has no to-do
 * list doing the narrowing, so every message becomes a row, and the things worth proving are the
 * ones that stop that being unusable.
 */
class GmailSourceTest extends SiftIntegrationTest {

	private static final String ADA = "\"Ada Lovelace\" <ada@uni.lu>";

	private static final String LIST_PATH = "/gmail/v1/users/me/messages";

	@Autowired
	private GmailSource source;

	@Autowired
	private FeedSyncService syncService;

	@Autowired
	private FeedItemRepository items;

	@Autowired
	private FeedService feed;

	@Autowired
	private SourceCredentialRepository credentials;

	@BeforeEach
	void emptyMailbox() {
		GMAIL.reset();
	}

	@Test
	@DisplayName("every message becomes a row, with its subject, its sender and its address")
	void everyMessageArrives() {
		GMAIL.deliver(
				Msg.unread("m1", "t1", minutesAgo(10), ADA, "Chart V2 review"),
				Msg.unread("m2", "t2", minutesAgo(20), "grete@uni.lu", "Seminar on Thursday"));

		List<IncomingItem> fetched = source.fetch(credential("all@uni.lu"));

		assertThat(fetched).extracting(IncomingItem::sourceId).containsExactlyInAnyOrder("msg:m1", "msg:m2");
		IncomingItem first = fetched.stream().filter(item -> item.sourceId().equals("msg:m1")).findFirst().orElseThrow();
		assertThat(first.title()).isEqualTo("Chart V2 review");
		assertThat(first.actorName()).isEqualTo("Ada Lovelace");
		// the address goes where a project path goes, so it reads as context and the search finds it
		assertThat(first.contextLabel()).isEqualTo("ada@uni.lu");
		assertThat(first.url()).isEqualTo("https://mail.google.com/mail/u/0/#all/m1");
		// a message happened once: the next sweep not listing it must not resolve it
		assertThat(first.resolveWhenAbsent()).isFalse();
	}

	@Test
	@DisplayName("a sender with no display name falls back to the address rather than to nothing")
	void bareAddressIsStillAName() {
		GMAIL.deliver(Msg.unread("m1", "t1", minutesAgo(5), "grete@uni.lu", "No display name"));

		IncomingItem item = source.fetch(credential("bare@uni.lu")).getFirst();

		assertThat(item.actorName()).isEqualTo("grete@uni.lu");
		assertThat(item.contextLabel()).isEqualTo("grete@uni.lu");
	}

	@Test
	@DisplayName("a second sweep reads only what arrived after the first, never the mailbox again")
	void theForwardEdgeBoundsEverySweepAfterTheFirst() {
		GMAIL.deliver(Msg.unread("m1", "t1", minutesAgo(30), ADA, "The first one"));
		SourceCredential credential = credential("watermark@uni.lu");

		assertThat(source.fetch(credential)).hasSize(1);

		GMAIL.deliver(Msg.unread("m2", "t2", minutesAgo(1), ADA, "The second one"));
		List<IncomingItem> second = source.fetch(credential);

		/*
		 * only the new one. every message costs a request of its own, so a sweep that re-read the
		 * whole window would make a real mailbox unaffordable rather than merely slow.
		 */
		assertThat(second).extracting(IncomingItem::sourceId).containsExactly("msg:m2");
	}

	@Test
	@DisplayName("mail already read in Gmail arrives read, so connecting a mailbox is not a wall of unread")
	void gmailsOwnReadStateSeedsTheRow() {
		GMAIL.deliver(
				Msg.read("m1", "t1", minutesAgo(10), ADA, "Dealt with in Gmail"),
				Msg.unread("m2", "t2", minutesAgo(9), ADA, "Not yet looked at"));
		SourceCredential credential = credential("seeded@uni.lu");

		syncService.sync(credential);

		assertThat(readFlag(credential.getUserId(), "msg:m1")).isTrue();
		assertThat(readFlag(credential.getUserId(), "msg:m2")).isFalse();
	}

	@Test
	@DisplayName("Sift owns read state after the row exists, so a later sweep cannot undo a decision here")
	void siftsOwnReadStateWinsAfterwards() {
		GMAIL.deliver(Msg.read("m1", "t1", minutesAgo(10), ADA, "Read in Gmail"));
		SourceCredential credential = credential("owned@uni.lu");
		syncService.sync(credential);

		// marked unread here on purpose, then the same message is offered again by an overlapping sweep
		feed.setRead(credential.getUserId(), row(credential.getUserId(), "msg:m1").getId(), false);
		syncService.sync(credential);

		assertThat(readFlag(credential.getUserId(), "msg:m1")).isFalse();
	}

	@Test
	@DisplayName("drafts and chats are left out, and they are the only things Sift itself decides")
	void draftsAndChatsAreNotMail() {
		GMAIL.deliver(
				Msg.unread("m1", "t1", minutesAgo(10), ADA, "Something that arrived"),
				Msg.sent("m2", "t2", minutesAgo(9), "grete@uni.lu", "Something I sent"),
				Msg.unread("m3", "t3", minutesAgo(8), "me@uni.lu", "Half written").labelled("DRAFT"),
				Msg.unread("m4", "t4", minutesAgo(7), "me@uni.lu", "A chat line").labelled("CHAT"));

		List<IncomingItem> fetched = source.fetch(credential("kept@uni.lu"));

		// mail you wrote is part of an archive you search, so only the two that are not mail go
		assertThat(fetched).extracting(IncomingItem::sourceId).containsExactlyInAnyOrder("msg:m1", "msg:m2");
	}

	@Test
	@DisplayName("mail you sent is a row about who received it, so it is findable by where it went")
	void sentMailIsARowAboutItsRecipient() {
		GMAIL.deliver(Msg.sent("m1", "t1", minutesAgo(10),
				"\"Grete Hermann\" <grete@uni.lu>", "The figures you asked for"));

		IncomingItem item = source.fetch(credential("sent@uni.lu")).getFirst();

		assertThat(item.kind()).isEqualTo(GmailSource.KIND_SENT);
		assertThat(item.actorName()).isEqualTo("Grete Hermann");
		assertThat(item.contextLabel()).isEqualTo("grete@uni.lu");
	}

	@Test
	@DisplayName("a message to several people keeps the first, and a comma inside a name is not a separator")
	void severalRecipientsKeepTheFirst() {
		GMAIL.deliver(Msg.sent("m1", "t1", minutesAgo(5),
				"\"Hermann, Grete\" <grete@uni.lu>, ada@uni.lu", "To both of you"));

		IncomingItem item = source.fetch(credential("many@uni.lu")).getFirst();

		assertThat(item.actorName()).isEqualTo("Hermann, Grete");
		assertThat(item.contextLabel()).isEqualTo("grete@uni.lu");
	}

	@Test
	@DisplayName("a mailbox larger than one sweep can hold is read whole, not cut off at the ceiling")
	void theWholeMailboxIsReadPastTheCeiling() {
		Msg[] mailbox = new Msg[250];
		for (int i = 0; i < mailbox.length; i++) {
			mailbox[i] = Msg.unread("m" + i, "t" + i, minutesAgo(i + 1), ADA, "Message " + i);
		}
		GMAIL.deliver(mailbox);

		List<IncomingItem> fetched = source.fetch(credential("whole@uni.lu"));

		/*
		 * all of them, past the 200 one sweep reads at the top. gmail lists newest first, so a source
		 * that only moved a forward edge would take the newest chunk and step over everything under it.
		 */
		assertThat(fetched).hasSize(250);
	}

	@Test
	@DisplayName("more arriving at once than one sweep can hold is read oldest first, so none is skipped")
	void aBurstLargerThanOneSweepIsReadOldestFirst() {
		GMAIL.deliver(Msg.unread("seed", "t0", minutesAgo(400), ADA, "The one already read"));
		SourceCredential credential = credential("burst@uni.lu");
		assertThat(source.fetch(credential)).hasSize(1);

		Msg[] burst = new Msg[250];
		for (int i = 0; i < burst.length; i++) {
			burst[i] = Msg.unread("m" + i, "t" + i, minutesAgo(250 - i), ADA, "Message " + i);
		}
		GMAIL.deliver(burst);

		List<IncomingItem> second = source.fetch(credential);
		List<IncomingItem> third = source.fetch(credential);

		assertThat(second).hasSize(200);
		// the 50 the ceiling left behind, which only arrive because the edge moved to the oldest 200
		assertThat(third).hasSize(50);
	}

	@Test
	@DisplayName("the walk back ends at the beginning of the mailbox and is not asked for again")
	void theWalkBackEndsAndStaysEnded() {
		GMAIL.deliver(
				Msg.unread("m1", "t1", minutesAgo(30), ADA, "The older one"),
				Msg.unread("m2", "t2", minutesAgo(10), ADA, "The newer one"));
		SourceCredential credential = credential("complete@uni.lu");

		source.fetch(credential);
		int listedByThen = GMAIL.hits(LIST_PATH);

		source.fetch(credential);

		// one list call, the forward one: nothing asks for what is below the floor a second time
		assertThat(GMAIL.hits(LIST_PATH) - listedByThen).isEqualTo(1);
	}

	@Test
	@DisplayName("messages in one conversation share a group, so a thread is one place in the list")
	void aThreadIsOneGroup() {
		GMAIL.deliver(
				Msg.unread("m1", "t1", minutesAgo(30), ADA, "Chart V2 review"),
				Msg.unread("m2", "t1", minutesAgo(20), "grete@uni.lu", "Re: Chart V2 review"),
				Msg.unread("m3", "t2", minutesAgo(10), ADA, "Something else"));
		SourceCredential credential = credential("threaded@uni.lu");

		syncService.sync(credential);

		/*
		 * the thread id, not the url. every message lives at the same path and differs only in the
		 * fragment, so the rule that strips the fragment would make one group of the whole mailbox.
		 */
		assertThat(row(credential.getUserId(), "msg:m1").getGroupKey())
				.isEqualTo(row(credential.getUserId(), "msg:m2").getGroupKey())
				.isEqualTo("gmail:thread:t1");
		assertThat(row(credential.getUserId(), "msg:m3").getGroupKey()).isEqualTo("gmail:thread:t2");
	}

	@Test
	@DisplayName("an expiring token is renewed, and the refresh token Google does not resend is kept")
	void renewalKeepsTheRefreshTokenGoogleOmits() {
		// Google returns no refresh_token on a renewal: storing null would end the connection
		GMAIL.answeringToken("""
				{"access_token": "renewed-access", "expires_in": 3600}
				""").accepting("renewed-access");
		GMAIL.deliver(Msg.unread("m1", "t1", minutesAgo(5), ADA, "After the renewal"));

		SourceCredential credential = credential("renew@uni.lu", "stale-access", justExpired());
		List<IncomingItem> fetched = source.fetch(credential);

		assertThat(GMAIL.hits("/token")).isEqualTo(1);
		// the read only succeeded because the new token was used, which the stub is strict about
		assertThat(fetched).hasSize(1);
		SourceCredential stored = credentials.findById(credential.getId()).orElseThrow();
		assertThat(stored.getAccessToken()).isEqualTo("renewed-access");
		assertThat(stored.getRefreshToken()).isEqualTo("old-refresh");
	}

	@Test
	@DisplayName("a rejected token is a reconnect, not a retry")
	void aRejectedTokenIsAnAuthFailure() {
		GMAIL.failingList(401);

		assertThatThrownBy(() -> source.fetch(credential("rejected@uni.lu")))
				.isInstanceOf(SourceAuthException.class)
				.hasMessageContaining("Connect Gmail again");
	}

	private static long minutesAgo(int minutes) {
		return Instant.now().minus(minutes, ChronoUnit.MINUTES).toEpochMilli();
	}

	private static Instant justExpired() {
		return Instant.now().minus(1, ChronoUnit.MINUTES);
	}

	private SourceCredential credential(String email) {
		return credential(email, "live-access", Instant.now().plus(1, ChronoUnit.HOURS));
	}

	private SourceCredential credential(String email, String accessToken, Instant expiresAt) {
		return credentials.save(SourceCredential.oauth(newUser(email), SourceType.GMAIL,
				"https://mail.google.com", accessToken, "old-refresh", expiresAt, Instant.now()));
	}

	private FeedItem row(UUID userId, String sourceId) {
		return items.findByUserIdAndSource(userId, SourceType.GMAIL).stream()
				.filter(item -> item.getSourceId().equals(sourceId))
				.findFirst()
				.orElseThrow(() -> new AssertionError("no row for " + sourceId));
	}

	private boolean readFlag(UUID userId, String sourceId) {
		return row(userId, sourceId).getReadAt() != null;
	}
}
