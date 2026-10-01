import { http, HttpResponse } from "msw";
import { setupServer } from "msw/node";

export const server = setupServer(
  http.get("/api/v1/settings/call-log-retention", () => HttpResponse.json({ retentionDays: null, version: 1 })),
  http.get("/api/v1/settings/debug", () => HttpResponse.json({ debugMode: false, version: 1 })),
  http.get("/api/v1/call-logs/:id/debug", ({ params }) => HttpResponse.json({ id: params.id, captured: false, exchanges: [] })),
  http.get("/api/v1/auth/setup-status", () =>
    HttpResponse.json({ setupRequired: true }),
  ),
  http.get("/api/v1/projects/:projectId/canvas/connections", () =>
    HttpResponse.json({ items: [] }),
  ),
  http.get("/api/v1/projects/:projectId/canvas/items/:itemId/media-versions", () =>
    HttpResponse.json({ items: [] }),
  ),
);
