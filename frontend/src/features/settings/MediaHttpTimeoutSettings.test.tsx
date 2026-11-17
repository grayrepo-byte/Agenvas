import { QueryClientProvider } from "@tanstack/react-query";
import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { MemoryRouter } from "react-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import type { MediaCapability, MediaConnection, MediaSettings, RunningHubDefinition } from "../../shared/api/client";
import { setLocale, t } from "../../shared/i18n";
import { server } from "../../test/server";
import { definition as comfyDefinition } from "./comfyWorkflowFixture";
import { MediaSettingsPage } from "./MediaSettingsPage";

const policy = { connectSeconds: 7, writeSeconds: 61, readSeconds: 83, callSeconds: 101 };
const runningHub: RunningHubDefinition = {
  schemaVersion: 1,
  protocolVersion: "V2",
  targetType: "WORKFLOW",
  targetId: "123",
  usePersonalQueue: false,
  addMetadata: false,
  fields: [],
  fixedBindings: [],
  outputs: [{ kind: "IMAGE", primary: true, maxCount: 1 }],
};

function fixture(platform: MediaConnection["platform"] = "OPENAI", adapterId = "OPENAI_GPT_IMAGE_2"): MediaSettings {
  return {
    defaults: [],
    httpTimeoutDefaults: { [adapterId]: policy },
    connections: [
      {
        id: "service",
        name: "Service",
        platform,
        origin: null,
        enabled: true,
        version: 1,
        connectionVersion: 1,
        keyMask: null,
        connectivityStatus: "NOT_CHECKED",
        realGenerationTested: false,
        capabilities: [
          {
            id: "capability",
            name: "Capability",
            adapterId,
            kind: "IMAGE_GENERATION",
            enabled: true,
            version: 4,
            capabilityVersion: 2,
            minimumSeconds: 0,
            maximumSeconds: 0,
            maxReferenceImages: 1,
            maxReferenceAudios: 0,
            maxReferenceVideos: 0,
            supportedVideoInputModes: [],
            defaultVideoInputMode: null,
            supportsEndFrame: false,
            supportedImageAspectRatios: ["AUTO"],
            supportedImageResolutions: ["1K"],
            supportedImageQualities: ["medium"],
            supportsTransparentBackground: false,
            supportsImageMask: false,
            mappingSha256: "a".repeat(64),
            settings: adapterId.startsWith("RUNNINGHUB_")
              ? { runningHub }
              : adapterId.startsWith("COMFY_")
                ? { comfyWorkflow: comfyDefinition }
                : {},
          },
        ],
      },
    ],
  };
}

function mount(read: () => MediaSettings) {
  server.use(http.get("/api/v1/settings/media-connections", () => HttpResponse.json(read())));
  const queryClient = createQueryClient();
  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <MediaSettingsPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return queryClient;
}

async function openEditor(user: ReturnType<typeof userEvent.setup>) {
  await user.click(await screen.findByRole("button", { name: t("settings.mediaSettings.editCapability") }));
  return screen.getByRole("dialog");
}

async function openPublisher(user: ReturnType<typeof userEvent.setup>) {
  await user.click(await screen.findByRole("tab", { name: t("settings.mediaSettings.connections") }));
  await user.click(screen.getByRole("button", { name: t("settings.mediaSettings.publishCapability") }));
  const dialog = screen.getByRole("dialog");
  await user.type(
    within(dialog).getByRole("textbox", { name: t("settings.mediaSettings.newCapabilityName") }),
    "New capability",
  );
  return dialog;
}

async function openTimeouts(user: ReturnType<typeof userEvent.setup>, dialog: HTMLElement) {
  await user.click(within(dialog).getByRole("tab", { name: "Request timeouts" }));
}

function savedSettings(body: unknown): MediaCapability["settings"] {
  expect(body).toEqual(expect.objectContaining({ settings: expect.any(Object) }));
  return (body as { settings: MediaCapability["settings"] }).settings;
}

afterEach(() => vi.unstubAllGlobals());

beforeEach(() => {
  vi.stubGlobal("matchMedia", () => ({ matches: false }));
  setLocale("en");
  server.use(
    http.get("/api/v1/auth/me", () => HttpResponse.json({ id: "admin", role: "ADMIN" })),
    http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test" })),
  );
});

