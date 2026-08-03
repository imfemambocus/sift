import "@fontsource-variable/instrument-sans";
// latin only: the full imports drag in cyrillic, greek and vietnamese subsets nothing here needs
import "@fontsource/ibm-plex-mono/latin-400.css";
import "@fontsource/ibm-plex-mono/latin-500.css";
import "./index.css";

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { BrowserRouter } from "react-router";
import { App } from "./App";
import { ThemeProvider } from "./theme/ThemeProvider";

const queryClient = new QueryClient({
	defaultOptions: {
		queries: { retry: 1, refetchOnWindowFocus: true },
	},
});

const container = document.getElementById("root");
if (container === null) {
	throw new Error("#root is missing from index.html");
}

createRoot(container).render(
	<StrictMode>
		<QueryClientProvider client={queryClient}>
			<ThemeProvider>
				<BrowserRouter>
					<App />
				</BrowserRouter>
			</ThemeProvider>
		</QueryClientProvider>
	</StrictMode>,
);
