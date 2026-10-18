"""Exercise real Nginx reloads and sockets using isolated ports, fake application responses and no system units."""
import http.server
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request

from release import BRIDGE_PORT, UPSTREAM, DeploymentError, SystemService, deploy, proxy_workers, wait_proxy_drain
import test_release as fixtures
from test_release import Service, TOKEN, bundle


def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


class ProxyService(Service):
    def __init__(self, root, fail=False, rollback_broken=False):
        super().__init__(root, "scheduler", fail)
        self.rollback_broken = rollback_broken
        ports = set()
        while len(ports) < 3: ports.add(free_port())
        primary, bridge, self.proxy_port = sorted(ports)
        self.ports = {8080: primary, BRIDGE_PORT: bridge}
        self.servers = {}
        self.process = None
        self.prefix = root / "nginx-test"
        self.prefix.mkdir()
        self.start_application(8080)

    def start_application(self, port):
        service = self
        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                current = (service.root / "opt/msgctf-scheduler/scheduler.jar").read_bytes()
                broken = port == 8080 and (service.fail and current == b"new artifact" or service.rollback_broken and service.restarts > 0)
                code, body = (503, {}) if broken else (200, {"status":"UP"}) if self.path == "/actuator/health" else (
                    (200, {"code":"SUCCESS","data":{"items":[],"next_cursor":None}})
                    if self.headers.get("Authorization") == "Bearer " + TOKEN else (401, {}))
                raw = json.dumps(body).encode()
                self.send_response(code); self.send_header("Content-Type","application/json")
                self.send_header("Content-Length",str(len(raw))); self.end_headers(); self.wfile.write(raw)
            def log_message(self, *_): pass
        server = http.server.ThreadingHTTPServer(("127.0.0.1", self.ports[port]), Handler)
        thread = threading.Thread(target=server.serve_forever, kwargs={"poll_interval":0.02}, daemon=True)
        thread.start()
        self.servers[port] = (server, thread)

    def stop_application(self, port):
        if port in self.servers:
            server, thread = self.servers.pop(port)
            server.shutdown(); server.server_close(); thread.join(timeout=2)

    def active(self, unit):
        return (BRIDGE_PORT if unit == "mjsec-scheduler-bridge" else 8080) in self.servers

    def request(self, path, token=None):
        return SystemService()._request(path, token, self.ports[8080])

    def bridge_request(self, path, token=None):
        return SystemService()._request(path, token, self.ports[BRIDGE_PORT])

    def start_bridge(self):
        super().start_bridge(); self.start_application(BRIDGE_PORT)

    def stop_bridge(self):
        self.stop_application(BRIDGE_PORT); super().stop_bridge()

    def restart(self, unit):
        super().restart(unit)
        self.stop_application(8080)
        time.sleep(0.4)  # A real absent upstream socket, bounded and isolated from production.
        self.start_application(8080)

    def reload_proxy(self):
        super().reload_proxy()
        upstream = self.root / UPSTREAM.lstrip("/")
        port = BRIDGE_PORT if upstream.exists() and str(BRIDGE_PORT) in upstream.read_text() else 8080
        config = self.prefix / "nginx.conf"
        config.write_text(f'''daemon off;
worker_processes 1;
pid {self.prefix}/nginx.pid;
error_log {self.prefix}/error.log notice;
events {{ worker_connections 128; }}
http {{
  access_log off;
  client_body_temp_path {self.prefix}/body;
  proxy_temp_path {self.prefix}/proxy;
  fastcgi_temp_path {self.prefix}/fastcgi;
  uwsgi_temp_path {self.prefix}/uwsgi;
  scgi_temp_path {self.prefix}/scgi;
  server {{ listen 127.0.0.1:{self.proxy_port}; location / {{
    proxy_pass http://127.0.0.1:{self.ports[port]};
    proxy_read_timeout 2s;
  }} }}
}}
''')
        command = [os.environ.get("NGINX_BINARY") or shutil.which("nginx"), "-e", str(self.prefix / "error.log"), "-p", str(self.prefix), "-c", str(config)]
        validation = subprocess.run(command + ["-t"], capture_output=True, text=True, timeout=5)
        if validation.returncode: raise AssertionError(validation.stderr)
        if self.process is None:
            self.process = subprocess.Popen(command, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            deadline = time.monotonic() + 5
            while time.monotonic() < deadline:
                try:
                    with socket.create_connection(("127.0.0.1", self.proxy_port), timeout=.2): break
                except OSError: time.sleep(.02)
            else: raise AssertionError("Isolated proxy did not start")
        else:
            workers = proxy_workers(self.process.pid)
            if not workers: raise AssertionError("Isolated proxy has no workers")
            subprocess.run(command + ["-s","reload"], check=True, capture_output=True, timeout=5)
            wait_proxy_drain(workers, timeout=5)

    def cleanup(self):
        if self.process:
            self.process.terminate()
            try: self.process.wait(timeout=5)
            except subprocess.TimeoutExpired: self.process.kill(); self.process.wait(timeout=5)
        for port in list(self.servers): self.stop_application(port)


@unittest.skipUnless(os.environ.get("NGINX_BINARY") or shutil.which("nginx"), "Nginx is required for socket integration")
class ProxyAvailabilityTests(unittest.TestCase):
    def exercise(self, fail=False, rollback_broken=False):
        with tempfile.TemporaryDirectory(prefix="scheduler-proxy-") as name:
            root = Path(name)
            fixtures.DeploymentTests().fixture(root,"scheduler")
            service = ProxyService(root, fail, rollback_broken)
            self.addCleanup(service.cleanup)
            results = []; stop = threading.Event()
            def observe():
                while not stop.is_set():
                    if service.process:
                        request = urllib.request.Request(f"http://127.0.0.1:{service.proxy_port}/api/instances?limit=1", headers={"Authorization":"Bearer " + TOKEN})
                        try:
                            with urllib.request.urlopen(request,timeout=2) as response:
                                results.append((response.code,json.load(response).get("code")))
                        except Exception as error: results.append((getattr(error,"code",0),type(error).__name__))
                    stop.wait(.01)
            observer = threading.Thread(target=observe,daemon=True); observer.start()
            try:
                if fail or rollback_broken:
                    with self.assertRaises(DeploymentError): deploy("scheduler",bundle(),root,service,1,0)
                else:
                    result=deploy("scheduler",bundle(),root,service,1,0)
                    self.assertEqual(result['deployment_strategy'], 'verified_api_bridge')
                time.sleep(.1)
            finally:
                stop.set(); observer.join(timeout=3)
            self.assertGreater(len(results),15)
            self.assertEqual(set(results),{(200,"SUCCESS")})
            self.assertEqual(service.bridge,rollback_broken)
            service.cleanup()

    def test_continuous_authenticated_reads_survive_primary_socket_disappearance(self): self.exercise()
    def test_continuous_authenticated_reads_survive_verified_rollback(self): self.exercise(fail=True)
    def test_unverified_rollback_keeps_last_known_good_api_serving(self): self.exercise(rollback_broken=True)


if __name__ == "__main__": unittest.main()
