#!/usr/bin/env python3
"""Exercise the deployed Nginx config with a byte-counting upstream on an isolated Docker network."""
from contextlib import closing
from http.client import HTTPConnection
from pathlib import Path
import subprocess
import time
import unittest
import uuid

DEPLOY = Path(__file__).resolve().parent
MIB = 1024 * 1024
UPLOAD_LIMITS = {
    "/api/v1/library/uploads": 50,
    "/api/v1/character-library/media/uploads": 50,
    "/api/v1/media-templates/images": 20,
    "/api/v1/settings/media-styles/11111111-1111-4111-8111-111111111111/thumbnail": 5,
}
PREFIX = (b'--probe\r\nContent-Disposition: form-data; name="file"; filename="synthetic.bin"\r\n'
          b'Content-Type: application/octet-stream\r\n\r\n')
SUFFIX = b"\r\n--probe--\r\n"
UPSTREAM = """
const received = new Map();
require('node:http').createServer((request, response) => {
    const probe = request.headers['x-upload-probe'];
    if (request.method === 'GET') {
        response.end(String(received.get(probe) ?? 0));
        return;
    }
    let bytes = 0;
    request.on('data', chunk => {
        bytes += chunk.length;
        if (probe) received.set(probe, bytes);
    });
    request.on('end', () => response.end(String(bytes)));
}).listen(8080, '0.0.0.0');
"""


def docker(*args, check=True):
    result = subprocess.run(["docker", *args], capture_output=True, text=True, timeout=120)
    if check and result.returncode:
        raise RuntimeError(result.stderr.strip())
    return result.stdout.strip()


def frontend_image(stage):
    return next(line.split()[1] for line in (DEPLOY / "docker/frontend.Dockerfile").read_text().splitlines()
                if line.startswith("FROM ") and line.endswith(f" AS {stage}"))


class NginxUploadTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        network = "agenvas-upload-test-" + uuid.uuid4().hex[:12]
        upstream = network + "-upstream"
        proxy = network + "-proxy"
        docker("network", "create", network)
        cls.addClassCleanup(docker, "network", "rm", network)
        cls.addClassCleanup(docker, "rm", "--force", upstream, check=False)
        docker("run", "--detach", "--name", upstream, "--network", network, "--network-alias", "server",
               frontend_image("build"), "node", "-e", UPSTREAM)
        cls.addClassCleanup(docker, "rm", "--force", proxy, check=False)
        docker("run", "--detach", "--name", proxy, "--network", network, "--publish", "127.0.0.1::8080",
               "--read-only", "--tmpfs", "/tmp:size=64m,mode=1777", "--user", "nginx",
               "--mount", f"type=bind,source={DEPLOY / 'nginx/nginx.conf'},target=/etc/nginx/nginx.conf,readonly",
               "--entrypoint", "nginx", frontend_image("runtime"), "-g", "daemon off;")
        cls.port = int(docker("port", proxy, "8080/tcp").rsplit(":", 1)[1])
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            try:
                if cls.upstream_bytes("ready") == 0:
                    return
            except (OSError, ValueError):
                pass
            time.sleep(0.05)
        raise RuntimeError("Nginx and the fixture upstream did not start\n" + docker("logs", proxy))

    @classmethod
    def upstream_bytes(cls, probe):
        with closing(HTTPConnection("127.0.0.1", cls.port, timeout=5)) as connection:
            connection.request("GET", "/api/v1/upload-probe", headers={"X-Upload-Probe": probe})
            response = connection.getresponse()
            return int(response.read())

    def send_headers(self, connection, path, length, probe=""):
        connection.putrequest("POST", path)
        connection.putheader("Content-Type", "multipart/form-data; boundary=probe")
        connection.putheader("Content-Length", str(length))
        connection.putheader("Expect", "100-continue")
        if probe:
            connection.putheader("X-Upload-Probe", probe)
        connection.endheaders()
        # Check the proxy admission response before sending any large body.
        with connection.sock.makefile("rb") as reply:
            status = int(reply.readline().split()[1])
            while reply.readline() not in (b"\r\n", b""):
                pass
        return status

    def test_proxy_limits_leave_room_for_multipart_framing_only_on_upload_routes(self):
        uploads = {**UPLOAD_LIMITS, "/api/v1/projects/probe/assets/video": 500, "/mcp/uploads": 500}
        for path, mibs in uploads.items():
            with self.subTest(path=path), closing(HTTPConnection("127.0.0.1", self.port, timeout=5)) as connection:
                self.assertEqual(self.send_headers(connection, path, mibs * MIB + len(PREFIX) + len(SUFFIX)), 100)
        for path, mibs in {**{path: limit + 1 for path, limit in uploads.items()},
                           "/api/v1/library/entries": 1, "/mcp": 2}.items():
            with self.subTest(path=path), closing(HTTPConnection("127.0.0.1", self.port, timeout=5)) as connection:
                self.assertEqual(self.send_headers(connection, path, mibs * MIB + 1), 413)

    def test_uploads_reach_upstream_before_the_client_finishes_sending(self):
        payload = bytes(2 * MIB)
        length = len(PREFIX) + len(payload) + len(SUFFIX)
        for path in UPLOAD_LIMITS:
            with self.subTest(path=path), closing(HTTPConnection("127.0.0.1", self.port, timeout=5)) as connection:
                probe = uuid.uuid4().hex
                self.assertEqual(self.send_headers(connection, path, length, probe), 100)
                connection.send(PREFIX + payload[:16 * 1024])
                deadline = time.monotonic() + 3
                while self.upstream_bytes(probe) == 0 and time.monotonic() < deadline:
                    time.sleep(0.05)
                self.assertGreater(self.upstream_bytes(probe), 0, "Nginx buffered the request body")
                connection.send(payload[16 * 1024:] + SUFFIX)
                response = connection.getresponse()
                self.assertEqual(response.status, 200)
                self.assertEqual(int(response.read()), length)


if __name__ == "__main__":
    unittest.main()
