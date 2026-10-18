#!/usr/bin/env python3
"""Fixed server-side deployment entry point; accepts only a data-only release bundle."""
import contextlib
import fcntl
import grp
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import signal
import subprocess
import sys
import tarfile
import tempfile
import time
import urllib.error
import urllib.request

MAX_BUNDLE_BYTES = 256 * 1024 * 1024
SHA = re.compile(r"[0-9a-f]{40}\Z")
CONFIG = {
    "scheduler": {"service": "mjsec-scheduler", "artifact": "/opt/msgctf-scheduler/scheduler.jar", "env": "/etc/default/mjsec-scheduler", "marker": "/opt/msgctf-scheduler/release.json", "group": "root", "mode": 0o644},
    "runtime": {"service": "secure-provisioner", "artifact": "/opt/secure-provisioner/secure-provisioner", "env": "/etc/secure-provisioner/provisioner.env", "marker": "/opt/secure-provisioner/release.json", "group": "provisioner", "mode": 0o755},
}
BRIDGE_SERVICE = "mjsec-scheduler-bridge"
BRIDGE_PORT = 18080
UPSTREAM = "/etc/nginx/snippets/msgctf-scheduler-upstream.conf"
PRIMARY_PROXY = b"proxy_pass http://127.0.0.1:8080;\n"

class DeploymentError(Exception):
    pass

def environment_values(raw):
    if len(raw) > 65536 or b"\0" in raw:
        raise DeploymentError("invalid environment")
    result = {}
    for line in raw.decode("utf-8").splitlines():
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        match = re.fullmatch(r"([A-Z][A-Z0-9_]*)=(.*)", line)
        if not match or match[1] in result or not match[1].startswith(("SCHEDULER_", "SPRING_", "SERVER_", "PROVISIONER_")):
            raise DeploymentError("unsupported environment entry")
        values = shlex.split(match[2], comments=False)
        if len(values) > 1:
            raise DeploymentError("unquoted environment value")
        result[match[1]] = values[0] if values else ""
    return result

def read_bundle(stream, component):
    names = {"release.json", "artifact", "app.env"}
    if component == "runtime":
        names.add("service-token")
    files = {}
    total = 0
    with tarfile.open(fileobj=stream, mode="r|gz") as archive:
        for member in archive:
            if member.name not in names or member.name in files or not member.isfile():
                raise DeploymentError("unexpected bundle entry")
            total += member.size
            if total > MAX_BUNDLE_BYTES or member.size < 0:
                raise DeploymentError("bundle too large")
            if member.name != "artifact" and member.size > 65536:
                raise DeploymentError("configuration too large")
            with archive.extractfile(member) as entry:
                files[member.name] = entry.read(member.size + 1)
            if len(files[member.name]) != member.size:
                raise DeploymentError("truncated bundle")
    if set(files) != names or not files["artifact"]:
        raise DeploymentError("missing bundle entry")
    manifest = json.loads(files["release.json"])
    if manifest.get("component") != component or not SHA.fullmatch(manifest.get("source_sha", "")):
        raise DeploymentError("invalid release identity")
    for name in names - {"release.json"}:
        if manifest.get("checksums", {}).get(name) != hashlib.sha256(files[name]).hexdigest():
            raise DeploymentError("bundle checksum mismatch")
    values = environment_values(files["app.env"])
    if component == "scheduler":
        if not re.fullmatch(r"[A-Za-z0-9_-]{32,256}", values.get("SCHEDULER_API_TOKEN", "")):
            raise DeploymentError("scheduler API token is missing")
        if values.get("SPRING_DOCKER_COMPOSE_ENABLED") != "false" or values.get("SPRING_PROFILES_ACTIVE") != "dev":
            raise DeploymentError("server profile must use managed dependencies")
        required = ("SPRING_DATASOURCE_URL", "SPRING_DATASOURCE_USERNAME", "SPRING_DATASOURCE_PASSWORD", "SPRING_DATA_REDIS_HOST", "SPRING_DATA_REDIS_PORT")
        if not all(values.get(key) for key in required):
            raise DeploymentError("database and Redis configuration is required")
    else:
        if not re.fullmatch(rb"[A-Za-z0-9_-]{43,128}\n?", files["service-token"]):
            raise DeploymentError("invalid runtime service token")
        if values.get("PROVISIONER_SERVICE_TOKEN_FILE") != "/etc/secure-provisioner/service-token" or values.get("PROVISIONER_SERVICE_TOKEN"):
            raise DeploymentError("runtime token file contract mismatch")
        if values.get("PROVISIONER_IMAGE_POLICIES") != "/etc/secure-provisioner/image-policies.json":
            raise DeploymentError("runtime image policy path changed")
    return manifest, files, values

