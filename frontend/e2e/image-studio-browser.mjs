import assert from "node:assert/strict";
import { mkdir, writeFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { join } from "node:path";
import { createServer } from "vite";
import { createBrowserSession } from "./browser-session.mjs";

const root = fileURLToPath(new URL("../", import.meta.url));
const server = await createServer({
  root,
  configFile: join(root, "vite.config.ts"),
  server: { host: "127.0.0.1", port: 0, open: false },
});
let browser;
const viewports = [
  { width: 1440, height: 1000 },
  { width: 390, height: 844 },
  { width: 844, height: 390 },
];
try {
  await server.listen();
  browser = await createBrowserSession();
  const { cdp, page, waitFor } = browser;
  const base = server.resolvedUrls.local[0];
  for (const viewport of viewports) {
    await cdp("Emulation.setDeviceMetricsOverride", { ...viewport, deviceScaleFactor: 1, mobile: false });
    for (const locale of ["zh", "en", "ru", "ja"]) {
      await cdp("Page.navigate", { url: new URL(`/e2e/image-studio.html?locale=${locale}`, base).href });
      await waitFor(() => document.querySelectorAll(".image-studio-result-card").length === 4);
      await waitFor(() =>
        [...document.querySelectorAll(".image-studio-result-card img")].every(
          image => image.complete && image.naturalWidth > 0,
        ),
      );
      const layout = await page(() => ({
        overflow: document.documentElement.scrollWidth > innerWidth,
        submitBottom: document.querySelector('.image-studio-controls button[type="submit"]').getBoundingClientRect()
          .bottom,
        controls: document.querySelector(".image-studio-controls").getBoundingClientRect().toJSON(),
        results: document.querySelector(".image-studio-results").getBoundingClientRect().toJSON(),
        images: [...document.querySelectorAll(".image-studio-image-button")].map(element => ({
          width: element.clientWidth,
          height: element.clientHeight,
        })),
        downloads: [...document.querySelectorAll(".image-studio-result-card a")].map(anchor =>
          anchor.getAttribute("href"),
        ),
      }));
      assert.equal(layout.overflow, false, `No horizontal overflow: ${viewport.width}/${locale}`);
      assert.ok(layout.images.every(image => image.width > 100 && image.height > 100));
      assert.ok(
        layout.downloads.every(
          url => url.startsWith("/api/v1/projects/synthetic-image-studio/assets/") && url.endsWith("/content"),
        ),
      );
      if (viewport.width === 1440)
        assert.ok(layout.submitBottom <= viewport.height, "Desktop generation action stays visible");
      if (viewport.width === 1440) assert.ok(layout.controls.right < layout.results.left, "Desktop uses two columns");
      if (viewport.width === 390)
        assert.ok(layout.controls.bottom < layout.results.top, "Narrow screen stacks controls before results");
      await page(() => document.querySelector(".image-studio-results-header button").click());
      await waitFor(() => document.querySelector("textarea").value.startsWith("清晨海边的小屋"));
      if (locale === "zh" && viewport.width === 1440) {
        await page(() => {
          const input = document.querySelector('input[type="file"]');
          const file = new File([new Uint8Array([1, 2, 3])], "synthetic-reference.png", { type: "image/png" });
          const transfer = new DataTransfer();
          transfer.items.add(file);
          input.files = transfer.files;
          input.dispatchEvent(new Event("change", { bubbles: true }));
        });
        await waitFor(() => document.querySelectorAll(".image-studio-reference").length === 1);
        await page(() => document.querySelector('.image-studio-controls button[type="submit"]').click());
        await waitFor(() =>
          [...document.querySelectorAll("button")].some(button => button.textContent.trim() === "重试受理"),
        );
        await page(() =>
          [...document.querySelectorAll("button")].find(button => button.textContent.trim() === "重试受理").click(),
        );
        await waitFor(
          () => window.__imageStudio.evidence().writes.length === 2 && !document.querySelector("fieldset").disabled,
        );
        const evidence = await page(() => window.__imageStudio.evidence());
        assert.deepEqual(evidence.writes[0], evidence.writes[1], "Uncertain acceptance retries one immutable command");
        assert.equal(evidence.uploads, 1);
        assert.equal(evidence.writes[0].request.parameters.generationCount, 4);
        assert.deepEqual(evidence.writes[0].request.referenceVersionIds, ["synthetic-reference-version"]);
        assert.equal(JSON.stringify(evidence.writes).includes("canvasItemId"), false);
        assert.deepEqual(evidence.unexpected, []);
        await page(() => document.querySelector(".image-studio-image-button").click());
        await waitFor(() => Boolean(document.querySelector(".yarl__root")));
        await cdp("Input.dispatchKeyEvent", {
          type: "keyDown",
          key: "Escape",
          code: "Escape",
          windowsVirtualKeyCode: 27,
        });
        await cdp("Input.dispatchKeyEvent", {
          type: "keyUp",
          key: "Escape",
          code: "Escape",
          windowsVirtualKeyCode: 27,
        });
        await waitFor(() => !document.querySelector(".yarl__root"));
        if (process.env.AGENVAS_STUDIO_SCREENSHOT) {
          await mkdir(fileURLToPath(new URL(".", `file://${process.env.AGENVAS_STUDIO_SCREENSHOT}`)), {
            recursive: true,
            mode: 0o700,
          });
          const capture = await cdp("Page.captureScreenshot", { format: "png", captureBeyondViewport: false });
          await writeFile(process.env.AGENVAS_STUDIO_SCREENSHOT, Buffer.from(capture.data, "base64"), { mode: 0o600 });
        }
      }
    }
  }
  assert.deepEqual(browser.exceptions, []);
  console.log(
    "Image Studio browser passed: 3 viewports × 4 languages; private previews/download links, history reuse, upload, immutable acceptance retry, full results; synthetic API only.",
  );
} finally {
  await browser?.close();
  await server.close();
}
