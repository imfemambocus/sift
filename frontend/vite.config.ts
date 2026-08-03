import tailwindcss from "@tailwindcss/vite";
import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";

const BACKEND = "http://localhost:7777";

/*
 * proxying keeps the dev server on one origin with the api, so the session cookie and the csrf
 * handshake behave exactly as they do in the built app rather than needing cors
 */
const proxy = {
	"/api": BACKEND,
	"/actuator": BACKEND,
};

export default defineConfig({
	plugins: [react(), tailwindcss()],
	// off 5173, which local dev servers tend to already hold
	server: { port: 5174, strictPort: true, proxy },
	preview: { port: 5175, strictPort: true, proxy },
});
