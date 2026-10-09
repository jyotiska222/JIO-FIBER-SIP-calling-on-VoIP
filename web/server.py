#!/usr/bin/env python3
"""
Web call server: serves index.html and exposes a small call API + a WebSocket that
carries your browser microphone to the call and the callee's voice back to the browser.
No third-party packages needed (Python 3.8+).

  GET  /              -> the call page
  POST /api/call      -> {"number": "98xxxxxxxx"}      start a call
  POST /api/hangup    -> end the current call
  POST /api/hold      -> {"hold": true|false}          hold / resume
  POST /api/dtmf      -> {"digits": "123#"}            keypad tones
  POST /api/transfer  -> {"number": "98xxxxxxxx"}      blind transfer
  POST /api/answer    -> answer the ringing incoming call
  POST /api/decline   -> reject the ringing incoming call
  GET  /api/status    -> {"state": "...", "log": [...], ...}
  WS   /ws/audio      -> binary frames, 16 kHz mono signed 16-bit little-endian PCM
                         browser -> server = your mic,  server -> browser = callee audio

INCOMING calls: whenever no call is active the server keeps a "standby" client registered
(jio-sip-client listen). When someone calls, the state becomes "ringing" and the page shows
Answer / Decline. (config.json -> "listen": false turns standby off.) While you place an
outgoing call the standby line is closed for a few seconds, then it comes back by itself.

The server never speaks SIP itself. It launches jio-sip-client (one process per call, in its
own process group) and talks to it through stdin/stdout, so every call starts clean.

Audio path for real calls (config.json -> pjsua.audio = "browser", the default):
    browser mic -> WebSocket -> pacat -> virtual PulseAudio sink -> pjsua (as its microphone)
    pjsua (as its speaker) -> virtual sink -> parec -> WebSocket -> browser speakers
The virtual sinks are created when a call starts and removed when it ends.
Needs `pactl`, `pacat`, `parec` (Debian/Ubuntu: sudo apt install pulseaudio-utils; works with
PipeWire too). Set "audio": "file" in config.json -> pjsua to go back to the old test.wav behaviour.
"""
import atexit, base64, hashlib, json, math, os, re, shutil, signal, struct, subprocess, sys
import socket, threading, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

BASE = Path(__file__).resolve().parent
sys.path.insert(0, str(BASE))
import jio_provision


def find_file(name, *subdirs):
    """Look in the expected sub-folder first, then next to server.py."""
    for d in subdirs:
        f = BASE / d / name if d else BASE / name
        if f.exists():
            return f
    return BASE / subdirs[0] / name  # expected path (used in error messages)


INDEX_HTML = find_file("index.html", "public", "")
SIP_CLIENT = find_file("jio-sip-client", "sip", "")
CONFIG_PATH = find_file("config.json", "sip", "")

HOST = os.environ.get("HOST", "127.0.0.1")
PORT = int(os.environ.get("PORT", "8080"))

NUMBER_RE = re.compile(r"^\+?[0-9]{6,15}$")
DIGITS_RE = re.compile(r"^[0-9*#A-Da-d]{1,32}$")
ACTIVE = ("calling", "ringing", "in-call", "on-hold")
RATE = 16000
WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

lock = threading.Lock()
current = None  # the Session of the latest call / standby line (may be finished)
placing = False  # True while an outgoing call is being set up (standby must not restart)
standby_block_until = 0.0


# --------------------------------------------------------------------------- self-healing credentials
AUTH_FAIL = re.compile(r"registration was rejected|no successful registration|lost its registration|"
                       r"\b40[137]\b|forbidden|unauthori[sz]ed|whitelist", re.I)
retry_number = None   # outgoing call to redo once after credentials were refreshed


def creds_updated(changed):
    """New credentials are in config.json: restart the line so they are used straight away."""
    global retry_number, standby_block_until
    standby_block_until = 0.0
    s = current
    if s is not None and s.alive() and not s.hung_up:
        s.hangup("new Jio credentials received - restarting the line")
    num, retry_number = retry_number, None
    if num:
        def redo():
            time.sleep(1)
            start_call(num)
        threading.Thread(target=redo, daemon=True).start()


