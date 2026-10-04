import { QueryClientProvider } from "@tanstack/react-query";
import { useState } from "react";
import { createRoot } from "react-dom/client";
import { createQueryClient } from "../src/app/queryClient";
import { SmartEditDialog } from "../src/features/canvas/SmartEditDialog";
import type { MediaCapability } from "../src/shared/api/client";
import "../src/styles.css";

// Synthetic image and capability: submission is disabled and no provider is configured.
const sourceUrl = `data:image/svg+xml,${encodeURIComponent(
  '<svg xmlns="http://www.w3.org/2000/svg" width="640" height="360"><rect width="640" height="360" fill="teal"/></svg>',
)}`;
const capability: MediaCapability = {
  id: "synthetic-model", name: "Synthetic smart edit image model", enabled: true,
  version: 0, capabilityVersion: 1, adapterId: "OPENAI_GPT_IMAGE_2", kind: "IMAGE_GENERATION",
  minimumSeconds: 0, maximumSeconds: 0, maxReferenceAudios: 0, maxReferenceVideos: 0,
  maxReferenceImages: 1, supportedVideoInputModes: [], defaultVideoInputMode: null,
  supportsEndFrame: false, supportedImageAspectRatios: ["AUTO"], supportedImageResolutions: ["1K"],
  supportedImageQualities: ["high"], supportsTransparentBackground: true, supportsImageMask: true,
  mappingSha256: "a".repeat(64), settings: { pricing: { amount: "0.03", currency: "CNY", unit: "IMAGE" } },
};

function Fixture() {
  const [open, setOpen] = useState(false);
  return <>
    <button onClick={() => setOpen(true)}>打开智能编辑</button>
    {open ? <SmartEditDialog projectId="synthetic-project" sourceVersionId="synthetic-source"
      sourceTitle="Synthetic image" sourceUrl={sourceUrl} capabilities={[capability]} configuredMethod submitDisabled
      busy={false} error={null} onClose={() => setOpen(false)} onSubmit={() => {
        throw new Error("This layout fixture must not submit generation");
      }} /> : null}
  </>;
}

createRoot(document.getElementById("root")!).render(
  <QueryClientProvider client={createQueryClient()}><Fixture /></QueryClientProvider>,
);
