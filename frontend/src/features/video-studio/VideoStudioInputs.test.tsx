import { QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { useState } from "react";
import { MemoryRouter } from "react-router";
import { expect, it, vi } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import type { VideoStudioCapability, VideoStudioReferenceAsset } from "../../shared/api/videoStudio";
import type { CreatorInputField } from "../../shared/creatorInputs";
import { t } from "../../shared/i18n";
import { server } from "../../test/server";
import { VideoStudioInputs } from "./VideoStudioInputs";
import { VideoStudioPage } from "./VideoStudioPage";
import { useVideoStudioDraftStore } from "./videoStudioDraft";

const model: VideoStudioCapability = {
  capabilityId: "workflow",
  capabilityVersion: 1,
  name: "Workflow",
  connectionName: "Mock",
  adapterId: "MOCK_VIDEO",
  modes: ["GENERAL_REFERENCE"],
  defaultMode: "GENERAL_REFERENCE",
  supportsEndFrame: false,
  requiresEndFrame: false,
  minDurationSeconds: null,
  maxDurationSeconds: null,
  defaultDurationSeconds: null,
  aspectRatios: [],
  defaultAspectRatio: null,
  videoResolutions: [],
  defaultVideoResolution: null,
  referenceLimits: { image: 1, video: 0, audio: 0 },
  fields: [],
  defaultDynamicValues: {},
};

const scalar = { source: "PARAMETER", type: "INTEGER", advanced: false, required: false } as const;
const sampling: CreatorInputField = { ...scalar, key: "steps", label: "Steps", defaultValue: 6, group: "Motion" };

function openStudio(fields: CreatorInputField[], onSubmit = vi.fn()) {
  useVideoStudioDraftStore.getState().clear();
  server.use(
    http.get("/api/v1/auth/me", () => HttpResponse.json({ id: "artist", loginName: "artist", role: "ADMIN" })),
    http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "synthetic" })),
    http.get("/api/v1/video-studio/context", () =>
      HttpResponse.json({
        projectId: "private-studio",
        defaultCapabilityId: model.capabilityId,
        capabilities: [{ ...model, fields }],
      }),
    ),
    http.get("/api/v1/video-studio/generations", () => HttpResponse.json({ items: [], nextCursor: null })),
    http.post("/api/v1/video-studio/generations", async ({ request }) => {
      onSubmit(await request.json());
      return HttpResponse.json({}, { status: 422 });
    }),
  );
  return render(
    <QueryClientProvider client={createQueryClient()}>
      <MemoryRouter initialEntries={["/video-studio"]}>
        <VideoStudioPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

it("submits published defaults from collapsed groups and omits fields disabled by the contract", async () => {
  const posted = vi.fn();
  openStudio(
    [
      sampling,
      { ...scalar, key: "seed", label: "Seed", defaultValue: 0, group: "Output" },
      { ...scalar, key: "motion", label: "Motion blur", type: "BOOLEAN", defaultValue: false, group: "Motion" },
      {
        ...scalar,
        key: "blurSteps",
        label: "Blur passes",
        defaultValue: 5,
        enabledWhen: { field: "motion", value: true },
      },
    ],
    posted,
  );
  const user = userEvent.setup();
  const disclosure = await screen.findByRole("button", { name: new RegExp(t("media.runningHub.advancedParameters")) });
  expect(disclosure).toHaveAttribute("aria-expanded", "false");
  expect(screen.queryByRole("spinbutton", { name: "Steps" })).not.toBeInTheDocument();
  await user.click(disclosure);
  const motion = screen.getAllByRole("group", { name: "Motion" });
  expect(within(motion[0]!).getByRole("spinbutton", { name: "Steps" })).toHaveValue(6);
  expect(within(motion[1]!).getByRole("combobox", { name: "Motion blur" })).toBeVisible();
  expect(screen.queryByRole("spinbutton", { name: "Blur passes" })).not.toBeInTheDocument();
  await user.click(disclosure);
  await user.click(screen.getByRole("button", { name: t("videoStudio.generate") }));
  await waitFor(() =>
    expect(posted).toHaveBeenCalledExactlyOnceWith(
      expect.objectContaining({ dynamicValues: { steps: 6, seed: 0, motion: false } }),
    ),
  );
});

it("opens an incomplete advanced stage and keeps the field focused through the correction", async () => {
  openStudio([{ ...scalar, key: "direction", label: "Blocking", type: "STRING", required: true, advanced: true }]);
  const field = await screen.findByRole("textbox", { name: /Blocking/ });
  const disclosure = screen.getByRole("button", { name: new RegExp(t("media.runningHub.advancedParameters")) });
  const submit = screen.getByRole("button", { name: t("videoStudio.generate") });
  expect(disclosure).toHaveAttribute("aria-expanded", "true");
  expect(submit).toBeDisabled();
  await userEvent.setup().type(field, "Pan left");
  expect(field).toHaveFocus();
  expect(field).toHaveValue("Pan left");
  expect(disclosure).toHaveAttribute("aria-expanded", "true");
  expect(submit).toBeEnabled();
});

it("reveals a collapsed number field when native step validation blocks submission", async () => {
  const posted = vi.fn();
  openStudio([{ ...sampling, minimum: 0, step: 2, defaultValue: 4 }], posted);
  const disclosure = await screen.findByRole("button", { name: new RegExp(t("media.runningHub.advancedParameters")) });
  const user = userEvent.setup();
  await user.click(disclosure);
  const input = screen.getByRole("spinbutton", { name: "Steps" });
  await user.clear(input);
  await user.type(input, "3");
  await user.click(disclosure);
  await user.click(screen.getByRole("button", { name: t("videoStudio.generate") }));
  await waitFor(() => expect(input).toHaveFocus());
  expect(disclosure).toHaveAttribute("aria-expanded", "true");
  expect(input).toBeInvalid();
  expect(posted).not.toHaveBeenCalled();
});

it("retries the selected file in its exact named slot after an upload failure", async () => {
  const image: CreatorInputField = { ...scalar, key: "portrait", type: "IMAGE", label: "Portrait" };
  const upload = vi.fn().mockRejectedValueOnce(new Error("Connection lost")).mockResolvedValueOnce(undefined);
  const settings = {
    ...model,
    references: [],
    dynamicValues: {},
    duration: "",
    startFrame: null,
    endFrame: null,
    aspectRatio: null,
    videoResolution: null,
    mode: model.defaultMode,
  };
  render(
    <VideoStudioInputs
      settings={settings}
      model={{ ...model, fields: [image] }}
      fields={[image]}
      values={{}}
      labels={[]}
      disabled={false}
      restoring={false}
      onUploadReference={upload}
      onUploadFrame={vi.fn()}
      onRemoveFrame={vi.fn()}
      onRemoveReference={vi.fn()}
      onChange={vi.fn()}
    />,
  );
  const file = new File(["image bytes"], "portrait.png", { type: "image/png" });
  const user = userEvent.setup();
  await user.upload(
    screen.getByLabelText(t("media.runningHub.uploadNamed", { 0: "Portrait" }), { selector: "input" }),
    file,
  );
  expect(await screen.findByRole("alert")).toHaveTextContent("Connection lost");
  await user.click(screen.getByRole("button", { name: t("media.retryUpload") }));
  expect(upload.mock.calls).toEqual([
    ["IMAGE", file, "portrait"],
    ["IMAGE", file, "portrait"],
  ]);
  expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  expect(screen.getByRole("button", { name: t("media.runningHub.uploadNamed", { 0: image.label }) })).toHaveFocus();
});

it("keeps a named slot and its keyboard target when an image is added, replaced or removed", async () => {
  const field: CreatorInputField = { ...scalar, key: "portrait", type: "IMAGE", label: "Portrait" };
  function Editor() {
    const [references, setReferences] = useState<VideoStudioReferenceAsset[]>([]);
    const settings = {
      ...model,
      references,
      dynamicValues: {},
      duration: "",
      startFrame: null,
      endFrame: null,
      aspectRatio: null,
      videoResolution: null,
      mode: model.defaultMode,
    };
    return (
      <VideoStudioInputs
        settings={settings}
        model={{ ...model, fields: [field] }}
        fields={[field]}
        values={{}}
        labels={references.map(() => field.label)}
        disabled={false}
        restoring={false}
        onUploadReference={async (kind, file) =>
          setReferences([
            {
              assetId: file.name,
              versionId: file.name,
              kind,
              artifactId: "portrait",
              fieldKey: field.key,
              projectId: "private-studio",
              width: 640,
              height: 480,
              durationMs: null,
              contentType: file.type,
            },
          ])
        }
        onRemoveReference={() => setReferences([])}
        onUploadFrame={vi.fn()}
        onRemoveFrame={vi.fn()}
        onChange={vi.fn()}
      />
    );
  }

  render(<Editor />);
  const user = userEvent.setup();
  const uploadName = t("media.runningHub.uploadNamed", { 0: field.label });
  const replaceName = t("media.upload.replaceTitle", { 0: field.label });
  const button = screen.getByRole("button", { name: uploadName });
  const input = screen.getByLabelText(uploadName, { selector: "input" });
  await user.upload(input, new File(["synthetic"], "first.png", { type: "image/png" }));
  expect(screen.getByRole("button", { name: replaceName })).toBe(button);
  expect(button).toHaveAccessibleDescription("640 × 480");

  const preview = screen.getByRole("img", { name: field.label });
  expect(preview).toHaveAttribute("src", expect.stringContaining("first.png/thumbnail"));
  fireEvent.error(preview);
  expect(preview).toHaveAttribute("src", expect.stringContaining("first.png/content"));
  fireEvent.error(preview);
  expect(screen.getByRole("img", { name: `${field.label} · ${t("media.card.previewUnavailable")}` })).toBeVisible();

  await user.upload(input, new File(["synthetic"], "second.png", { type: "image/png" }));
  expect(screen.getByRole("img", { name: field.label })).toHaveAttribute(
    "src",
    expect.stringContaining("second.png/thumbnail"),
  );
  const remove = screen.getByRole("button", { name: t("videoStudio.removeFrame", { 0: field.label }) });
  remove.focus();
  await user.keyboard("{Enter}");
  expect(screen.getByRole("button", { name: uploadName })).toBe(button);
  expect(button).toHaveFocus();
  expect(screen.queryByRole("list", { name: t("common.mixedReference") })).not.toBeInTheDocument();
});