provisioner = jio_provision.Provisioner(on_updated=creds_updated)


def load_config():
    try:
        return json.loads(CONFIG_PATH.read_text())
    except Exception:
        return {}


def audio_mode(cfg=None):
    """mock | browser | file"""
    cfg = cfg if cfg is not None else load_config()
    if cfg.get("mode", "mock") == "mock":
        return "mock"
    return "file" if cfg.get("pjsua", {}).get("audio", "browser") == "file" else "browser"


# --------------------------------------------------------------------------- WebSocket (minimal)
class WS:
    def __init__(self, sock, rfile):
        self.sock, self.rfile = sock, rfile
        self.send_lock = threading.Lock()
        self.closed = False

    def _read(self, n):
        buf = b""
        while len(buf) < n:
            chunk = self.rfile.read(n - len(buf))
            if not chunk:
                raise EOFError
            buf += chunk
        return buf

    def recv(self):
        """Return (opcode, payload); raises EOFError when the peer is gone."""
        b1, b2 = self._read(2)
        opcode, masked, ln = b1 & 0x0F, b2 & 0x80, b2 & 0x7F
        if ln == 126:
            ln = struct.unpack(">H", self._read(2))[0]
        elif ln == 127:
            ln = struct.unpack(">Q", self._read(8))[0]
        mask = self._read(4) if masked else None
        data = self._read(ln) if ln else b""
        if mask and ln:
            m = (mask * (ln // 4 + 1))[:ln]
            data = (int.from_bytes(data, "big") ^ int.from_bytes(m, "big")).to_bytes(ln, "big")
        return opcode, data

    def send(self, opcode, data=b""):
        ln = len(data)
        if ln < 126:
            head = struct.pack(">BB", 0x80 | opcode, ln)
        elif ln < 65536:
            head = struct.pack(">BBH", 0x80 | opcode, 126, ln)
        else:
            head = struct.pack(">BBQ", 0x80 | opcode, 127, ln)
        with self.send_lock:
            if self.closed:
                return False
            try:
                self.sock.sendall(head + data)
                return True
            except OSError:
                self.closed = True
                return False

    def send_binary(self, data):
        return self.send(0x2, data)

    def close(self):
        if not self.closed:
            try:
                self.send(0x8, b"")
            finally:
                self.closed = True


# --------------------------------------------------------------------------- audio bridges
class BaseBridge:
    def __init__(self, session):
        self.session = session
        self.ws = None
        self.closed = False

    def attach(self, ws):
        self.ws = ws

    def detach(self, ws):
        if self.ws is ws:
            self.ws = None

    def env(self):
        return {}

    def setup(self):
        pass

    def feed(self, data):
        pass

    def close(self):
        self.closed = True


class PulseBridge(BaseBridge):
    """Virtual PulseAudio devices that connect the browser to pjsua."""
    TX, RX = "webcall_tx", "webcall_rx"          # TX: browser mic -> call.  RX: call -> browser
    FMT = ["--rate=%d" % RATE, "--channels=1", "--format=s16le", "--raw"]

    def __init__(self, session):
        super().__init__(session)
        self.mods, self.pacat, self.parec = [], None, None

    @staticmethod
    def missing_tools():
        return [t for t in ("pactl", "pacat", "parec") if not shutil.which(t)]

    @classmethod
    def cleanup_stale(cls):
        """Remove leftovers of a previous run (crash / kill -9) so setup is always repeatable."""
        try:
            out = subprocess.run(["pactl", "list", "short", "modules"], capture_output=True,
                                 text=True, timeout=5).stdout
        except Exception:
            return
        for line in out.splitlines():
            if "sink_name=webcall_" in line:
                subprocess.run(["pactl", "unload-module", line.split()[0]],
                               capture_output=True, timeout=5)

    def setup(self):
        self.cleanup_stale()
        for name in (self.TX, self.RX):
            r = subprocess.run(
                ["pactl", "load-module", "module-null-sink", f"sink_name={name}",
                 f"sink_properties=device.description={name}", f"rate={RATE}", "channels=1"],
                capture_output=True, text=True, timeout=8)
            if r.returncode != 0:
                raise RuntimeError("pactl could not create the virtual audio device: "
                                   + (r.stderr.strip() or "is PulseAudio/PipeWire running?"))
            self.mods.append(r.stdout.strip())
        self.pacat = subprocess.Popen(
            ["pacat", "--playback", f"--device={self.TX}", "--latency-msec=40",
             "--client-name=webcall-mic"] + self.FMT,
            stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
            start_new_session=True)
        self.parec = subprocess.Popen(
            ["parec", f"--device={self.RX}.monitor", "--latency-msec=40",
             "--client-name=webcall-speaker"] + self.FMT,
            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, start_new_session=True)
        threading.Thread(target=self._pump_out, daemon=True).start()

    def env(self):
        # pjsua (through ALSA's pulse plugin) uses these as its speaker / microphone.
        return {"PULSE_SINK": self.RX, "PULSE_SOURCE": self.TX + ".monitor"}

    def _pump_out(self):
        fd, odd = self.parec.stdout.fileno(), b""
        while not self.closed:
            try:
                chunk = os.read(fd, 1280)
            except OSError:
                break
            if not chunk:
                break
            chunk = odd + chunk
            odd = chunk[-1:] if len(chunk) % 2 else b""
            if odd:
                chunk = chunk[:-1]
            ws = self.ws
            if ws and chunk:
                ws.send_binary(chunk)

    def feed(self, data):
        p = self.pacat
        if p and p.stdin and not self.closed:
            try:
                p.stdin.write(data)
                p.stdin.flush()
            except (OSError, ValueError):
                pass

    def close(self):
        if self.closed:
            return
        self.closed = True
        for p in (self.pacat, self.parec):
            if p and p.poll() is None:
                try:
                    p.kill()
                    p.wait(timeout=2)
                except Exception:
                    pass
        for m in self.mods:
            subprocess.run(["pactl", "unload-module", m], capture_output=True, timeout=5)
        self.mods = []


class MockBridge(BaseBridge):
    """For mock mode: counts mic audio and plays a soft beep back, so the browser audio path
    (mic permission, level meter, speakers, volume) can be tested without any SIP backend."""

    def __init__(self, session):
        super().__init__(session)
        self.bytes_in, self.logged = 0, False

    def setup(self):
        threading.Thread(target=self._beeper, daemon=True).start()

    def feed(self, data):
        self.bytes_in += len(data)
        if not self.logged:
            self.logged = True
            self.session.add_log("[mock] receiving microphone audio from the browser")

    def _beeper(self):
        n = RATE // 5
        beep = b"".join(struct.pack("<h", int(3000 * math.sin(2 * math.pi * 440 * i / RATE)))
                        for i in range(n))
        while not self.closed:
            for _ in range(20):
                if self.closed:
                    return
                time.sleep(0.1)
            ws = self.ws
            if ws and self.session.state == "in-call":
                for i in range(0, len(beep), 640):
                    ws.send_binary(beep[i:i + 640])
                    time.sleep(0.02)


# --------------------------------------------------------------------------- one call = one Session
class Session:
    def __init__(self, number, kind="call", log=None):
        self.number = number
        self.kind = kind                      # "call" (outgoing, 1 process per call) | "listen" (standby line)
        self.state = "calling" if kind == "call" else "connecting"
        self.log = list(log or [])
        self.finished_at = None
        self.notice, self.notice_id = "", 0   # latest PROBLEM:/NOTE: line from the client (shown as a toast)
        self.started_at = time.time()
        self.proc = None
        self.bridge = None
        self.hung_up = False
        self.connected_at = None
        self.slock = threading.Lock()
        self.cleaned = False
        self.done = threading.Event()   # set when the client process is gone and cleaned up

    # ---- state / log
    def add_log(self, line):
        with self.slock:
            self.log.append(f"{time.strftime('%H:%M:%S')}  {line}")
            del self.log[:-200]

    def set_state(self, s):
        with self.slock:
            if s in ("standby", "ringing", "ended", "connecting"):
                self.connected_at = None
            if s == "in-call" and self.connected_at is None:
                self.connected_at = time.time()
            self.state = s

    def alive(self):
        return self.proc is not None and self.proc.poll() is None

    # ---- lifecycle
    def start(self):
        cfg = load_config()
        mode = audio_mode(cfg)
        env = os.environ.copy()
        if mode == "mock":
            self.bridge = MockBridge(self)
        elif mode == "browser":
            miss = PulseBridge.missing_tools()
            if miss:
                raise RuntimeError("missing " + ", ".join(miss) + "  ->  sudo apt install pulseaudio-utils")
            self.bridge = PulseBridge(self)
        if self.bridge:
            self.bridge.setup()
            env.update(self.bridge.env())
        if not SIP_CLIENT.exists():
            raise RuntimeError(f"jio-sip-client not found. Expected at {SIP_CLIENT}")
        self.proc = subprocess.Popen(
            [sys.executable, str(SIP_CLIENT)]
            + (["call", self.number] if self.kind == "call" else ["listen"]) + ["--audio", mode],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            text=True, bufsize=1, env=env, start_new_session=True)  # own process group
        threading.Thread(target=self._watch, daemon=True).start()

    def _watch(self):
        """Read client output; 'STATE:' lines update the call state."""
        p = self.proc
        try:
            for raw in p.stdout:
                line = raw.rstrip()
                if line.startswith("STATE:"):
                    new = line.split(":", 1)[1].strip()
                    if not (self.hung_up and new not in ("ended", "error")):
                        self.set_state(new)
                elif line.startswith("INCOMING:"):
                    self.number = line.split(":", 1)[1].strip()
                elif line.startswith(("PROBLEM:", "NOTE:")):
                    with self.slock:
                        self.notice, self.notice_id = line.split(":", 1)[1].strip(), self.notice_id + 1
                    if line.startswith("PROBLEM:") and AUTH_FAIL.search(line):
                        global retry_number
                        if self.kind == "call" and not self.connected_at:
                            retry_number = self.number
                        if provisioner.on_problem(line):
                            self.add_log("[server] login problem detected -> fetching fresh credentials from the router")
                elif "registration success" in line.lower():
                    provisioner.registered_ok()
                self.add_log(line)
        except Exception as e:
            self.add_log(f"[server] output reader stopped: {e}")
        code = p.wait()
        if self.state not in ("ended", "error"):
            self.set_state("ended" if (code == 0 or self.hung_up) else "error")
        self.add_log(f"client exited with code {code}")
        if self.kind == "listen" and not self.hung_up:
            self.add_log("[server] standby line closed unexpectedly; it will be restarted")
            self.set_state("error")
        self.kill_group()
        self.cleanup()

    def send_cmd(self, line):
        p = self.proc
        if not self.alive() or p.stdin is None:
            return False
        try:
            p.stdin.write(line + "\n")
            p.stdin.flush()
            return True
        except (OSError, ValueError):
            return False

    def hangup(self, reason="Hanging up"):
        if self.hung_up or not self.alive():
            return False
        self.hung_up = True
        self.add_log(f"[server] {reason}")
        self.set_state("ended")
        try:
            os.kill(self.proc.pid, signal.SIGTERM)   # client: BYE, unregister, exit
        except OSError:
            pass
        threading.Thread(target=self._reaper, daemon=True).start()
        return True

    def decline(self):
        if self.kind == "listen" and self.alive():
            self.add_log("[server] Incoming call declined")
            return self.send_cmd("decline")
        return False

    def end_call(self, reason="Hang up pressed"):
        """End the CURRENT CALL. On the standby line the line itself stays registered."""
        if self.kind == "listen":
            if not self.alive():
                return False
            self.add_log(f"[server] {reason}")
            # No optimistic state change: the client replies "STATE: standby" only after pjsua has
            # really ended the call (it retries / escalates if the first attempt does not work).
            return self.send_cmd("endcall")
        return self.hangup(reason)

    def _reaper(self):
        """Safety net: never let a stuck client block the next call."""
        try:
            self.proc.wait(timeout=8)
        except subprocess.TimeoutExpired:
            self.add_log("[server] client did not exit in time, killing it")
            self.kill_group()

    def kill_group(self):
        p = self.proc
        if p is None:
            return
        try:
            os.killpg(p.pid, signal.SIGKILL)   # client + pjsua + anything it spawned
        except (ProcessLookupError, PermissionError, OSError):
            pass

    def cleanup(self):
        with self.slock:
            if self.cleaned:
                return
            self.cleaned = True
        try:
            if self.bridge:
                self.bridge.close()
        finally:
            self.finished_at = time.time()
            self.done.set()

    def snapshot(self):
        with self.slock:
            elapsed = int(time.time() - self.connected_at) if self.connected_at else 0
            return {"state": self.state, "log": list(self.log), "number": self.number,
                    "elapsed": elapsed, "notice": self.notice, "notice_id": self.notice_id}


def start_call(number):
    global current, placing
    with lock:
        placing = True            # keep the standby manager away while we switch lines
        old = current
    try:
        if old is not None and not old.done.is_set():
            if old.state in ACTIVE and old.alive():
                if old.state == "ringing":
                    return False, "An incoming call is ringing - answer or decline it first."
                return False, "A call is already in progress."
            if old.alive() and not old.hung_up:
                old.hangup("placing an outgoing call")   # closes standby line cleanly (unregister)
            # Previous session is over / closing: give it a moment to finish cleanly, then force it.
            if not old.done.wait(timeout=6):
                old.kill_group()
                old.done.wait(timeout=3)
                old.cleanup()
        s = Session(number)
        with lock:
            current = s
        try:
            s.start()
        except Exception as e:
            s.add_log(f"PROBLEM: {e}")
            s.set_state("error")
            s.kill_group()
            s.cleanup()
            return False, str(e)
        return True, "Calling..."
    finally:
        placing = False


def start_standby():
    """Start (or restart) the registered 'standby' line that receives incoming calls."""
    global current, standby_block_until
    with lock:
        prev = current
        s = Session("", kind="listen", log=(prev.log[-60:] + ["--------"]) if prev else None)
        current = s
    try:
        s.start()
    except Exception as e:
        s.add_log(f"PROBLEM: incoming calls unavailable: {e}")
        s.set_state("error")
        s.kill_group()
        s.cleanup()
        standby_block_until = time.time() + 30


def standby_manager():
    """Keeps a registered line open whenever no call is active, so incoming calls arrive."""
    global standby_block_until
    while True:
        time.sleep(2)
        try:
            if not load_config().get("listen", True):
                continue
            with lock:
                s, busy = current, placing
            if busy or time.time() < standby_block_until:
                continue
            if s is not None and not s.done.is_set():
                continue                                  # a call / standby line is running
            if s is not None and s.finished_at:
                # let the user read an error from the last call before replacing the log
                wait = 15 if s.state == "error" else 0
                if s.kind == "listen" and s.finished_at - s.started_at < 20:
                    wait = 30                             # crash loop: don't hammer the router
                if time.time() - s.finished_at < wait:
                    continue
            start_standby()
        except Exception as e:                            # never let the manager thread die
            print("standby manager:", e)


def with_active(fn):
    s = current
    if s is None or not s.alive() or s.state not in ACTIVE:
        return False, "No active call."
    return fn(s)


def shutdown_all(*_):
    s = current
    if s is not None:
        s.kill_group()
        s.cleanup()
    if _:
        sys.exit(0)


# --------------------------------------------------------------------------- HTTP
class Handler(BaseHTTPRequestHandler):
    def _json(self, code, obj):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _same_origin(self):
        """Stops other websites from placing calls / grabbing the audio via this local server."""
        origin = self.headers.get("Origin")
        if not origin:
            return True
        return re.sub(r"^https?://", "", origin).rstrip("/") == self.headers.get("Host", "")

    def do_GET(self):
        path = self.path.split("?", 1)[0]
        if path == "/ws/audio":
            return self._audio_ws()
        if path == "/api/status":
            s = current
            snap = s.snapshot() if s else {"state": "idle", "log": [], "number": "", "elapsed": 0, "notice": "", "notice_id": 0}
            snap["audio"] = audio_mode()
            return self._json(200, snap)
        if path == "/api/provision":
            return self._json(200, provisioner.snapshot())
        if path in ("/", "/index.html"):
            if not INDEX_HTML.exists():
                self.send_response(500)
                self.send_header("Content-Type", "text/plain; charset=utf-8")
                self.end_headers()
                self.wfile.write(f"index.html not found. Expected at {INDEX_HTML}".encode())
                return
            data = INDEX_HTML.read_bytes()
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
            return
        self._json(404, {"error": "not found"})

    def do_POST(self):
        if not self._same_origin():
            return self._json(403, {"error": "cross-origin request refused"})
        length = int(self.headers.get("Content-Length", 0) or 0)
        try:
            data = json.loads(self.rfile.read(length) or b"{}")
        except json.JSONDecodeError:
            return self._json(400, {"error": "invalid JSON"})
        path = self.path.split("?", 1)[0]

        def reply(res, ok_code=200, bad_code=409):
            ok, msg = res
            return self._json(ok_code if ok else bad_code, {"ok": ok, "message": msg})

        if path == "/api/provision/refresh":
            provisioner.fails = 0
            provisioner.last_attempt = 0
            threading.Thread(target=provisioner.refresh, args=("button",), daemon=True).start()
            return self._json(200, {"ok": True, "message": "Refreshing credentials..."})
        if path == "/api/provision/send-otp":
            return reply(provisioner.start_otp())
        if path == "/api/provision/otp":
            otp = str(data.get("otp", "")).strip()
            if not re.match(r"^\d{4,8}$", otp):
                return self._json(400, {"error": "OTP is 4-8 digits."})
            return reply(provisioner.submit_otp(otp))

        if path == "/api/call":
            number = str(data.get("number", "")).replace(" ", "").replace("-", "")
            if not NUMBER_RE.match(number):
                return self._json(400, {"error": "Enter 6-15 digits, optionally starting with +."})
            return reply(start_call(number), bad_code=409)

        if path == "/api/hangup":
            return reply(with_active(lambda s: (s.end_call("Hang up pressed"), "Hanging up...")))

        if path == "/api/answer":
            def ans(s):
                if s.state != "ringing":
                    return False, "Nothing is ringing."
                return s.send_cmd("answer"), "Answering..."
            return reply(with_active(ans))

        if path == "/api/decline":
            def dec(s):
                if s.state != "ringing":
                    return False, "Nothing is ringing."
                return s.decline(), "Declining..."
            return reply(with_active(dec))

        if path == "/api/test-incoming" and audio_mode() == "mock":     # mock mode only
            s = current
            ok = bool(s and s.kind == "listen" and s.alive() and s.send_cmd("ring " + str(data.get("number", "9812345678"))))
            return self._json(200 if ok else 409, {"ok": ok, "message": "ringing" if ok else "standby line not ready"})

        if path == "/api/hold":
            hold = bool(data.get("hold", True))
            return reply(with_active(lambda s: (s.send_cmd("hold" if hold else "unhold"),
                                                "Holding..." if hold else "Resuming...")))

        if path == "/api/dtmf":
            digits = str(data.get("digits", ""))
            if not DIGITS_RE.match(digits):
                return self._json(400, {"error": "digits must be 0-9, * or #"})
            return reply(with_active(lambda s: (s.send_cmd("dtmf " + digits), "Sent")))

        if path == "/api/transfer":
            number = str(data.get("number", "")).replace(" ", "").replace("-", "")
            if not NUMBER_RE.match(number):
                return self._json(400, {"error": "Enter 6-15 digits, optionally starting with +."})
            return reply(with_active(lambda s: (s.send_cmd("transfer " + number), "Transferring...")))

        self._json(404, {"error": "not found"})

    def _audio_ws(self):
        s = current
        key = self.headers.get("Sec-WebSocket-Key")
        if not key or "websocket" not in self.headers.get("Upgrade", "").lower():
            return self._json(400, {"error": "WebSocket upgrade required"})
        if not self._same_origin():
            return self._json(403, {"error": "cross-origin request refused"})
        if s is None or s.bridge is None or s.state not in ACTIVE or not s.alive():
            return self._json(409, {"error": "no active call that uses browser audio"})
        accept = base64.b64encode(hashlib.sha1((key + WS_GUID).encode()).digest()).decode()
        self.send_response(101, "Switching Protocols")
        self.send_header("Upgrade", "websocket")
        self.send_header("Connection", "Upgrade")
        self.send_header("Sec-WebSocket-Accept", accept)
        self.end_headers()
        self.close_connection = True
        self.connection.settimeout(None)
        try:
            self.connection.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        except OSError:
            pass
        ws = WS(self.connection, self.rfile)
        bridge = s.bridge
        bridge.attach(ws)
        s.add_log("[server] browser audio connected")
        try:
            while True:
                op, payload = ws.recv()
                if op == 0x2:
                    bridge.feed(payload)
                elif op == 0x8:
                    break
                elif op == 0x9:
                    ws.send(0xA, payload)
        except (EOFError, OSError, ValueError):
            pass
        finally:
            bridge.detach(ws)
            ws.closed = True
            # Browser tab closed / refreshed mid-call: don't leave a call running with no audio.
            if s is current and s.state in ("calling", "in-call", "on-hold") and not s.hung_up:
                s.end_call("browser audio connection closed")

    def log_message(self, fmt, *args):
        pass  # keep the console quiet


if __name__ == "__main__":
    if audio_mode() == "browser":
        PulseBridge.cleanup_stale() if not PulseBridge.missing_tools() else None
    if audio_mode() != "mock":
        if jio_provision.needs_login():
            print("credentials    : none yet -> open the page and log in once (OTP)")
            threading.Thread(target=provisioner.refresh, args=("first start",), daemon=True).start()
        else:
            print("credentials    : found (they are refreshed automatically if Jio changes them)")
    atexit.register(shutdown_all)
    threading.Thread(target=standby_manager, daemon=True).start()
    signal.signal(signal.SIGTERM, shutdown_all)
    print(f"index.html     : {INDEX_HTML} ({'ok' if INDEX_HTML.exists() else 'MISSING'})")
    print(f"jio-sip-client : {SIP_CLIENT} ({'ok' if SIP_CLIENT.exists() else 'MISSING'})")
    mode = audio_mode()
    print(f"audio          : {mode}")
    if mode == "browser" and PulseBridge.missing_tools():
        print("WARNING: missing " + ", ".join(PulseBridge.missing_tools())
              + "  ->  sudo apt install pulseaudio-utils")
    print(f"incoming calls : {'ON (standby line kept registered)' if load_config().get('listen', True) else 'off'}")
    print(f"Open http://{'localhost' if HOST == '127.0.0.1' else HOST}:{PORT}")
    try:
        ThreadingHTTPServer((HOST, PORT), Handler).serve_forever()
    except KeyboardInterrupt:
        pass