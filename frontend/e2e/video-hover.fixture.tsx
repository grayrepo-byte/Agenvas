import { StrictMode, useState } from "react";
import { createRoot } from "react-dom/client";
import { VideoPreview } from "../src/features/canvas/VideoPreview";
import { setLocale } from "../src/shared/i18n";
import "../src/styles.css";

setLocale("zh");
const poster = `data:image/svg+xml,${encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" width="320" height="180"><rect width="320" height="180" fill="teal"/></svg>')}`;

// The runner supplies local synthetic video with a real sine-wave audio track.
function Fixture() {
  const [selections, setSelections] = useState(0);
  return <main id="fixture" data-selections={selections} style={{ margin: 40 }}>
    <button id="page-gesture" type="button">Allow a real page interaction</button>
    <div id="valid-preview" style={{ width: 400, height: 225, marginTop: 40 }}
      onClick={() => setSelections((value) => value + 1)}>
      <VideoPreview title="Synthetic video with sound" src="/e2e/video-hover-valid.mp4"
        posterSrc={poster} demo={false} width={320} height={180} contentType="video/mp4" />
    </div>
    <div id="retry-preview" style={{ width: 400, height: 225, marginTop: 40 }}>
      <VideoPreview title="Synthetic damaged video" src="/e2e/video-hover-retry.mp4"
        posterSrc={poster} demo={false} width={320} height={180} contentType="video/mp4" />
    </div>
  </main>;
}

createRoot(document.getElementById("root")!).render(<StrictMode><Fixture /></StrictMode>);
