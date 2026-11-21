# Optional photography fixtures

The production Studio contains no sample results. Its default browser regression uses independent synthetic geometry and an isolated API fixture. Photography screenshots may instead run that same real React page with openly licensed photographs, explicitly labeled **Mock / not a real model generation** in the test prompt. These are UI acceptance fixtures, not evidence that a provider generated these photos.

| Fixture | Photographer and license | Source |
| --- | --- | --- |
| `lake-landscape.jpg` | Bonnie Moreland, CC0 1.0 | [Lake Mountain Landscape](https://commons.wikimedia.org/wiki/File:Lake_Mountain_Landscape.jpg), using the 1920×1280 derivative credited in [shape_vectorizer](https://github.com/NotCoco/shape_vectorizer) |
| `rocket.jpg` | SpaceX, public domain | [scikit-image data credits](https://scikit-image.org/docs/0.23.x/api/skimage.data.html#skimage.data.rocket) |
| `coffee.png` | Rachel Michetti, CC0 | [scikit-image data credits](https://scikit-image.org/docs/0.23.x/api/skimage.data.html#skimage.data.coffee) |
| `eagle.png` | Dayane Machado, CC0 | [scikit-image data credits](https://scikit-image.org/docs/0.23.x/api/skimage.data.html#skimage.data.eagle) |

From `frontend`, optionally run `python3 e2e/fetch-image-studio-photos.py /absolute/private/fixture-directory`. Downloads preserve the executor's proxy and TLS settings and verify known SHA-256 values. No credentials or model service are involved. A denied download is an environment limitation; do not disable the proxy or TLS verification.

Set `AGENVAS_STUDIO_PHOTO_ROOT` to that directory when starting Vite, then open `/e2e/image-studio.html?photos=1&locale=zh`. Add `scenario=portrait` for the portrait fixture. The serve-only media handler recognizes four exact fixture names in an exact synthetic API namespace; it never accepts a browser-supplied file path. Without the directory, photo requests return 404. Neither photo bytes nor the development media handler ship in the client build.

Keep downloaded bytes and screenshots outside tracked source. The source concept image is never used as a background, image slice or result asset. Preserve camera framing with the product's normal contain behavior. Core browser checks remain usable offline without this optional photo set.
