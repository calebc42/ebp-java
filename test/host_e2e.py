#!/usr/bin/env python3
"""Host end-to-end: the real ebp.el peer against the Java Companion.

Drives the milestone plan's step E and the host half of step F without a
device: HostRunner (the same Companion core the Android host embeds, with
a file-backed store) on a fixed loopback port, and `emacs --batch` running
the unmodified ebp.el client with the milestone application.  Every
assertion and every Companion event is appended to the evidence log.

Scenarios, in order:
  1. fresh pairing, publish, READY; the accepted screen reads "Flag: off"
     and the indicator raster's first pixel is the authored red;
  2. a live durable activation (Set on) round-trips through event.action,
     the durable flag flips, and the next accepted revision reads "Flag: on";
  3. transport gone, host alive: an activation while Emacs is stopped is
     durably admitted (queued_events 1); the relaunched Emacs replays it and
     the screen reads "Flag: off";
  4. crash after outbox commit, before send (failpoint): the Companion
     process halts; the relaunched Companion still holds the record, the
     peer redials, replay delivers it, and the screen reads "Flag: on";
  5. Emacs revision floors outlive an erased Companion store: after wiping
     the Companion's store the republished revision is above the old one;
  6. `loopback-unix` (SPEC 5.3): the runner binds a filesystem Unix socket
     in a private directory, Emacs dials it, READY, one durable activation
     round-trips, and a relaunched runner rebinds the same path;
  7. `emacs-unix` (SPEC 5.6): Emacs binds the socket, the runner dials it,
     READY; the Emacs listener is torn down and rebound, and the runner's
     jittered redial re-establishes the session without a restart.
"""
import json
import os
import pathlib
import queue
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time

ROOT = pathlib.Path(__file__).resolve().parent.parent
EMACS = os.environ.get("EMACS", "emacs")
EBP_EL = os.environ.get("EBP_EL_DIR", str(ROOT.parent / "ebp.el" / "lisp"))
JAVA_HOME = os.environ.get("JAVA_HOME", os.path.expanduser("~/.gradle/jdks/eclipse_adoptium-17-amd64-linux.2"))
JAVA = os.path.join(JAVA_HOME, "bin", "java")
LOG = pathlib.Path(os.environ.get("EBPM1_E2E_LOG", str(ROOT / "evidence" / "m1" / "host-e2e.log")))


def log(line):
    stamp = time.strftime("%H:%M:%S")
    with LOG.open("a") as f:
        f.write(f"[{stamp}] {line}\n")
    print(line, flush=True)


class Failed(Exception):
    pass


def check(cond, what):
    if not cond:
        raise Failed(what)
    log(f"PASS {what}")


class Runner:
    """HostRunner over pipes: commands in, `REPLY`/`EVENT` lines out."""

    def __init__(self, workdir, port=None, socket_path=None, dial=None):
        if socket_path is not None:
            transport = ["--socket", str(socket_path)]
        elif dial is not None:
            transport = ["--dial", str(dial)]
        else:
            transport = ["--port", str(port)]
        classpath = os.pathsep.join([str(ROOT / "build" / "classes"), str(ROOT / "build" / "host-classes")])
        self.proc = subprocess.Popen(
            [JAVA, "-cp", classpath,
             "com.calebc42.ebp.companion.host.HostRunner", "--dir", str(workdir)] + transport,
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)
        self.lines = queue.Queue()
        self.events = []
        threading.Thread(target=self._pump, daemon=True).start()
        self.port = None
        self.address = None
        self.pairing = None
        deadline = time.time() + 20
        while time.time() < deadline and (self.address is None or self.pairing is None):
            try:
                line = self.lines.get(timeout=1)
            except queue.Empty:
                continue
            if line.startswith("PORT="):
                self.port = int(line[5:])
                self.address = f"port {self.port}"
            elif line.startswith("SOCKET=") or line.startswith("DIAL="):
                self.address = line
            elif line.startswith("PAIRING="):
                self.pairing = line[8:]
            else:
                self._note(line)
        if self.address is None:
            raise Failed("HostRunner did not report a transport")
        log(f"runner up on {self.address}")

    def _pump(self):
        for line in self.proc.stdout:
            self.lines.put(line.rstrip("\n"))
        self.lines.put(None)

    def _note(self, line):
        if line is None:
            return
        if line.startswith("EVENT "):
            try:
                self.events.append(json.loads(line[6:]))
            except ValueError:
                pass
            log(f"  companion {line}")
        else:
            log(f"  runner: {line}")

    def cmd(self, line, timeout=30):
        self.proc.stdin.write(line + "\n")
        self.proc.stdin.flush()
        deadline = time.time() + timeout
        while time.time() < deadline:
            try:
                out = self.lines.get(timeout=1)
            except queue.Empty:
                continue
            if out is None:
                raise Failed(f"runner exited while waiting for a reply to {line!r}")
            if out.startswith("REPLY "):
                reply = json.loads(out[6:])
                log(f"  {line} -> {json.dumps(reply)[:400]}")
                return reply
            self._note(out)
        raise Failed(f"no reply to {line!r}")

    def drain(self):
        while True:
            try:
                out = self.lines.get_nowait()
            except queue.Empty:
                return
            self._note(out)

    def wait_exit(self, timeout=30):
        try:
            code = self.proc.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            raise Failed("runner did not exit")
        self.drain()
        return code

    def quit(self):
        if self.proc.poll() is None:
            try:
                self.proc.stdin.write("quit\n")
                self.proc.stdin.flush()
            except (BrokenPipeError, ValueError):
                pass
            try:
                self.proc.wait(timeout=10)
            except subprocess.TimeoutExpired:
                self.proc.kill()
        self.drain()