def atomic_write(path, data, mode, gid=0):
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.is_symlink():
        raise DeploymentError("deployment target must not be a symlink")
    descriptor, temporary = tempfile.mkstemp(prefix=".deploy-", dir=path.parent)
    try:
        with os.fdopen(descriptor, "wb") as file:
            file.write(data)
            file.flush()
            os.fsync(file.fileno())
            os.fchmod(file.fileno(), mode)
            if os.geteuid() == 0:
                os.fchown(file.fileno(), 0, gid)
        os.replace(temporary, path)
    finally:
        with contextlib.suppress(FileNotFoundError):
            os.unlink(temporary)


def proxy_workers(master):
    children = Path(f"/proc/{master}/task/{master}/children")
    workers = set()
    for pid in children.read_text().split():
        try:
            if Path(f"/proc/{pid}/cmdline").read_bytes().startswith(b"nginx: worker process"):
                workers.add(int(pid))
        except FileNotFoundError:
            pass
    return workers


def wait_proxy_drain(workers, timeout=15):
    deadline = time.monotonic() + timeout
    while any(Path(f"/proc/{pid}").exists() for pid in workers):
        if time.monotonic() >= deadline:
            raise DeploymentError("previous proxy workers did not drain")
        time.sleep(0.05)

class SystemService:
    def reload(self):
        subprocess.run(["systemctl", "daemon-reload"], check=True, capture_output=True, timeout=30)

    def restart(self, service):
        subprocess.run(["systemctl", "restart", service], check=True, capture_output=True, timeout=120)

    def active(self, service):
        return subprocess.run(["systemctl", "is-active", "--quiet", service], capture_output=True, timeout=10).returncode == 0

    def request(self, path, token=None):
        return self._request(path, token, 8080)

    def bridge_request(self, path, token=None):
        return self._request(path, token, BRIDGE_PORT)

    def _request(self, path, token, port):
        class NoRedirect(urllib.request.HTTPRedirectHandler):
            def redirect_request(self, *args):
                return None
        headers = {"Accept": "application/json"}
        if token:
            headers["Authorization"] = "Bearer " + token
        request = urllib.request.Request(f"http://127.0.0.1:{port}" + path, headers=headers)
        try:
            response = urllib.request.build_opener(NoRedirect()).open(request, timeout=5)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            raw = response.read(1024 * 1024)
            try:
                value = json.loads(raw)
            except (ValueError, UnicodeError):
                value = None
            return response.code, value

    def reload_proxy(self):
        result = subprocess.run(["systemctl", "show", "nginx", "--property=MainPID", "--value"],
            check=True, capture_output=True, text=True, timeout=10)
        master = result.stdout.strip()
        if not master.isdecimal() or int(master) <= 1:
            raise DeploymentError("nginx master is not active")
        workers = proxy_workers(int(master))
        if not workers:
            raise DeploymentError("nginx has no verified serving workers")
        subprocess.run(["/usr/sbin/nginx", "-t"], check=True, capture_output=True, timeout=10)
        subprocess.run(["systemctl", "reload", "nginx"], check=True, capture_output=True, timeout=20)
        wait_proxy_drain(workers)

    def start_bridge(self):
        if self.active(BRIDGE_SERVICE):
            raise DeploymentError("an existing bridge requires administrator verification")
        subprocess.run(["systemctl", "reset-failed", BRIDGE_SERVICE], capture_output=True, timeout=10)
        result = subprocess.run(["systemctl", "show", "mjsec-scheduler", "--property=User",
            "--property=Group", "--property=WorkingDirectory"], check=True, capture_output=True, text=True, timeout=10)
        identity = dict(line.split("=", 1) for line in result.stdout.splitlines() if "=" in line)
        command = ["systemd-run", "--quiet", "--collect", "--unit=" + BRIDGE_SERVICE,
            "--property=Restart=no", "--property=Type=exec", "--property=MemoryMax=1G", "--property=CPUQuota=100%",
            "--property=EnvironmentFile=/etc/default/mjsec-scheduler-bridge"]
        for key in ("User", "Group", "WorkingDirectory"):
            if identity.get(key):
                command.append("--property=" + key + "=" + identity[key])
        command.extend(["/opt/temurin-24/bin/java", "-Xmx512m", "-jar", "/opt/msgctf-scheduler/bridge.jar",
            "--server.address=127.0.0.1", f"--server.port={BRIDGE_PORT}", f"--management.server.port={BRIDGE_PORT}",
            "--scheduler.operation.enabled=false", "--scheduler.cleanup.enabled=false",
            "--spring.jpa.hibernate.ddl-auto=validate"])
        subprocess.run(command, check=True, capture_output=True, timeout=30)

    def stop_bridge(self):
        subprocess.run(["systemctl", "stop", BRIDGE_SERVICE], capture_output=True, timeout=90)
        if self.active(BRIDGE_SERVICE):
            raise DeploymentError("bridge shutdown requires administrator verification")

    def bridge_workers_disabled(self):
        result = subprocess.run(["systemctl", "show", BRIDGE_SERVICE, "--property=InvocationID", "--value"],
            check=True, capture_output=True, text=True, timeout=10)
        invocation = result.stdout.strip()
        if not re.fullmatch(r"[0-9a-f]{32}", invocation):
            return False
        result = subprocess.run(["journalctl", "_SYSTEMD_INVOCATION_ID=" + invocation, "--no-pager", "-o", "cat", "-n", "300"],
            check=True, capture_output=True, text=True, timeout=10)
        return all(message in result.stdout for message in (
            "operation worker disabled: REQUESTED instances are not progressed",
            "cleanup worker disabled: CLEANUP_PENDING instances are not reaped"))


