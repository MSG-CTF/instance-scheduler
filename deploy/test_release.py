import hashlib
import io
import json
import os
import subprocess
from pathlib import Path
import tarfile
import tempfile
import unittest
from unittest.mock import patch

from release import BRIDGE_PORT, BRIDGE_SERVICE, CONFIG, UPSTREAM, DeploymentError, SystemService, deploy, read_bundle

TOKEN = "a" * 48
ENV = ("SCHEDULER_API_TOKEN=" + TOKEN + "\nSPRING_PROFILES_ACTIVE=dev\nSPRING_DOCKER_COMPOSE_ENABLED=false\nSPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1/db\nSPRING_DATASOURCE_USERNAME=test\nSPRING_DATASOURCE_PASSWORD=test\nSPRING_DATA_REDIS_HOST=127.0.0.1\nSPRING_DATA_REDIS_PORT=6379\n").encode()
RUNTIME_ENV = b"PROVISIONER_SERVICE_TOKEN_FILE=/etc/secure-provisioner/service-token\nPROVISIONER_IMAGE_POLICIES=/etc/secure-provisioner/image-policies.json\n"

def bundle(component="scheduler", extra=None, corrupt=False, artifact=b"new artifact", env=None):
    files = {"artifact": artifact, "app.env": env or (ENV if component == "scheduler" else RUNTIME_ENV)}
    if component == "runtime":
        files["service-token"] = TOKEN.encode()
    manifest = {"component": component, "source_sha": "b" * 40, "checksums": {k: hashlib.sha256(v).hexdigest() for k, v in files.items()}}
    if corrupt:
        manifest["checksums"]["artifact"] = "0" * 64
    files["release.json"] = json.dumps(manifest).encode()
    buffer = io.BytesIO()
    with tarfile.open(fileobj=buffer, mode="w:gz") as archive:
        for name, data in files.items():
            info = tarfile.TarInfo(name)
            info.size = len(data)
            archive.addfile(info, io.BytesIO(data))
        if extra:
            archive.addfile(extra)
    buffer.seek(0)
    return buffer

class Service:
    def __init__(self, root, component, fail=False):
        self.root, self.component, self.fail = root, component, fail
        self.restarts = 0
        self.bridge = False
        self.steps = []
    def reload_proxy(self):
        upstream = self.root / UPSTREAM.lstrip("/")
        self.steps.append(("proxy", upstream.read_text() if upstream.exists() else "proxy_pass http://127.0.0.1:8080;\n"))
    def start_bridge(self):
        self.bridge = True
        self.steps.append(("bridge_start", None))
    def stop_bridge(self):
        self.bridge = False
        self.steps.append(("bridge_stop", None))
    def bridge_workers_disabled(self):
        return True
    def bridge_request(self, path, token=None):
        if path == "/actuator/health": return 200, {"status":"UP"}
        old = (self.root / "etc/default/mjsec-scheduler-bridge").read_bytes()
        if token != TOKEN or TOKEN.encode() not in old: return 401, {}
        return 200, {"code":"SUCCESS","data":{"items":[],"next_cursor":None}}
    def reload(self):
        pass
    def restart(self, service):
        self.restarts += 1
        self.steps.append(("restart", service))
    def active(self, service):
        return self.bridge if service == BRIDGE_SERVICE else True
    def request(self, path, token=None):
        artifact = self.root / CONFIG[self.component]["artifact"].lstrip("/")
        if self.fail and artifact.read_bytes() == b"new artifact":
            return 503, {}
        if path == "/actuator/health":
            return 200, {"status": "UP"}
        if token != TOKEN:
            return 401, {}
        if self.component == "runtime":
            return 404, {"error": {"code": "OPERATION_NOT_FOUND"}}
        return 200, {"code": "SUCCESS", "data": {"items": [], "next_cursor": None}}

