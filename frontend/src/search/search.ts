/*
 * The matching itself is the server's job now. It used to be uFuzzy over the whole feed in the
 * browser, which only worked while the browser held the whole feed; a paged one can only search the
 * page it has. `GET /api/feed?q=` does it in the database instead, and forgives the same one typo
 * per word that uFuzzy did.
 *
 * What is left here is the one question the frame asks before it decides whether to show the page
 * or the results.
 */
export function isSearching(query: string): boolean {
	return query.trim() !== "";
}
