import { QueryClientProvider } from "@tanstack/react-query";
import { act, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { beforeEach, describe, expect, it } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import type { ThirdPartyPromptSyncSettings } from "../../shared/api/client";
import { setLocale } from "../../shared/i18n";
import { server } from "../../test/server";
import { ThirdPartyPromptSyncControls } from "./ThirdPartyPromptSyncControls";

const SETTINGS_URL = "/api/v1/settings/media-template-sync";
const TITLE = "Automatically sync third-party prompts";
const SAVE = "Save sync settings";
const RELOAD = "Reload settings";
const CONFLICT = "Sync settings changed. Your draft is preserved; reload before saving.";

function show(client = createQueryClient()) {
  client.setDefaultOptions({ queries: { retry: false, staleTime: 30_000 } });
  return { client, ...render(<QueryClientProvider client={client}><ThirdPartyPromptSyncControls /></QueryClientProvider>) };
}

async function readySwitch() {
  const toggle = await screen.findByRole("switch", { name: TITLE });
  await waitFor(() => expect(toggle).toBeEnabled());
  return toggle;
}

function readSettings(value: ThirdPartyPromptSyncSettings) {
  server.use(http.get(SETTINGS_URL, () => HttpResponse.json(value)));
}

beforeEach(() => {
  setLocale("en");
  server.use(http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "synthetic-csrf" })));
});