describe("media capability HTTP timeouts", () => {
  it("publishes the complete server defaults without requiring a visit to the timeout section", async () => {
    const settings = fixture();
    settings.connections[0]!.capabilities = [];
    const writes: unknown[] = [];
    server.use(
      http.post("/api/v1/settings/media-connections/service/capabilities", async ({ request }) => {
        writes.push(await request.json());
        return HttpResponse.json(settings);
      }),
    );
    mount(() => settings);
    const user = userEvent.setup();
    const dialog = await openPublisher(user);
    await user.click(within(dialog).getByRole("button", { name: t("settings.mediaSettings.publishCapability") }));
    await waitFor(() => expect(writes).toHaveLength(1));
    expect(savedSettings(writes[0]).httpTimeouts).toEqual(policy);
    expect(savedSettings(writes[0])).not.toHaveProperty("httpTimeoutDraft");
  });

  it("keeps publication defaults and the idempotency key after failure and a background refresh", async () => {
    let settings = fixture();
    settings.connections[0]!.capabilities = [];
    const writes: { body: unknown; key: string | null }[] = [];
    server.use(
      http.post("/api/v1/settings/media-connections/service/capabilities", async ({ request }) => {
        writes.push({ body: await request.json(), key: request.headers.get("Idempotency-Key") });
        return HttpResponse.json(
          { detail: "Synthetic save failure" },
          { status: 503, headers: { "Content-Type": "application/problem+json" } },
        );
      }),
    );
    const queryClient = mount(() => settings);
    const user = userEvent.setup();
    const dialog = await openPublisher(user);
    await user.click(within(dialog).getByRole("button", { name: t("settings.mediaSettings.publishCapability") }));
    await within(dialog).findByText("Synthetic save failure");
    settings = { ...settings, httpTimeoutDefaults: { OPENAI_GPT_IMAGE_2: { ...policy, readSeconds: 99 } } };
    await act(() => queryClient.invalidateQueries({ queryKey: ["settings", "media"] }));
    await openTimeouts(user, dialog);
    expect(within(dialog).getByRole("spinbutton", { name: "Read timeout (seconds)" })).toHaveValue(83);
    await user.click(within(dialog).getByRole("button", { name: t("settings.mediaSettings.publishCapability") }));
    await waitFor(() => expect(writes).toHaveLength(2));
    expect(writes[1]).toEqual(writes[0]);
    expect(savedSettings(writes[1]!.body).httpTimeouts).toEqual(policy);
  });

  it("displays server defaults for a legacy capability while a metadata-only save keeps them absent", async () => {
    const settings = fixture();
    const writes: unknown[] = [];
    server.use(
      http.put("/api/v1/settings/media-connections/service/capabilities/capability", async ({ request }) => {
        writes.push(await request.json());
        return HttpResponse.json(settings);
      }),
    );
    mount(() => settings);
    const user = userEvent.setup();
    const dialog = await openEditor(user);
    await user.clear(within(dialog).getByRole("textbox", { name: t("settings.mediaSettings.capabilityName") }));
    await user.type(
      within(dialog).getByRole("textbox", { name: t("settings.mediaSettings.capabilityName") }),
      "Renamed",
    );
    await openTimeouts(user, dialog);
    expect(within(dialog).getByRole("spinbutton", { name: "Connection timeout (seconds)" })).toHaveValue(7);
    expect(within(dialog).getByRole("spinbutton", { name: "Write timeout (seconds)" })).toHaveValue(61);
    expect(within(dialog).getByRole("spinbutton", { name: "Read timeout (seconds)" })).toHaveValue(83);
    expect(within(dialog).getByRole("spinbutton", { name: "Whole request timeout (seconds)" })).toHaveValue(101);
    await user.click(within(dialog).getByRole("button", { name: t("settings.mediaSettings.saveCapability") }));
    await waitFor(() => expect(writes).toHaveLength(1));
    expect(savedSettings(writes[0])).not.toHaveProperty("httpTimeouts");
  });

  it("retains edited timeouts after a failed save and reloads the saved complete policy", async () => {
    let settings = fixture();
    const writes: unknown[] = [];
    server.use(
      http.put("/api/v1/settings/media-connections/service/capabilities/capability", async ({ request }) => {
        const body = await request.json();
        writes.push(body);
        if (writes.length === 1)
          return HttpResponse.json(
            { detail: "Synthetic save failure", title: "Save failed", code: "INTERNAL_ERROR" },
            { status: 503, headers: { "Content-Type": "application/problem+json" } },
          );
        settings = {
          ...settings,
          connections: settings.connections.map(connection => ({
            ...connection,
            capabilities: connection.capabilities.map(capability => ({
              ...capability,
              version: 5,
              capabilityVersion: 3,
              settings: savedSettings(body),
            })),
          })),
        };
        return HttpResponse.json(settings);
      }),
    );
    mount(() => settings);
    const user = userEvent.setup();
    let dialog = await openEditor(user);
    await openTimeouts(user, dialog);
    const read = within(dialog).getByRole("spinbutton", { name: "Read timeout (seconds)" });
    await user.clear(read);
    await user.type(read, "97");
    await user.click(within(dialog).getByRole("button", { name: t("settings.mediaSettings.saveCapability") }));
    await within(dialog).findByText("Synthetic save failure");
    expect(read).toHaveValue(97);
    await waitFor(() =>
      expect(within(dialog).getByRole("button", { name: t("settings.mediaSettings.saveCapability") })).toBeEnabled(),
    );
    await user.click(within(dialog).getByRole("button", { name: t("settings.mediaSettings.saveCapability") }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(savedSettings(writes[1]).httpTimeouts).toEqual({ ...policy, readSeconds: 97 });
    expect(writes[1]).toEqual(writes[0]);
    dialog = await openEditor(user);
    await openTimeouts(user, dialog);
    expect(within(dialog).getByRole("spinbutton", { name: "Read timeout (seconds)" })).toHaveValue(97);
    expect(within(dialog).getByRole("spinbutton", { name: "Write timeout (seconds)" })).toHaveValue(61);
  });

  it("keeps a timeout draft and CAS baseline during background changes until explicit reload", async () => {
    let settings = fixture();
    const queryClient = mount(() => settings);
    const user = userEvent.setup();
    const dialog = await openEditor(user);
    await openTimeouts(user, dialog);
    const read = within(dialog).getByRole("spinbutton", { name: "Read timeout (seconds)" });
    await user.clear(read);
    await user.type(read, "97");
    settings = fixture();
    settings.connections[0]!.capabilities[0] = {
      ...settings.connections[0]!.capabilities[0]!,
      version: 5,
      settings: { httpTimeouts: { ...policy, readSeconds: 89 } },
    };
    await act(() => queryClient.invalidateQueries({ queryKey: ["settings", "media"] }));
    await waitFor(() =>
      expect(within(dialog).getByRole("button", { name: t("settings.mediaSettings.saveCapability") })).toBeDisabled(),
    );
    expect(read).toHaveValue(97);
    await user.click(within(dialog).getByRole("button", { name: t("common.refreshVersion") }));
    expect(within(dialog).getByRole("spinbutton", { name: "Read timeout (seconds)" })).toHaveValue(89);
  });

  it("preserves timeout edits on CAS conflict and requires explicit reload before saving again", async () => {
    let settings = fixture();
    const writes: unknown[] = [];
    server.use(
      http.put("/api/v1/settings/media-connections/service/capabilities/capability", async ({ request }) => {
        writes.push(await request.json());
        if (writes.length === 1) {
          settings = fixture();
          settings.connections[0]!.capabilities[0] = {
            ...settings.connections[0]!.capabilities[0]!,
            version: 5,
            settings: { httpTimeouts: { ...policy, readSeconds: 89 } },
          };
          return HttpResponse.json(
            { detail: "Synthetic conflict", code: "MEDIA_CAPABILITY_CONFLICT" },
            { status: 409, headers: { "Content-Type": "application/problem+json" } },
          );
        }
        return HttpResponse.json(settings);
      }),
    );
    mount(() => settings);
    const user = userEvent.setup();
    const dialog = await openEditor(user);
    await openTimeouts(user, dialog);
    const read = within(dialog).getByRole("spinbutton", { name: "Read timeout (seconds)" });
    fireEvent.change(read, { target: { value: "97" } });
    await user.click(within(dialog).getByRole("button", { name: t("settings.mediaSettings.saveCapability") }));
    await within(dialog).findByText("Synthetic conflict");
    await waitFor(() =>
      expect(within(dialog).getByRole("button", { name: t("settings.mediaSettings.saveCapability") })).toBeDisabled(),
    );
    expect(read).toHaveValue(97);
    expect(writes).toHaveLength(1);
    expect(writes[0]).toEqual(expect.objectContaining({ expectedVersion: 4 }));
    await user.click(within(dialog).getByRole("button", { name: t("common.refreshVersion") }));
    expect(within(dialog).getByRole("spinbutton", { name: "Read timeout (seconds)" })).toHaveValue(89);
    await user.click(within(dialog).getByRole("button", { name: t("settings.mediaSettings.saveCapability") }));
    await waitFor(() => expect(writes).toHaveLength(2));
    expect(writes[1]).toEqual(expect.objectContaining({ expectedVersion: 5 }));
    expect(savedSettings(writes[1]).httpTimeouts).toEqual({ ...policy, readSeconds: 89 });
  });

  it("requires complete explicit input if a new publication has no server timeout defaults", async () => {
    const settings = fixture();
    delete settings.httpTimeoutDefaults;
    settings.connections[0]!.capabilities = [];
    const writes: unknown[] = [];
    server.use(
      http.post("/api/v1/settings/media-connections/service/capabilities", async ({ request }) => {
        writes.push(await request.json());
        return HttpResponse.json(settings);
      }),
    );
    mount(() => settings);
    const user = userEvent.setup();
    const dialog = await openPublisher(user);
    await user.click(within(dialog).getByRole("button", { name: t("settings.mediaSettings.publishCapability") }));
    expect(writes).toEqual([]);
    await openTimeouts(user, dialog);
    for (const [name, value] of [
      ["Connection timeout (seconds)", "7"],
      ["Write timeout (seconds)", "61"],
      ["Read timeout (seconds)", "83"],
      ["Whole request timeout (seconds)", "101"],
    ]) {
      fireEvent.change(within(dialog).getByRole("spinbutton", { name }), { target: { value } });
    }
    await user.click(within(dialog).getByRole("button", { name: t("settings.mediaSettings.publishCapability") }));
    await waitFor(() => expect(writes).toHaveLength(1));
    expect(savedSettings(writes[0]).httpTimeouts).toEqual(policy);
  });

  it("publishes edited RunningHub timeouts together with its workflow contract", async () => {
    const settings = fixture("RUNNINGHUB", "RUNNINGHUB_IMAGE");
    settings.connections[0]!.capabilities = [];
    const writes: unknown[] = [];
    server.use(
      http.post("/api/v1/settings/media-connections/service/capabilities", async ({ request }) => {
        writes.push(await request.json());
        return HttpResponse.json(settings);
      }),
    );
    mount(() => settings);
    const user = userEvent.setup();
    const dialog = await openPublisher(user);
    await user.type(within(dialog).getByRole("textbox", { name: t("settings.runningHub.targetId") }), "123");
    await user.click(within(dialog).getByRole("link", { name: /Request timeouts/ }));
    fireEvent.change(within(dialog).getByRole("spinbutton", { name: "Write timeout (seconds)" }), {
      target: { value: "91" },
    });
    await user.click(within(dialog).getByRole("button", { name: t("settings.mediaSettings.publishCapability") }));
    await waitFor(() => expect(writes).toHaveLength(1));
    expect(savedSettings(writes[0]).httpTimeouts).toEqual({ ...policy, writeSeconds: 91 });
    expect(savedSettings(writes[0]).runningHub?.targetId).toBe("123");
  });

  it.each(["", "0", "-1", "1.5", "121"])("retains invalid connect value %s and prevents submission", async value => {
    const settings = fixture();
    const writes: unknown[] = [];
    server.use(
      http.put("/api/v1/settings/media-connections/service/capabilities/capability", async ({ request }) => {
        writes.push(await request.json());
        return HttpResponse.json(settings);
      }),
    );
    mount(() => settings);
    const user = userEvent.setup();
    const dialog = await openEditor(user);
    await openTimeouts(user, dialog);
    const connect = within(dialog).getByRole("spinbutton", { name: "Connection timeout (seconds)" });
    fireEvent.change(connect, { target: { value } });
    expect(connect).toBeInvalid();
    fireEvent.submit(connect.closest("form")!);
    await waitFor(() =>
      expect(within(dialog).getByRole("button", { name: t("settings.mediaSettings.saveCapability") })).toBeEnabled(),
    );
    expect(writes).toEqual([]);
    expect(connect).toHaveValue(value === "" ? null : Number(value));
  });

  it("validates every phase against the whole request deadline and reveals hidden invalid fields", async () => {
    const settings = fixture();
    mount(() => settings);
    const user = userEvent.setup();
    const dialog = await openEditor(user);
    await openTimeouts(user, dialog);
    const call = within(dialog).getByRole("spinbutton", { name: "Whole request timeout (seconds)" });
    fireEvent.change(call, { target: { value: "60" } });
    expect(within(dialog).getByRole("spinbutton", { name: "Write timeout (seconds)" })).toBeInvalid();
    expect(within(dialog).getByRole("spinbutton", { name: "Read timeout (seconds)" })).toBeInvalid();
    await user.click(within(dialog).getByRole("tab", { name: t("settings.mediaSettings.modelConfiguration") }));
    await user.click(within(dialog).getByRole("button", { name: t("settings.mediaSettings.saveCapability") }));
    await waitFor(() =>
      expect(within(dialog).getByRole("tab", { name: "Request timeouts" })).toHaveAttribute("aria-selected", "true"),
    );
    expect(within(dialog).getByRole("spinbutton", { name: "Whole request timeout (seconds)" })).toHaveValue(60);
  });

  it("does not show or send HTTP settings for Mock", async () => {
    const settings = fixture("MOCK", "MOCK_IMAGE");
    settings.connections[0]!.capabilities[0]!.settings = { httpTimeouts: policy };
    const writes: unknown[] = [];
    server.use(
      http.put("/api/v1/settings/media-connections/service/capabilities/capability", async ({ request }) => {
        writes.push(await request.json());
        return HttpResponse.json(settings);
      }),
    );
    mount(() => settings);
    const user = userEvent.setup();
    const dialog = await openEditor(user);
    expect(within(dialog).queryByRole("tab", { name: "Request timeouts" })).not.toBeInTheDocument();
    expect(within(dialog).queryByLabelText("Connection timeout (seconds)")).not.toBeInTheDocument();
    await user.click(within(dialog).getByRole("button", { name: t("settings.mediaSettings.saveCapability") }));
    await waitFor(() => expect(writes).toHaveLength(1));
    expect(savedSettings(writes[0])).not.toHaveProperty("httpTimeouts");
  });

  it("does not expose HTTP settings or an editor for local capabilities", async () => {
    const settings = fixture("LOCAL", "LOCAL_IMAGE_PROCESSOR");
    mount(() => settings);
    await screen.findByTitle("Capability");
    expect(screen.queryByRole("button", { name: t("settings.mediaSettings.editCapability") })).not.toBeInTheDocument();
    expect(screen.queryByRole("tab", { name: "Request timeouts" })).not.toBeInTheDocument();
  });

  it("makes RunningHub timeout settings reachable through its existing section navigation without another form", async () => {
    const settings = fixture("RUNNINGHUB", "RUNNINGHUB_IMAGE");
    const writes: unknown[] = [];
    server.use(
      http.put("/api/v1/settings/media-connections/service/capabilities/capability", async ({ request }) => {
        writes.push(await request.json());
        return HttpResponse.json(settings);
      }),
    );
    mount(() => settings);
    const user = userEvent.setup();
    const dialog = await openEditor(user);
    expect(dialog.querySelectorAll("form")).toHaveLength(1);
    const link = within(dialog).getByRole("link", { name: /Request timeouts/ });
    await user.click(link);
    const read = within(dialog).getByRole("spinbutton", { name: "Read timeout (seconds)" });
    fireEvent.change(read, { target: { value: "99" } });
    await user.click(within(dialog).getByRole("button", { name: t("settings.mediaSettings.saveCapability") }));
    await waitFor(() => expect(writes).toHaveLength(1));
    expect(savedSettings(writes[0]).httpTimeouts).toEqual({ ...policy, readSeconds: 99 });
    expect(savedSettings(writes[0]).runningHub?.targetId).toBe("123");
  });

  it("shows ComfyUI timeouts in its publication review and preserves them with the graph", async () => {
    const settings = fixture("COMFYUI", "COMFY_VIDEO_V1");
    settings.connections[0]!.capabilities[0]!.kind = "VIDEO_GENERATION";
    settings.connections[0]!.capabilities[0]!.settings.httpTimeouts = policy;
    const writes: unknown[] = [];
    server.use(
      http.put("/api/v1/settings/media-connections/service/capabilities/capability", async ({ request }) => {
        writes.push(await request.json());
        return HttpResponse.json(settings);
      }),
    );
    mount(() => settings);
    const user = userEvent.setup();
    const dialog = await openEditor(user);
    await user.click(within(dialog).getByRole("button", { name: /3\./ }));
    const write = within(dialog).getByRole("spinbutton", { name: "Write timeout (seconds)" });
    fireEvent.change(write, { target: { value: "91" } });
    await user.click(within(dialog).getByRole("button", { name: t("settings.mediaSettings.saveCapability") }));
    await waitFor(() => expect(writes).toHaveLength(1));
    expect(savedSettings(writes[0]).httpTimeouts).toEqual({ ...policy, writeSeconds: 91 });
    expect(savedSettings(writes[0]).comfyWorkflow).toEqual(comfyDefinition);
  });
});
