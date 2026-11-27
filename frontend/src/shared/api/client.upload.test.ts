import { beforeEach, describe, expect, it, vi } from "vitest";

const MEBIBYTE = 1024 * 1024;
const PROJECT_ID = "project/id";
const UPLOADS = [
  { method: "uploadImageAsset", suffix: "", limit: 20 * MEBIBYTE },
  { method: "uploadAudioAsset", suffix: "/audio", limit: 50 * MEBIBYTE },
  { method: "uploadVideoAsset", suffix: "/video", limit: 500 * MEBIBYTE },
] as const;
let client: typeof import("./client");

beforeEach(async () => {
  // Start each scenario with an empty CSRF cache, as a new browser session would.
  vi.resetModules();
  client = await import("./client");
});

function fileOfSize(size: number): File {
  const file = new File(["synthetic media"], "upload.bin");
  Object.defineProperty(file, "size", { value: size });
  return file;
}

function mockUpload(response = Response.json({ id: "uploaded-asset" })) {
  return vi
    .spyOn(globalThis, "fetch")
    .mockImplementation(path =>
      Promise.resolve(
        path === "/api/v1/auth/csrf"
          ? Response.json({ token: "synthetic-csrf", headerName: "X-CSRF-TOKEN" })
          : response.clone(),
      ),
    );
}

describe("session-protected multipart uploads", () => {
  it.each(UPLOADS)(
    "$method accepts its size limit and leaves the boundary to the browser",
    async ({ method, suffix, limit }) => {
      const fetch = mockUpload();
      const file = fileOfSize(limit);
      await expect(client[method](PROJECT_ID, file)).resolves.toEqual({ id: "uploaded-asset" });
      const call = fetch.mock.calls[1];
      expect(call?.[0]).toBe(`/api/v1/projects/project%2Fid/assets${suffix}`);
      const init = call?.[1];
      const headers = new Headers(init?.headers);
      expect(init?.method).toBe("POST");
      expect(init?.credentials).toBe("same-origin");
      expect(headers.get("X-CSRF-TOKEN")).toBe("synthetic-csrf");
      expect(headers.get("Accept-Language")).toBe("zh");
      expect(headers.has("Content-Type")).toBe(false);
      expect((init?.body as FormData).get("file")).toBe(file);
    },
  );

  it.each(UPLOADS)("$method rejects oversized files before making any request", async ({ method, limit }) => {
    const fetch = mockUpload();
    await expect(client[method](PROJECT_ID, fileOfSize(limit + 1))).rejects.toMatchObject({
      status: 413,
      code: "ASSET_TOO_LARGE",
      retryable: false,
    });
    expect(fetch).not.toHaveBeenCalled();
  });

  it("preserves the library metadata and server ProblemDetail", async () => {
    const fetch = mockUpload(
      Response.json(
        { code: "VERSION_CONFLICT", title: "Conflict", detail: "Synthetic conflict", retryable: true },
        {
          status: 409,
          headers: { "Content-Type": "application/problem+json" },
        },
      ),
    );
    const request = {
      file: fileOfSize(1),
      kind: "AUDIO",
      name: "Synthetic audio",
      category: "OTHER",
      commandKey: "synthetic-command",
    } as const;
    await expect(client.uploadLibraryEntry(request)).rejects.toMatchObject({
      status: 409,
      code: "VERSION_CONFLICT",
      message: "Synthetic conflict",
      retryable: true,
    });
    const call = fetch.mock.calls[1];
    expect(call?.[0]).toBe("/api/v1/library/uploads");
    expect(new Headers(call?.[1]?.headers).has("Content-Type")).toBe(false);
    const form = call?.[1]?.body as FormData;
    for (const [key, value] of Object.entries(request)) expect(form.get(key)).toBe(value);
  });

  it("keeps the media-specific fallback when a server returns a non-JSON error", async () => {
    mockUpload(new Response("Synthetic unavailable", { status: 503 }));
    await expect(client.uploadVideoAsset(PROJECT_ID, fileOfSize(1))).rejects.toMatchObject({
      status: 503,
      code: "HTTP_ERROR",
      message: "视频上传未完成",
      retryable: true,
    });
  });

  it("continues sending JSON writes with their content type and CSRF header", async () => {
    const fetch = mockUpload(Response.json({ id: "project" }));
    await client.createProject({ name: "Synthetic project", aspectRatio: "LANDSCAPE_16_9" });
    const init = fetch.mock.calls[1]?.[1];
    expect(new Headers(init?.headers).get("Content-Type")).toBe("application/json");
    expect(new Headers(init?.headers).get("X-CSRF-TOKEN")).toBe("synthetic-csrf");
    expect(JSON.parse(init?.body as string)).toEqual({ name: "Synthetic project", aspectRatio: "LANDSCAPE_16_9" });
  });
});

describe("session token rotation", () => {
  const project = { name: "Synthetic project", aspectRatio: "LANDSCAPE_16_9" } as const;

  it("shares the first token read between concurrent writes", async () => {
    const fetch = mockUpload();
    await Promise.all([client.createProject(project), client.createProject(project)]);
    expect(fetch.mock.calls.filter(([url]) => url === "/api/v1/auth/csrf")).toHaveLength(1);
  });

  it("allows an explicit retry after a stale token without replaying the rejected command", async () => {
    let reads = 0;
    const tokens: (string | null)[] = [];
    vi.spyOn(globalThis, "fetch").mockImplementation(async (path, init) => {
      if (path === "/api/v1/auth/csrf") return Response.json({ headerName: "X-CSRF-TOKEN", token: String(++reads) });
      tokens.push(new Headers(init?.headers).get("X-CSRF-TOKEN"));
      return tokens.length === 2 ? new Response(null, { status: 403 }) : Response.json({ id: "project" });
    });
    await client.createProject(project);
    await expect(client.createProject(project)).rejects.toMatchObject({ status: 403 });
    expect(tokens).toEqual(["1", "1"]);
    await client.createProject(project);
    expect(tokens).toEqual(["1", "1", "2"]);
  });

  it("gets a fresh token before login after the browser session changed elsewhere", async () => {
    let reads = 0;
    const fetch = vi
      .spyOn(globalThis, "fetch")
      .mockImplementation(async path =>
        path === "/api/v1/auth/csrf"
          ? Response.json({ headerName: "X-CSRF-TOKEN", token: String(++reads) })
          : Response.json({ id: "success" }),
      );
    await client.createProject(project);
    await client.login({ loginName: "artist", password: "synthetic" });
    const loginCall = fetch.mock.calls.find(([url]) => url === "/api/v1/auth/login");
    expect(new Headers(loginCall?.[1]?.headers).get("X-CSRF-TOKEN")).toBe("2");
  });
});
