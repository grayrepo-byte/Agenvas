import type { Plugin } from "vite";
import { sendMedia } from "./settings-design-media";

/** Exact, serve-only test namespace; these independent shapes never enter the client build. */
export function imageStudioMediaPreview(): Plugin {
  return {
    name: "agenvas-image-studio-test-media",
    apply: "serve",
    configureServer(server) {
      server.middlewares.use((request, response, next) => {
        const match =
          /^\/api\/v1\/projects\/synthetic-image-studio\/assets\/synthetic-studio-(square|portrait|landscape)-(\d+)\/(content|thumbnail)$/.exec(
            request.url?.split("?")[0] ?? "",
          );
        if (!match) return next();
        if (request.method !== "GET" && request.method !== "HEAD") {
          response.statusCode = 405;
          response.setHeader("Allow", "GET, HEAD");
          response.end();
          return;
        }
        const [width, height] =
          match[1] === "portrait" ? [900, 1600] : match[1] === "landscape" ? [1600, 900] : [1024, 1024];
        const bytes = Buffer.from(
          `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" viewBox="0 0 ${width} ${height}">
<rect width="100%" height="100%" fill="#202b36"/>
<path d="M0 ${height} ${width * 0.4} ${height * 0.3} ${width} ${height * 0.8} V${height}Z" fill="#496477"/>
<circle cx="${width * 0.74}" cy="${height * 0.22}" r="${width * 0.09}" fill="#c4d9dd"/>
<text x="${width * 0.07}" y="${height * 0.86}" fill="white" font-family="sans-serif" font-size="${width * 0.04}">SYNTHETIC TEST ${match[2]}</text>
<text x="${width * 0.07}" y="${height * 0.92}" fill="white" font-family="sans-serif" font-size="${width * 0.025}">NOT AI GENERATED</text>
</svg>`,
        );
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Agenvas-Synthetic-Preview", "true");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Content-Security-Policy", "default-src 'none'; sandbox");
        sendMedia(request, response, bytes, "image/svg+xml");
      });
    },
  };
}