def prepare_scheduler_proxy(root, service):
    """Convert only the two existing fixed scheduler locations, with an immediate config rollback."""
    path = lambda name: root / name.lstrip("/")
    upstream = path(UPSTREAM)
    sources = [path("/etc/nginx/sites-available/sentinel-scheduler-ip"),
        path("/etc/nginx/snippets/msgctf-scheduler-api.conf")]
    include = "include " + UPSTREAM + ";"
    entries = []
    for source in sources:
        if source.is_symlink() or not source.is_file():
            raise DeploymentError("scheduler HTTPS configuration must be prepared by an administrator")
        original = source.read_bytes()
        if b"proxy_pass http://127.0.0.1:8080;" not in original and include.encode() not in original:
            raise DeploymentError("unrecognized scheduler proxy configuration")
        entries.append((source, original, original.replace(b"proxy_pass http://127.0.0.1:8080;", include.encode())))
    if upstream.is_symlink() or upstream.exists() and upstream.read_bytes() != PRIMARY_PROXY:
        raise DeploymentError("scheduler proxy is not on the verified primary")
    if upstream.exists() and all(old == new for _, old, new in entries):
        return
    previous = upstream.read_bytes() if upstream.exists() else None
    try:
        atomic_write(upstream, PRIMARY_PROXY, 0o644)
        for target, _, updated in entries:
            atomic_write(target, updated, 0o644)
        service.reload_proxy()
    except BaseException:
        for target, original, _ in entries:
            atomic_write(target, original, 0o644)
        if previous is None:
            upstream.unlink(missing_ok=True)
        else:
            atomic_write(upstream, previous, 0o644)
        service.reload_proxy()
        raise