class Peer:
    """The real ebp.el endpoint in a batch Emacs."""

    def __init__(self, workdir, port, pairing, token, peer_log, transport="tcp", socket_path=None):
        self.stop_file = workdir / f"stop-{int(time.time() * 1000)}"
        env = dict(os.environ, EBPM1_DIR=str(workdir / "emacs"), EBPM1_PAIRING=pairing,
                   EBPM1_TOKEN=token, EBPM1_STOP_FILE=str(self.stop_file), EBPM1_LOG=str(peer_log),
                   EBPM1_TRANSPORT=transport)
        if port is not None:
            env["EBPM1_PORT"] = str(port)
        if socket_path is not None:
            env["EBPM1_SOCKET"] = str(socket_path)
        self.log = peer_log
        self.proc = subprocess.Popen(
            [EMACS, "-Q", "--batch", "-L", EBP_EL, "-L", str(ROOT / "lisp"), "-l", str(ROOT / "test" / "host-e2e.el")],
            env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        threading.Thread(target=self._pump, daemon=True).start()

    def _pump(self):
        for line in self.proc.stdout:
            log(f"  emacs: {line.rstrip()}")

    def log_text(self):
        return self.log.read_text() if self.log.exists() else ""

    def stop(self, timeout=20):
        self.stop_file.touch()
        try:
            self.proc.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            self.proc.kill()
            raise Failed("emacs did not stop")
        log(f"emacs exited {self.proc.returncode}")

    def kill(self):
        if self.proc.poll() is None:
            self.proc.kill()


def poll(what, fn, timeout=30, interval=0.5):
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        last = fn()
        if last:
            return last
        time.sleep(interval)
    raise Failed(f"timeout waiting for {what} (last: {str(last)[:300]})")


def free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def screen(runner):
    doc = runner.cmd("snapshot app:milestone")
    items = doc.get("items", [])
    texts = [i.get("text") for i in items if i.get("kind") == "text"]
    buttons = [i.get("id") for i in items if i.get("kind") == "button"]
    return doc, texts, buttons, doc.get("first_pixels", [])


def main():
    LOG.parent.mkdir(parents=True, exist_ok=True)
    LOG.write_text("")
    log(f"host e2e start; emacs={EMACS}; ebp.el={EBP_EL}")
    subprocess.run([str(ROOT / "build.sh")], check=True, cwd=ROOT)
    workdir = pathlib.Path(tempfile.mkdtemp(prefix="ebp-e2e-"))
    companion_dir = workdir / "companion"
    peer_log = workdir / "peer.log"
    port = free_port()
    runner = Runner(companion_dir, port)
    pairing = json.loads((companion_dir / "pairing.json").read_text())
    token = pairing["token"]
    peer = None
    ok = False
    try:
        # 1. Fresh pairing and publish.
        peer = Peer(workdir, port, runner.pairing, token, peer_log)
        status = poll("READY with app:milestone present",
                      lambda: (lambda s: s if s.get("session") == "READY"
                               and s.get("surfaces", {}).get("app:milestone", {}).get("present") else None)(runner.cmd("status")))
        rev0 = status["surfaces"]["app:milestone"]["revision"]
        check(status["queued_events"] == 0, "welcome/READY reached with an empty queue")
        doc, texts, buttons, pixels = screen(runner)
        check(texts == ["EBP milestone", "Flag: off"], f"accepted screen text {texts}")
        check(buttons == ["set-on", "set-off"], f"buttons {buttons}")
        check(pixels and pixels[0] == "#ffc62828", f"indicator first pixel {pixels}")
        check(doc["revision"] == rev0, "snapshot revision equals the accepted revision")
        check("state ready" in peer.log_text(), "peer log records READY")

        # 2. Live durable activation.
        reply = runner.cmd(f"tap-id app:milestone set-on")
        check(reply.get("admission") == "QUEUED", f"Set on admitted durably ({reply.get('admission')})")
        poll("Flag: on", lambda: screen(runner)[1] == ["EBP milestone", "Flag: on"] or None)
        doc, texts, buttons, pixels = screen(runner)
        check(pixels[0] == "#ff2e7d32", "indicator repainted green from the accepted snapshot")
        check(doc["revision"] > rev0, f"revision advanced {rev0} -> {doc['revision']}")
        status = poll("queue drained", lambda: (lambda s: s if s["queued_events"] == 0 and not s["in_flight"] else None)(runner.cmd("status")))
        check("accept t" in peer.log_text(), "Emacs handler committed desired=t")
        rev1 = doc["revision"]

        # 3. Transport gone, host alive.
        peer.stop()
        status = poll("session closed", lambda: (lambda s: s if s["session"] in ("none", "CLOSED") else None)(runner.cmd("status")))
        reply = runner.cmd("tap-id app:milestone set-off")
        check(reply.get("admission") == "QUEUED", "activation while disconnected is durably admitted")
        status = runner.cmd("status")
        check(status["queued_events"] == 1, "one durable record retained without a session")
        doc, texts, _, _ = screen(runner)
        check(texts == ["EBP milestone", "Flag: on"], "cached screen still readable while disconnected")
        peer = Peer(workdir, port, runner.pairing, token, peer_log)
        poll("Flag: off after replay", lambda: screen(runner)[1] == ["EBP milestone", "Flag: off"] or None, timeout=40)
        status = poll("queue drained after replay", lambda: (lambda s: s if s["queued_events"] == 0 else None)(runner.cmd("status")))
        check("accept nil" in peer.log_text(), "Emacs handler committed desired=false (stored nil) from the replayed event")
        doc, _, _, _ = screen(runner)
        check(doc["revision"] > rev1, f"revision advanced after replay {rev1} -> {doc['revision']}")
        rev2 = doc["revision"]

        # 4. Crash after outbox commit, before send.
        reply = runner.cmd("failpoint outbox.after-commit-before-send")
        check(reply.get("armed") is True, "failpoint armed")
        runner.proc.stdin.write("tap-id app:milestone set-on\n")
        runner.proc.stdin.flush()
        code = runner.wait_exit()
        check(code == 3, f"companion halted at the boundary (exit {code})")
        store_file = companion_dir / "store" / f"{runner.pairing}.json"
        durable = json.loads(store_file.read_text())
        check(len(durable["outbox"]) == 1, "outbox record committed before the send")
        event_id = durable["outbox"][0]["event_id"]
        runner = Runner(companion_dir, port)
        status = runner.cmd("status")
        check(status["queued_events"] == 1, "relaunched companion restored the retained record")
        check(not status["in_flight"], "nothing is in flight after process death")
        poll("Flag: on after relaunch replay", lambda: screen(runner)[1] == ["EBP milestone", "Flag: on"] or None, timeout=60)
        status = poll("queue drained after relaunch", lambda: (lambda s: s if s["queued_events"] == 0 else None)(runner.cmd("status")))
        durable = json.loads(store_file.read_text())
        check(any(i["event_id"] == event_id for i in durable["issued_event_ids"]), "the delivered EventId stays reserved")
        check(peer.log_text().count("accept t") >= 2, "Emacs accepted the replayed occurrence")

        # 5. Emacs floors outlive an erased Companion store.
        peer.stop()
        runner.quit()
        shutil.rmtree(companion_dir / "store")
        runner = Runner(companion_dir, port)
        status = runner.cmd("status")
        check(status["surfaces"] == {}, "companion store erased: no surface history")
        peer = Peer(workdir, port, runner.pairing, token, peer_log)
        status = poll("republished after erase", lambda: (lambda s: s if s.get("surfaces", {}).get("app:milestone", {}).get("present") else None)(runner.cmd("status")))
        rev3 = status["surfaces"]["app:milestone"]["revision"]
        check(rev3 > rev2, f"Emacs did not reuse an old revision after the companion store was erased ({rev2} -> {rev3})")

        # 6. loopback-unix: the Companion binds a private Unix socket, Emacs dials.
        peer.stop()
        runner.quit()
        sockdir = workdir / "run"
        sockdir.mkdir(mode=0o700)
        sock = sockdir / "companion.sock"
        unix_dir = workdir / "companion-unix"
        runner = Runner(unix_dir, socket_path=sock)
        check(runner.address == f"SOCKET={sock}", "runner bound the requested socket path")
        check(sock.exists() and (sock.stat().st_mode & 0o777) == 0o600, "socket file is 0600")
        check(runner.cmd("status")["transport"] == {"profile": "loopback-unix", "address": str(sock)},
              "status names the loopback-unix profile")
        unix_pairing = json.loads((unix_dir / "pairing.json").read_text())
        peer_unix_log = workdir / "peer-unix.log"
        peer = Peer(workdir / "unix", None, runner.pairing, unix_pairing["token"], peer_unix_log,
                    transport="unix", socket_path=sock)
        status = poll("READY over loopback-unix",
                      lambda: (lambda s: s if s.get("session") == "READY"
                               and s.get("surfaces", {}).get("app:milestone", {}).get("present") else None)(runner.cmd("status")))
        check("transport unix" in peer.log_text(), "peer dialed the Unix socket")
        reply = runner.cmd("tap-id app:milestone set-on")
        check(reply.get("admission") == "QUEUED", "activation over loopback-unix admitted durably")
        poll("Flag: on over loopback-unix", lambda: screen(runner)[1] == ["EBP milestone", "Flag: on"] or None)
        # Relaunch the runner on the same path: a stale socket of ours is unlinked and rebound.
        runner.quit()
        runner = Runner(unix_dir, socket_path=sock)
        status = poll("READY again after the listener relaunch",
                      lambda: (lambda s: s if s.get("session") == "READY" else None)(runner.cmd("status")), timeout=60)
        check(status["queued_events"] == 0, "no stranded records after the relaunch")
        peer.stop(timeout=10)
        runner.quit()
        check(not sock.exists(), "runner unlinked its socket on quit")

        # 7. emacs-unix: Emacs binds the socket, the Companion dials with jittered redial.
        esock = sockdir / "emacs.sock"
        dial_dir = workdir / "companion-dial"
        # Provision the pairing by starting the runner once against a path
        # nothing listens on yet; it keeps redialing until Emacs binds.
        runner = Runner(dial_dir, dial=esock)
        check(runner.address == f"DIAL={esock}", "runner is dialing the Emacs socket path")
        check(runner.cmd("status")["transport"]["profile"] == "emacs-unix", "status names the emacs-unix profile")
        dial_pairing = json.loads((dial_dir / "pairing.json").read_text())
        peer_listen_log = workdir / "peer-listen.log"
        peer = Peer(workdir / "listen", None, runner.pairing, dial_pairing["token"], peer_listen_log,
                    transport="listen", socket_path=esock)
        status = poll("READY over emacs-unix",
                      lambda: (lambda s: s if s.get("session") == "READY"
                               and s.get("surfaces", {}).get("app:milestone", {}).get("present") else None)(runner.cmd("status")), timeout=60)
        check("transport listen" in peer.log_text(), "peer bound the Emacs socket")
        reply = runner.cmd("tap-id app:milestone set-off")
        check(reply.get("admission") == "QUEUED", "activation over emacs-unix admitted durably")
        poll("Flag: off over emacs-unix", lambda: screen(runner)[1] == ["EBP milestone", "Flag: off"] or None)
        # Tear the listener down (Emacs exits) and bring it back: the dialer's
        # SPEC 5.7 redial re-establishes the session with no runner restart.
        peer.stop(timeout=10)
        check(not esock.exists(), "Emacs unlinked its socket on close")
        status = poll("session closed after the listener left",
                      lambda: (lambda s: s if s["session"] in ("none", "CLOSED") else None)(runner.cmd("status")))
        peer = Peer(workdir / "listen", None, runner.pairing, dial_pairing["token"], peer_listen_log,
                    transport="listen", socket_path=esock)
        status = poll("READY again after the Emacs listener returned",
                      lambda: (lambda s: s if s.get("session") == "READY" else None)(runner.cmd("status")), timeout=90)
        check(peer.log_text().count("state ready") >= 2, "the redial reached READY a second time")
        ok = True
    except Failed as e:
        log(f"FAIL {e}")
    finally:
        if peer is not None:
            try:
                peer.stop(timeout=10)
            except Failed:
                peer.kill()
        runner.quit()
        log(f"peer log:\n{peer_log.read_text() if peer_log.exists() else ''}")
        log("RESULT " + ("PASS" if ok else "FAIL"))
        shutil.rmtree(workdir, ignore_errors=True)
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
