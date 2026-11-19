"""Download checksum-pinned, openly licensed photos for optional local UI QA only."""

import hashlib
from pathlib import Path
import sys
import urllib.request

PHOTOS = [
    (
        "lake-landscape.jpg",
        "https://raw.githubusercontent.com/NotCoco/shape_vectorizer/main/docs/examples/demo_input.jpg",
        "9b69293a557c3ba31d8bf7af7fc2e8848d2f51f593f86e8c4cc60b482c35a5a5",
    ),
    (
        "rocket.jpg",
        "https://raw.githubusercontent.com/scikit-image/scikit-image/v0.24.0/skimage/data/rocket.jpg",
        "c2dd0de7c538df8d111e479619b129464d0269d0ae5fd18ca91d33a7fdfea95c",
    ),
    (
        "coffee.png",
        "https://raw.githubusercontent.com/scikit-image/scikit-image/v0.24.0/skimage/data/coffee.png",
        "cc02f8ca188b167c775a7101b5d767d1e71792cf762c33d6fa15a4599b5a8de7",
    ),
    (
        "eagle.png",
        "https://gitlab.com/scikit-image/data/-/raw/1e4f62ac31ba4553d176d4473a5967ad1b076d62/eagle.png",
        "928f1bbe7403b533265f56db3a6b07c835dfa8e2513f5c5075ca2f1960f6179e",
    ),
]


def main():
    destination = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("node_modules/.cache/image-studio-photos")
    destination.mkdir(parents=True, exist_ok=True, mode=0o700)
    for name, url, expected in PHOTOS:
        target = destination / name
        if target.exists():
            if hashlib.sha256(target.read_bytes()).hexdigest() == expected:
                continue
            raise ValueError(f"Existing file has different fixture bytes: {name}")
        with urllib.request.urlopen(url, timeout=30) as response:
            data = response.read(4_000_001)
        if len(data) > 4_000_000 or hashlib.sha256(data).hexdigest() != expected:
            raise ValueError(f"Unexpected fixture bytes: {name}")
        target.write_bytes(data)
        target.chmod(0o600)
    print(f"Licensed photo fixtures ready: {destination.resolve()}")


if __name__ == "__main__":
    main()
