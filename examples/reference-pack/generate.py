"""Regenerate original geometric test images; standard library only, no external assets."""

import hashlib
import json
from pathlib import Path
import struct
import zlib

ROOT = Path(__file__).parent
WIDTH, HEIGHT = 240, 320


def image(name, points=None, clothing=False):
    pixels = bytearray([246, 248, 250] * WIDTH * HEIGHT)

    def dot(x, y, radius=3, color=(42, 72, 95)):
        for py in range(max(0, y - radius), min(HEIGHT, y + radius + 1)):
            for px in range(max(0, x - radius), min(WIDTH, x + radius + 1)):
                if (px - x) ** 2 + (py - y) ** 2 <= radius ** 2:
                    offset = (py * WIDTH + px) * 3
                    pixels[offset:offset + 3] = bytes(color)

    def line(start, end, radius=3):
        dx, dy = end[0] - start[0], end[1] - start[1]
        steps = max(abs(dx), abs(dy), 1)
        for n in range(steps + 1):
            dot(round(start[0] + dx * n / steps), round(start[1] + dy * n / steps), radius)

    if clothing:
        outline = [(95, 65), (75, 75), (38, 135), (65, 150), (84, 120),
                   (76, 255), (164, 255), (156, 120), (175, 150),
                   (202, 135), (165, 75), (145, 65), (120, 88), (95, 65)]
        for start, end in zip(outline, outline[1:]):
            line(start, end)
        line((120, 88), (120, 255), 2)
        for y in (130, 160, 190, 220):
            dot(130, y, 2)
    else:
        head, shoulder, hip, left_hand, right_hand, left_foot, right_foot = points
        dot(*head, radius=15)
        for start, end in [(shoulder, hip), (shoulder, left_hand), (shoulder, right_hand),
                           (hip, left_foot), (hip, right_foot)]:
            line(start, end, 4)
        for p in (shoulder, hip, left_hand, right_hand, left_foot, right_foot):
            dot(*p, radius=6)

    def chunk(kind, data):
        return struct.pack('!I', len(data)) + kind + data + struct.pack('!I', zlib.crc32(kind + data))

    scanlines = b''.join(b'\x00' + pixels[y * WIDTH * 3:(y + 1) * WIDTH * 3] for y in range(HEIGHT))
    data = (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('!2I5B', WIDTH, HEIGHT, 8, 2, 0, 0, 0))
            + chunk(b'IDAT', zlib.compress(scanlines, 9)) + chunk(b'IEND', b''))
    path = f'images/{name}.png'
    (ROOT / path).write_bytes(data)
    return {'path': path, 'sha256': hashlib.sha256(data).hexdigest(),
            'byteSize': len(data), 'contentType': 'image/png'}


poses = {
    'front': [(120, 55), (120, 88), (120, 175), (65, 150), (175, 150), (86, 280), (154, 280)],
    'high': [(120, 75), (120, 110), (120, 178), (65, 150), (175, 150), (94, 255), (146, 255)],
    'low': [(120, 45), (120, 80), (120, 162), (68, 140), (172, 140), (78, 280), (162, 280)],
    'side': [(130, 55), (125, 88), (120, 175), (100, 155), (145, 148), (110, 280), (137, 278)],
    'diag': [(130, 55), (125, 88), (120, 175), (80, 153), (161, 148), (92, 280), (146, 276)],
}
variants = [{'id': f'neutral-{view}', 'label': view.capitalize(), 'view': view,
             **image(f'neutral-{view}', points)} for view, points in poses.items()]
reach = [(120, 55), (120, 88), (120, 175), (55, 65), (190, 45), (86, 280), (154, 280)]
manifest = {'schemaVersion': 1, 'license': 'CC0-1.0', 'entries': [
    {'id': 'synthetic-neutral', 'kind': 'POSE', 'title': 'Synthetic neutral stance',
     'category': 'standing', 'tags': ['geometric', 'test'], 'facets': {}, 'variants': variants},
    {'id': 'synthetic-reach', 'kind': 'POSE', 'title': 'Synthetic reaching stance',
     'category': 'gestures', 'tags': ['geometric', 'test'], 'facets': {},
     'variants': [{'id': 'reach-front', 'label': 'Front', 'view': 'front', **image('reach-front', reach)}]},
    {'id': 'synthetic-coat', 'kind': 'COSTUME', 'title': 'Synthetic geometric coat',
     'category': 'winter', 'tags': ['geometric', 'test'], 'facets': {'season': 'winter'},
     'variants': [{'id': 'coat-front', 'label': 'Front', 'view': 'front', **image('coat-front', clothing=True)}]},
]}
(ROOT / 'reference-pack.json').write_text(json.dumps(manifest, indent=2) + '\n')