describe("ThirdPartyPromptSyncControls", () => {
  it("starts off, waits for explicit Save, and reloads the persisted administrator choice", async () => {
    let persisted: ThirdPartyPromptSyncSettings = { enabled: false, deploymentEnabled: true, version: 17 };
    const writes: unknown[] = [];
    let reads = 0;
    server.use(http.get(SETTINGS_URL, () => { reads++; return HttpResponse.json(persisted); }),
      http.put(SETTINGS_URL, async ({ request }) => {
        writes.push(await request.json());
        persisted = { enabled: true, deploymentEnabled: true, version: 18 };
        return HttpResponse.json(persisted);
      }));
    const first = show(); const user = userEvent.setup();
    const toggle = await readySwitch();
    expect(toggle).not.toBeChecked();
    expect(screen.getByRole("button", { name: SAVE })).toBeDisabled();
    expect(screen.getByText(/Sources can see the server IP/)).toBeInTheDocument();
    expect(screen.getByText(/raw\.githubusercontent\.com and imagepromptgallery\.com/)).toBeInTheDocument();
    await user.click(toggle);
    expect(toggle).toBeChecked();
    expect(writes).toEqual([]);
    expect(screen.getByRole("button", { name: SAVE })).toBeEnabled();
    await user.click(screen.getByRole("button", { name: SAVE }));
    await waitFor(() => expect(writes).toEqual([{ enabled: true, expectedVersion: 17 }]));
    await waitFor(() => expect(screen.getByRole("button", { name: SAVE })).toBeDisabled());
    first.unmount();
    show();
    expect(await readySwitch()).toBeChecked();
    expect(reads).toBe(2);
    expect(writes).toHaveLength(1);
  });

  it("updates both source panels from one saved global choice without another read", async () => {
    let reads = 0;
    const writes: unknown[] = [];
    server.use(http.get(SETTINGS_URL, () => { reads++; return HttpResponse.json({ enabled: false, deploymentEnabled: true, version: 3 }); }),
      http.put(SETTINGS_URL, async ({ request }) => {
        writes.push(await request.json());
        return HttpResponse.json({ enabled: true, deploymentEnabled: true, version: 4 });
      }));
    render(<QueryClientProvider client={createQueryClient()}>
      <section aria-label="Image source settings"><ThirdPartyPromptSyncControls /></section>
      <section aria-label="Video source settings"><ThirdPartyPromptSyncControls /></section>
    </QueryClientProvider>);
    const image = within(screen.getByRole("region", { name: "Image source settings" }));
    const video = within(screen.getByRole("region", { name: "Video source settings" }));
    const imageToggle = image.getByRole("switch", { name: TITLE });
    await waitFor(() => expect(imageToggle).toBeEnabled());
    const user = userEvent.setup(); await user.click(imageToggle);
    expect(video.getByRole("switch", { name: TITLE })).not.toBeChecked();
    await user.click(image.getByRole("button", { name: SAVE }));
    await waitFor(() => expect(video.getByRole("switch", { name: TITLE })).toBeChecked());
    expect(writes).toEqual([{ enabled: true, expectedVersion: 3 }]);
    expect(reads).toBe(1);
  });

  it("turns an existing opt-in off only after Save with the current version", async () => {
    readSettings({ enabled: true, deploymentEnabled: true, version: 23 });
    const writes: unknown[] = [];
    server.use(http.put(SETTINGS_URL, async ({ request }) => {
      writes.push(await request.json());
      return HttpResponse.json({ enabled: false, deploymentEnabled: true, version: 24 });
    }));
    show(); const toggle = await readySwitch(); const user = userEvent.setup();
    expect(toggle).toBeChecked();
    expect(screen.getByText(/In-flight downloads may finish; cached prompts and manual sync remain available/)).toBeInTheDocument();
    await user.click(toggle);
    expect(toggle).not.toBeChecked(); expect(writes).toEqual([]);
    await user.click(screen.getByRole("button", { name: SAVE }));
    await waitFor(() => expect(writes).toEqual([{ enabled: false, expectedVersion: 23 }]));
    await waitFor(() => expect(screen.getByRole("button", { name: SAVE })).toBeDisabled());
  });

  it("keeps the unsaved choice and version after a failed save so it can be retried", async () => {
    readSettings({ enabled: false, deploymentEnabled: true, version: 6 });
    const writes: unknown[] = [];
    server.use(http.put(SETTINGS_URL, async ({ request }) => {
      writes.push(await request.json());
      return writes.length === 1
        ? HttpResponse.json({ detail: "Synthetic sync settings unavailable", status: 503 }, { status: 503, headers: { "Content-Type": "application/problem+json" } })
        : HttpResponse.json({ enabled: true, deploymentEnabled: true, version: 7 });
    }));
    show(); const user = userEvent.setup(); const toggle = await readySwitch();
    await user.click(toggle); await user.click(screen.getByRole("button", { name: SAVE }));
    expect(await screen.findByText("Synthetic sync settings unavailable")).toBeInTheDocument();
    expect(toggle).toBeChecked();
    expect(screen.getByRole("button", { name: SAVE })).toBeEnabled();
    await user.click(screen.getByRole("button", { name: SAVE }));
    await waitFor(() => expect(writes).toEqual([
      { enabled: true, expectedVersion: 6 }, { enabled: true, expectedVersion: 6 },
    ]));
    await waitFor(() => expect(screen.queryByText("Synthetic sync settings unavailable")).not.toBeInTheDocument());
    expect(toggle).toBeChecked();
  });

  it("preserves a conflicting draft and requires reloading before saving the new version", async () => {
    let persisted: ThirdPartyPromptSyncSettings = { enabled: false, deploymentEnabled: true, version: 10 };
    const writes: unknown[] = [];
    server.use(http.get(SETTINGS_URL, () => HttpResponse.json(persisted)),
      http.put(SETTINGS_URL, async ({ request }) => {
        writes.push(await request.json());
        if (writes.length === 1) {
          persisted = { ...persisted, version: 11 };
          return HttpResponse.json({ code: "THIRD_PARTY_SYNC_SETTINGS_CONFLICT", detail: "Synthetic concurrent settings change", status: 409 }, { status: 409, headers: { "Content-Type": "application/problem+json" } });
        }
        persisted = { enabled: true, deploymentEnabled: true, version: 12 };
        return HttpResponse.json(persisted);
      }));
    show(); const user = userEvent.setup(); const toggle = await readySwitch();
    await user.click(toggle); await user.click(screen.getByRole("button", { name: SAVE }));
    expect(await screen.findByText(CONFLICT)).toBeInTheDocument();
    expect(toggle).toBeChecked();
    expect(screen.getByRole("button", { name: SAVE })).toBeDisabled();
    await user.click(screen.getByRole("button", { name: RELOAD }));
    await waitFor(() => expect(toggle).not.toBeChecked());
    expect(screen.queryByText(CONFLICT)).not.toBeInTheDocument();
    await user.click(toggle); await user.click(screen.getByRole("button", { name: SAVE }));
    await waitFor(() => expect(writes).toEqual([
      { enabled: true, expectedVersion: 10 }, { enabled: true, expectedVersion: 11 },
    ]));
  });

  it("detects a newer server version without losing the draft before any PUT", async () => {
    let persisted: ThirdPartyPromptSyncSettings = { enabled: false, deploymentEnabled: true, version: 30 };
    const writes: unknown[] = [];
    server.use(http.get(SETTINGS_URL, () => HttpResponse.json(persisted)),
      http.put(SETTINGS_URL, async ({ request }) => { writes.push(await request.json()); return HttpResponse.json(persisted); }));
    const { client } = show(); const user = userEvent.setup(); const toggle = await readySwitch();
    await user.click(toggle);
    persisted = { ...persisted, version: 31 };
    await act(async () => { await client.invalidateQueries(); });
    expect(await screen.findByText(CONFLICT)).toBeInTheDocument();
    expect(toggle).toBeChecked();
    expect(screen.getByRole("button", { name: SAVE })).toBeDisabled();
    expect(writes).toEqual([]);
    await user.click(screen.getByRole("button", { name: RELOAD }));
    await waitFor(() => expect(toggle).not.toBeChecked());
    expect(screen.queryByText(CONFLICT)).not.toBeInTheDocument();
  });

  it("explains the deployment limit without treating a saved opt-in as permission to bypass it", async () => {
    readSettings({ enabled: false, deploymentEnabled: false, version: 40 });
    const writes: unknown[] = [];
    server.use(http.put(SETTINGS_URL, async ({ request }) => {
      writes.push(await request.json());
      return HttpResponse.json({ enabled: true, deploymentEnabled: false, version: 41 });
    }));
    show(); const user = userEvent.setup(); const toggle = await readySwitch();
    const explanation = "Scheduled sync is disabled by the deployment; enabling this setting will not start background downloads.";
    expect(screen.getByText(explanation)).toBeInTheDocument();
    await user.click(toggle); expect(writes).toEqual([]);
    await user.click(screen.getByRole("button", { name: SAVE }));
    await waitFor(() => expect(writes).toEqual([{ enabled: true, expectedVersion: 40 }]));
    expect(toggle).toBeChecked();
    expect(screen.getByText(explanation)).toBeInTheDocument();
  });

  it("disables editing after a read failure and reloads before allowing changes", async () => {
    server.use(http.get(SETTINGS_URL, () => HttpResponse.json({ detail: "Synthetic settings read unavailable", status: 503 }, { status: 503, headers: { "Content-Type": "application/problem+json" } })));
    show();
    expect(await screen.findByText("Synthetic settings read unavailable")).toBeInTheDocument();
    expect(screen.getByRole("switch", { name: TITLE })).toBeDisabled();
    expect(screen.getByRole("button", { name: SAVE })).toBeDisabled();
    readSettings({ enabled: false, deploymentEnabled: true, version: 50 });
    await userEvent.setup().click(screen.getByRole("button", { name: RELOAD }));
    expect(await readySwitch()).not.toBeChecked();
    expect(screen.queryByText("Synthetic settings read unavailable")).not.toBeInTheDocument();
  });
});
