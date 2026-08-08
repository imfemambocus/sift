/*
 * The matching is the server's job: `GET /api/feed?q=` runs it in the database, so a search covers
 * the whole history rather than whichever page the browser happens to hold.
 *
 * What is left here is the one question the frame asks before it decides whether to show the page
 * or the results.
 */
export function isSearching(query: string): boolean {
	return query.trim() !== "";
}