class SchedulerBridge:
    """Keep the previous API available while exactly one primary owns background workers."""
    def __init__(self, root, service, attempts, delay):
        self.root, self.service, self.attempts, self.delay = root, service, attempts, delay
        self.routed = False
        self.started = False
        self.artifact = root / "opt/msgctf-scheduler/bridge.jar"
        self.env = root / "etc/default/mjsec-scheduler-bridge"

    def prepare(self):
        prepare_scheduler_proxy(self.root, self.service)
        for target in (self.artifact, self.env):
            if target.exists() or target.is_symlink():
                raise DeploymentError("previous bridge files require administrator verification")
        if self.service.active(BRIDGE_SERVICE):
            raise DeploymentError("an existing bridge requires administrator verification")
        config = CONFIG["scheduler"]
        old_env = (self.root / config["env"].lstrip("/")).read_bytes()
        values = environment_values(old_env)
        try:
            atomic_write(self.artifact, (self.root / config["artifact"].lstrip("/")).read_bytes(), 0o644)
            atomic_write(self.env, old_env, 0o600)
            self.service.start_bridge()
            self.started = True
            parent = self.service
            class BridgeProbe:
                def active(self, _): return parent.active(BRIDGE_SERVICE)
                def request(self, path, token=None): return parent.bridge_request(path, token)
            verify("scheduler", config, values, values.get("SCHEDULER_API_TOKEN"), BridgeProbe(), self.attempts, self.delay)
            if not self.service.bridge_workers_disabled():
                raise DeploymentError("bridge background workers were not confirmed disabled")
            self.route(BRIDGE_PORT)
        except BaseException:
            if self.routed:
                self.route(8080)
            self.close()
            if not self.started and not self.service.active(BRIDGE_SERVICE):
                self.artifact.unlink(missing_ok=True)
                self.env.unlink(missing_ok=True)
            raise

    def route(self, port):
        if port not in (8080, BRIDGE_PORT):
            raise DeploymentError("unexpected upstream port")
        # A failed reload can be ambiguous. Retain the serving bridge until a primary reload succeeds.
        self.routed = True
        atomic_write(self.root / UPSTREAM.lstrip("/"), f"proxy_pass http://127.0.0.1:{port};\n".encode(), 0o644)
        self.service.reload_proxy()
        self.routed = port != 8080

    def close(self):
        if self.started and not self.routed:
            self.service.stop_bridge()
            self.artifact.unlink(missing_ok=True)
            self.env.unlink(missing_ok=True)
            self.started = False

def verify(component, config, values, token, service, attempts=45, delay=2):
    for attempt in range(attempts):
        try:
            if service.active(config["service"]):
                if component == "scheduler":
                    code, health = service.request("/actuator/health")
                    code2, page = service.request("/api/instances?limit=1", token)
                    valid = code == 200 and isinstance(health, dict) and health.get("status") == "UP" and code2 == 200 and isinstance(page, dict) and page.get("code") == "SUCCESS" and isinstance(page.get("data"), dict) and isinstance(page["data"].get("items"), list) and "next_cursor" in page["data"]
                    path = "/api/instances?limit=1"
                else:
                    path = "/internal/v1/operations/ci-deployment-healthcheck"
                    code, body = service.request(path, token)
                    valid = code == 404 and isinstance(body, dict) and body.get("error", {}).get("code") == "OPERATION_NOT_FOUND"
                if valid and service.request(path)[0] == 401 and service.request(path, "invalid-deployment-credential")[0] == 401:
                    return
        except (OSError, ValueError, subprocess.SubprocessError):
            pass
        if attempt + 1 < attempts:
            time.sleep(delay)
    raise DeploymentError("service verification failed")