class DeploymentTests(unittest.TestCase):
    def fixture(self, root, component):
        for key, value in (("artifact", b"old artifact"), ("env", ENV if component == "scheduler" else RUNTIME_ENV), ("marker", b'{"source_commit":"old"}')):
            target = root / CONFIG[component][key].lstrip("/")
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(value)
            target.chmod(0o600)
        if component == "runtime":
            (root / "etc/secure-provisioner/service-token").write_text(TOKEN)
        else:
            for file in ("etc/nginx/sites-available/sentinel-scheduler-ip", "etc/nginx/snippets/msgctf-scheduler-api.conf"):
                target = root / file
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text("location / { proxy_pass http://127.0.0.1:8080; }\n")

    def test_success_updates_artifact_metadata_and_authentication(self):
        for component in CONFIG:
            with self.subTest(component=component), tempfile.TemporaryDirectory() as name:
                root = Path(name)
                self.fixture(root, component)
                service = Service(root, component)
                result = deploy(component, bundle(component), root, service, 1, 0)
                self.assertEqual(result["status"], "verified")
                self.assertEqual(service.restarts, 1)
                self.assertEqual((root / CONFIG[component]["artifact"].lstrip("/")).read_bytes(), b"new artifact")
                env = root / CONFIG[component]["env"].lstrip("/")
                self.assertEqual(env.stat().st_mode & 0o777, 0o640 if component == "runtime" else 0o600)

    def test_failed_health_restores_artifact_environment_marker_and_dropin(self):
        for component in CONFIG:
            with self.subTest(component=component), tempfile.TemporaryDirectory() as name:
                root = Path(name)
                self.fixture(root, component)
                before = {p: p.read_bytes() for p in root.rglob("*") if p.is_file()}
                service = Service(root, component, fail=True)
                with self.assertRaises(DeploymentError):
                    deploy(component, bundle(component), root, service, 1, 0)
                self.assertEqual(service.restarts, 2)
                for p, data in before.items():
                    if "/nginx/" not in str(p): self.assertEqual(p.read_bytes(), data)
                self.assertFalse((root / "etc/systemd/system/mjsec-scheduler.service.d/60-ci-artifact.conf").exists())

    def test_corrupt_artifact_changes_no_service_files(self):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            self.fixture(root, "scheduler")
            service = Service(root, "scheduler")
            with self.assertRaises(DeploymentError):
                deploy("scheduler", bundle(corrupt=True), root, service, 1, 0)
            self.assertEqual(service.restarts, 0)
            self.assertEqual((root / "opt/msgctf-scheduler/scheduler.jar").read_bytes(), b"old artifact")

    def test_service_can_read_artifact_directory_with_private_umask(self):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            self.fixture(root, "scheduler")
            directory = root / "opt/msgctf-scheduler"
            directory.chmod(0o700)
            previous = os.umask(0o077)
            try:
                deploy("scheduler", bundle(), root, Service(root, "scheduler"), 1, 0)
            finally:
                os.umask(previous)
            self.assertEqual(directory.stat().st_mode & 0o777, 0o755)

    def test_rejects_traversal_symlink_and_duplicate_entries(self):
        for name, kind in (("../escape", tarfile.REGTYPE), ("app.env", tarfile.SYMTYPE), ("artifact", tarfile.REGTYPE)):
            with self.subTest(name=name, kind=kind):
                entry = tarfile.TarInfo(name)
                entry.type = kind
                entry.linkname = "/etc/passwd"
                with self.assertRaises(DeploymentError):
                    read_bundle(bundle(extra=entry), "scheduler")

    def test_rejects_component_and_unsafe_environment(self):
        with self.assertRaises(DeploymentError):
            read_bundle(bundle(), "runtime")
        for suffix in (b"LD_PRELOAD=/tmp/code\n", b"SCHEDULER_API_TOKEN=duplicate\n", b"not an env file\n"):
            with self.assertRaises(DeploymentError):
                read_bundle(bundle(env=ENV + suffix), "scheduler")

    def test_html_or_unprotected_api_fails_verification(self):
        for insecure in (False, True):
            with tempfile.TemporaryDirectory() as name:
                root = Path(name)
                self.fixture(root, "scheduler")
                service = Service(root, "scheduler")
                original = service.request
                def request(path, token=None):
                    current = (root / "opt/msgctf-scheduler/scheduler.jar").read_bytes()
                    if current == b"new artifact":
                        if insecure and path == "/actuator/health":
                            return 200, {"status": "UP"}
                        return (200, {"code": "SUCCESS", "data": {"items": [], "next_cursor": None}}) if insecure else (200, None)
                    return original(path, token)
                service.request = request
                with self.assertRaises(DeploymentError):
                    deploy("scheduler", bundle(), root, service, 1, 0)
                self.assertEqual(service.restarts, 2)

    def test_lock_prevents_parallel_deploy_without_restart(self):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            self.fixture(root, "scheduler")
            service = Service(root, "scheduler")
            with patch("release.fcntl.flock", side_effect=BlockingIOError):
                with self.assertRaises(DeploymentError):
                    deploy("scheduler", bundle(), root, service, 1, 0)
            self.assertEqual(service.restarts, 0)

    def test_token_change_is_restored_after_failed_deployment(self):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            self.fixture(root, "scheduler")
            service = Service(root, "scheduler")
            changed = ENV.replace(TOKEN.encode(), b"z" * 48)
            with self.assertRaises(DeploymentError):
                deploy("scheduler", bundle(env=changed), root, service, 1, 0)
            self.assertEqual((root / "etc/default/mjsec-scheduler").read_bytes(), ENV)
            self.assertEqual(service.restarts, 2)

    def test_rollback_does_not_claim_success_if_old_service_is_broken(self):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            self.fixture(root, "scheduler")
            service = Service(root, "scheduler")
            service.request = lambda *_: (503, {})
            with patch("builtins.print") as output:
                with self.assertRaises(DeploymentError):
                    deploy("scheduler", bundle(), root, service, 1, 0)
                self.assertFalse(any("ROLLBACK_RESULT=" in str(call) for call in output.call_args_list))

    def test_warm_bridge_is_routed_before_restart_and_removed_only_after_primary_verifies(self):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name); self.fixture(root, "scheduler"); service = Service(root, "scheduler")
            deploy("scheduler", bundle(), root, service, 1, 0)
            actions = [step[0] for step in service.steps]
            bridge_route = next(i for i,step in enumerate(service.steps) if step[0]=="proxy" and str(BRIDGE_PORT) in step[1])
            self.assertLess(actions.index("bridge_start"), bridge_route)
            self.assertLess(bridge_route, actions.index("restart"))
            self.assertEqual(actions[-2:], ["proxy", "bridge_stop"])
            self.assertIn(":8080;", service.steps[-2][1])
            self.assertFalse(service.bridge)
            self.assertFalse((root / "opt/msgctf-scheduler/bridge.jar").exists())
            self.assertFalse((root / "etc/default/mjsec-scheduler-bridge").exists())

    def test_bridge_health_or_disabled_worker_failure_does_not_restart_primary(self):
        for failure in ("health", "workers", "auth"):
            with self.subTest(failure=failure), tempfile.TemporaryDirectory() as name:
                root = Path(name); self.fixture(root,"scheduler"); service = Service(root,"scheduler")
                if failure=="health": service.bridge_request = lambda *_: (503,{})
                if failure=="workers": service.bridge_workers_disabled = lambda: False
                if failure=="auth": service.bridge_request = lambda *_: (200,{"code":"SUCCESS","data":{"items":[],"next_cursor":None}})
                with self.assertRaises(DeploymentError): deploy("scheduler",bundle(),root,service,1,0)
                self.assertEqual(service.restarts,0)
                self.assertEqual((root/"opt/msgctf-scheduler/scheduler.jar").read_bytes(),b"old artifact")
                self.assertFalse(service.bridge)

    def test_failed_deployment_keeps_bridge_through_verified_rollback(self):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name); self.fixture(root,"scheduler"); service = Service(root,"scheduler",fail=True)
            original = service.restart
            def restart(unit):
                self.assertTrue(service.bridge)
                self.assertIn(str(BRIDGE_PORT),(root/UPSTREAM.lstrip("/")).read_text())
                original(unit)
            service.restart = restart
            with self.assertRaises(DeploymentError): deploy("scheduler",bundle(),root,service,1,0)
            self.assertEqual(service.restarts,2)
            self.assertFalse(service.bridge)
            self.assertIn(":8080;",(root/UPSTREAM.lstrip("/")).read_text())

    def test_unverified_rollback_keeps_serving_bridge_and_does_not_claim_success(self):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name); self.fixture(root,"scheduler"); service = Service(root,"scheduler")
            service.request = lambda *_: (503,{})
            with patch("builtins.print") as output:
                with self.assertRaises(DeploymentError): deploy("scheduler",bundle(),root,service,1,0)
            self.assertTrue(service.bridge)
            self.assertIn(str(BRIDGE_PORT),(root/UPSTREAM.lstrip("/")).read_text())
            self.assertFalse(any("ROLLBACK_RESULT=" in str(call) for call in output.call_args_list))

    def test_existing_bridge_is_not_stopped_or_overwritten(self):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name); self.fixture(root,"scheduler"); service = Service(root,"scheduler"); service.bridge = True
            with self.assertRaises(DeploymentError): deploy("scheduler",bundle(),root,service,1,0)
            self.assertEqual(service.restarts,0)
            self.assertTrue(service.bridge)
            self.assertNotIn("bridge_stop",[step[0] for step in service.steps])

    def test_proxy_reload_failure_before_restart_restores_primary_route(self):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name); self.fixture(root,"scheduler"); service = Service(root,"scheduler")
            original = service.reload_proxy
            def reload_proxy():
                original()
                if str(BRIDGE_PORT) in (root/UPSTREAM.lstrip("/")).read_text(): raise RuntimeError("fixture reload failure")
            service.reload_proxy = reload_proxy
            with self.assertRaises(RuntimeError): deploy("scheduler",bundle(),root,service,1,0)
            self.assertEqual(service.restarts,0)
            self.assertFalse(service.bridge)
            self.assertIn(":8080;",(root/UPSTREAM.lstrip("/")).read_text())

    def test_confirmed_start_failure_cleans_only_its_files_without_restarting_primary(self):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name); self.fixture(root,"scheduler"); service = Service(root,"scheduler")
            def start(): raise RuntimeError("fixture start failed")
            service.start_bridge = start
            with self.assertRaises(RuntimeError): deploy("scheduler",bundle(),root,service,1,0)
            self.assertEqual(service.restarts,0)
            self.assertFalse((root/"opt/msgctf-scheduler/bridge.jar").exists())
            self.assertFalse((root/"etc/default/mjsec-scheduler-bridge").exists())

    def test_ambiguous_start_keeps_process_and_files_for_verification(self):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name); self.fixture(root,"scheduler"); service = Service(root,"scheduler")
            def start(): service.bridge=True; raise TimeoutError("fixture unknown start result")
            service.start_bridge = start
            with self.assertRaises(TimeoutError): deploy("scheduler",bundle(),root,service,1,0)
            self.assertEqual(service.restarts,0)
            self.assertTrue(service.bridge)
            self.assertTrue((root/"opt/msgctf-scheduler/bridge.jar").exists())

    def test_system_bridge_overrides_worker_flags_without_putting_tokens_on_command_line(self):
        commands=[]
        def run(args, **_):
            commands.append(args)
            return subprocess.CompletedProcess(args,0,stdout="User=sla\nGroup=\nWorkingDirectory=/home/sla/apps/scheduler\n")
        service=SystemService()
        with patch.object(service,"active",return_value=False),patch("release.subprocess.run",side_effect=run):
            service.start_bridge()
        command=next(args for args in commands if args[0]=="systemd-run")
        self.assertIn("--scheduler.operation.enabled=false",command)
        self.assertIn("--scheduler.cleanup.enabled=false",command)
        self.assertIn("--server.address=127.0.0.1",command)
        self.assertIn("--spring.jpa.hibernate.ddl-auto=validate",command)
        self.assertIn("--property=User=sla",command)
        self.assertIn("--property=MemoryMax=1G",command)
        self.assertNotIn(TOKEN," ".join(command))

if __name__ == "__main__":
    unittest.main()