def deploy(component, stream, root=Path("/"), service=None, attempts=45, delay=2):
    if component not in CONFIG:
        raise DeploymentError("unknown component")
    service = service or SystemService()
    config = CONFIG[component]
    path = lambda name: root / name.lstrip("/")
    state = path("/var/lib/msgctf-deploy/" + component)
    state.mkdir(parents=True, mode=0o700, exist_ok=True)
    with (state / "deploy.lock").open("a") as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as error:
            raise DeploymentError("deployment already running") from error
        manifest, files, values = read_bundle(stream, component)
        artifact, env, marker = (path(config[key]) for key in ("artifact", "env", "marker"))
        artifact.parent.mkdir(parents=True, exist_ok=True)
        if artifact.parent.is_symlink():
            raise DeploymentError("artifact directory must not be a symlink")
        artifact.parent.chmod(0o755)
        entries = [(artifact, files["artifact"], config["mode"], 0), (env, files["app.env"], 0o600, 0)]
        token = values.get("SCHEDULER_API_TOKEN")
        if component == "runtime":
            gid = grp.getgrnam(config["group"]).gr_gid if root == Path("/") else os.getgid()
            entries[1] = (env, files["app.env"], 0o640, gid)
            entries.append((path("/etc/secure-provisioner/service-token"), files["service-token"], 0o640, gid))
            token = files["service-token"].decode().strip()
        else:
            dropin = b"[Service]\nExecStart=\nExecStart=/opt/temurin-24/bin/java -jar /opt/msgctf-scheduler/scheduler.jar\n"
            entries.append((path("/etc/systemd/system/mjsec-scheduler.service.d/60-ci-artifact.conf"), dropin, 0o644, 0))
        entries.append((marker, (json.dumps({"source_commit": manifest["source_sha"], "artifact_sha256": manifest["checksums"]["artifact"], "component": component, "deployed_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())}) + "\n").encode(), 0o644, 0))
        with tempfile.TemporaryDirectory(prefix="transaction-", dir=state) as temporary:
            backup = Path(temporary)
            snapshots = []
            for index, (target, _, _, _) in enumerate(entries):
                if target.is_symlink() or (target.exists() and not target.is_file()):
                    raise DeploymentError("unsafe deployment target")
                saved = backup / str(index)
                stat = target.stat() if target.exists() else None
                if stat:
                    shutil.copy2(target, saved)
                snapshots.append((target, saved, stat))
            bridge = SchedulerBridge(root, service, attempts, delay) if component == "scheduler" and artifact.exists() and env.exists() else None
            if bridge:
                bridge.prepare()  # No primary files or process are changed unless the old API is ready on the bridge.
            try:
                for target, data, mode, gid in entries:
                    atomic_write(target, data, mode, gid)
                service.reload()
                service.restart(config["service"])
                verify(component, config, values, token, service, attempts, delay)
                if bridge:
                    bridge.route(8080)
            except BaseException:
                for target, saved, stat in snapshots:
                    if stat:
                        atomic_write(target, saved.read_bytes(), stat.st_mode & 0o777, stat.st_gid)
                    else:
                        target.unlink(missing_ok=True)
                service.reload()
                service.restart(config["service"])
                old_values = environment_values(env.read_bytes())
                old_token = old_values.get("SCHEDULER_API_TOKEN") if component == "scheduler" else path("/etc/secure-provisioner/service-token").read_text().strip()
                verify(component, config, old_values, old_token, service, attempts, delay)
                if bridge:
                    bridge.route(8080)
                print("ROLLBACK_RESULT=" + json.dumps({"component": component, "status": "verified"}))
                raise DeploymentError("deployment failed; previous service verified") from None
            finally:
                if bridge:
                    bridge.close()
        return {"component": component, "source_commit": manifest["source_sha"], "artifact_sha256": manifest["checksums"]["artifact"], "status": "verified", "authentication_verified": True,
            "deployment_strategy": "verified_api_bridge" if bridge else "initial_start" if component == "scheduler" else "runtime_restart"}

def main():
    os.umask(0o077)
    if len(sys.argv) != 2 or sys.argv[1] not in CONFIG or os.geteuid() != 0:
        raise SystemExit(2)
    def interrupted(*_):
        raise DeploymentError("deployment interrupted")
    for number in (signal.SIGTERM, signal.SIGHUP, signal.SIGINT):
        signal.signal(number, interrupted)
    try:
        result = deploy(sys.argv[1], sys.stdin.buffer)
        print("DEPLOYMENT_RESULT=" + json.dumps(result))
    except Exception as error:
        print("Deployment did not pass verification (" + type(error).__name__ + ")", file=sys.stderr)
        raise SystemExit(1) from None

if __name__ == "__main__":
    main()
