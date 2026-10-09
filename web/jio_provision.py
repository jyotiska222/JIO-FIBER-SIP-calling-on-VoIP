#!/usr/bin/env python3
"""
jio_provision.py -- the JFC SIP Configuration Tool, merged into the web-call project.
Standard library only (no `requests`).

What it does
  * finds the router by itself (`ip route` default gateway, then jiofiber.local.html, then the
    IP already in config.json)
  * asks the router for fresh SIP credentials (username / password / domain)
  * first ever time (device not known to the router) -> OTP login, driven from the web page
  * every later time -> NO OTP, NO phone number: just the router IP (device is already registered)
  * writes the new credentials into config.json (atomic, with config.json.bak backup)

server.py calls Provisioner.on_problem() whenever the SIP client reports a registration /
password failure, so the system repairs itself and the line comes back by itself.

CLI:  python3 jio_provision.py            (refresh now; asks for OTP in the terminal if needed)
      python3 jio_provision.py --ip <router-ip>
"""
import http.client, json, os, re, shutil, socket, ssl, subprocess, sys, threading, time
import urllib.parse
import xml.etree.ElementTree as ET
from pathlib import Path

HERE = Path(__file__).resolve().parent
CONFIG_PATH = HERE / "config.json"
if not CONFIG_PATH.exists() and (HERE / "sip" / "config.json").exists():
    CONFIG_PATH = HERE / "sip" / "config.json"

PORT_HTTPS = 8443          # router provisioning port
PORT_SIP = 5068            # router SIP proxy port
FALLBACK_HOST = "jiofiber.local.html"
MIN_GAP = 60               # seconds between automatic refresh attempts
MAX_AUTO_FAILS = 3         # then stop and wait for the user (button on the page)
PLACEHOLDERS = ("PASSWORD", "", None)

# No config file ships with the project: it is created from these generic defaults at first login.
DEFAULT_CONFIG = {
    "mode": "pjsua",
    "listen": True,
    "pjsua": {
        "binary": "/usr/local/bin/pjsua-jio",
        "id": "", "registrar": "", "proxy": "", "realm": "*",
        "username": "", "password": "", "domain_for_calls": "",
        "use_tls": True, "number_prefix": "0", "user_phone": True,
        "audio": "browser", "max_call_seconds": 0,
        "disable_codecs": ["speex", "iLBC", "GSM", "G722", "PCMU", "PCMA", "opus"],
    },
}

_CTX = ssl.create_default_context()
_CTX.check_hostname = False
_CTX.verify_mode = ssl.CERT_NONE        # the router uses a self-signed certificate


# --------------------------------------------------------------------------- identity (same as JFC tool)
def _hash(s):
    h = 0
    for b in s.encode():
        h = (h * 33 + b) & 0xFFFFFFFF
    return h


def device_mac():
    """MAC derived from the hostname exactly like the JFC tool, so the router recognises us again."""
    hx = "{:08X}".format(_hash(socket.gethostname()))
    hx = "".join(reversed([hx[i:i + 2] for i in range(0, 8, 2)]))
    hx = hx.zfill(12).lower()
    return ":".join(hx[i:i + 2] for i in range(0, 12, 2))


# --------------------------------------------------------------------------- router discovery
def _port_open(host, port, timeout=2.0):
    try:
        with socket.create_connection((host, port), timeout=timeout):
            return True
    except OSError:
        return False


def find_router_ip(cfg_hint=None):
    """Default gateway from `ip route`; falls back to jiofiber.local.html, then the old IP."""
    cands = []
    try:
        out = subprocess.run(["ip", "route"], capture_output=True, text=True, timeout=5).stdout
        cands += re.findall(r"^default via (\d+\.\d+\.\d+\.\d+)", out, re.M)
        cands += [m for m in re.findall(r"via (\d+\.\d+\.\d+\.\d+)", out) if m not in cands]
    except Exception:
        pass
    try:
        cands.append(socket.gethostbyname(FALLBACK_HOST))
    except OSError:
        pass
    if cfg_hint:
        cands.append(cfg_hint)
    for ip in dict.fromkeys(cands):
        if _port_open(ip, PORT_HTTPS):
            return ip
    return None


# --------------------------------------------------------------------------- router requests
def _params(host_name, mac, eth, add=False):
    p = [("terminal_sw_version", "RCSAndrd"), ("terminal_vendor", host_name), ("terminal_model", host_name),
         ("SMS_port", 0), ("act_type", "volatile"), ("IMSI", ""), ("msisdn", ""), ("IMEI", ""), ("vers", 0),
         ("token", ""), ("rcs_state", 0), ("rcs_version", "5.1B"), ("rcs_profile", "joyn_blackbird"),
         ("client_vendor", "JUIC"), ("default_sms_app", 2), ("default_vvm_app", 0), ("device_type", "vvm"),
         ("client_version", "JSEAndrd-1.0"), ("mac_address", mac), ("alias", host_name),
         ("nwk_intf", "eth" if eth else "wifi")]
    if add:
        p.append(("op_type", "add"))
    return p


def _get(ip, query, cookie=None, raw_query=False):
    """HTTPS GET to the router. raw_query=True sends the query un-encoded (the JFC 'add device' request)."""
    qs = query if raw_query else urllib.parse.urlencode(query)
    conn = http.client.HTTPSConnection(ip, PORT_HTTPS, context=_CTX, timeout=15)
    try:
        hdr = {"Connection": "close"}
        if cookie:
            hdr["Cookie"] = cookie
        conn.request("GET", "/?" + qs, headers=hdr)
        r = conn.getresponse()
        return r.status, dict((k.lower(), v) for k, v in r.getheaders()), r.read().decode(errors="replace")
    finally:
        conn.close()


def fetch_credentials(ip):
    """Ask for the provisioning XML. Returns (xml_text | None, last_status). No OTP involved."""
    host, mac, status = socket.gethostname(), device_mac(), 0
    for eth in (True, False):                       # registered devices answer on "eth" (JFC --no-otp)
        status, _, body = _get(ip, _params(host, mac, eth))
        if status == 200 and "<parm" in body:
            return body, 200
    return None, status                             # 407 = this device is not registered yet


def send_otp(ip):
    """Register this device -> the router/Jio sends an OTP SMS. Returns (cookie, masked phone)."""
    host, mac = socket.gethostname(), device_mac()
    q = "&".join(f"{k}={v}" for k, v in _params(host, mac, False, add=True))
    status, hdr, body = _get(ip, q, raw_query=True)
    if status != 200:
        raise RuntimeError(f"router refused to send an OTP (HTTP {status})")
    cookie = "; ".join(p for p in (hdr.get("set-cookie") or "").split("; ") if "=" in p)
    return cookie, hdr.get("x-amn", "")


def verify_otp(ip, cookie, otp):
    status, _, _ = _get(ip, [("OTP", otp)], cookie=cookie)
    return status == 200


# --------------------------------------------------------------------------- parse + write config
def parse_xml(text):
    vals = {}
    for p in ET.fromstring(text).findall(".//parm"):
        vals[p.attrib.get("name")] = p.attrib.get("value")
    need = ("username", "userpwd", "home_network_domain_name")
    if not all(vals.get(k) for k in need):
        raise RuntimeError("router answered but the credentials were missing from its reply")
    return vals


def read_config():
    try:
        return json.loads(CONFIG_PATH.read_text())
    except Exception:
        return {}


def write_credentials(vals, ip):
    cfg = read_config() or json.loads(json.dumps(DEFAULT_CONFIG))
    pj = cfg.setdefault("pjsua", {})
    user, dom = vals["username"], vals["home_network_domain_name"]
    old = (pj.get("username"), pj.get("password"), pj.get("registrar"))
    pj.update({
        "id": f"sip:+{user}",
        "registrar": f"sip:{ip}:{PORT_SIP}",
        "proxy": f"sip:{ip}:{PORT_SIP};transport=tls",
        "realm": "*",
        "username": user,
        "password": vals["userpwd"],
        "domain_for_calls": dom,
    })
    cfg["mode"] = "pjsua"
    new = (pj["username"], pj["password"], pj["registrar"])
    if CONFIG_PATH.exists():
        shutil.copy2(CONFIG_PATH, str(CONFIG_PATH) + ".bak")
    tmp = CONFIG_PATH.with_suffix(".tmp")
    tmp.write_text(json.dumps(cfg, indent=2))
    os.replace(tmp, CONFIG_PATH)
    try:
        os.chmod(CONFIG_PATH, 0o600)
    except OSError:
        pass
    return old != new


def needs_login():
    pj = read_config().get("pjsua", {})
    return pj.get("password") in PLACEHOLDERS or not pj.get("username")


# --------------------------------------------------------------------------- state machine for the web page
class Provisioner:
    """state: idle | working | need-otp | ok | failed   (shown on the web page)"""

    def __init__(self, on_updated=None):
        self.on_updated = on_updated       # called after new credentials were written
        self.state, self.message, self.phone = "idle", "", ""
        self.lock = threading.Lock()
        self.last_attempt, self.fails = 0.0, 0
        self.ip, self.cookie = None, None
        self.version = 0

    def snapshot(self):
        return {"state": self.state, "message": self.message, "phone": self.phone,
                "needs_login": needs_login(), "version": self.version}

    def _set(self, state, message="", phone=None):
        self.state, self.message = state, message
        if phone is not None:
            self.phone = phone
        self.version += 1

    # ---- entry points
    def on_problem(self, reason):
        """Called by server.py when registration / password fails. Rate limited, runs in background."""
        now = time.time()
        if self.state in ("working", "need-otp") or now - self.last_attempt < MIN_GAP:
            return False
        if self.fails >= MAX_AUTO_FAILS:
            return False
        threading.Thread(target=self.refresh, args=(reason,), daemon=True).start()
        return True

    def registered_ok(self):
        self.fails = 0

    def refresh(self, reason="manual"):
        """Silent refresh (no OTP). Falls back to need-otp if the router does not know this device."""
        if not self.lock.acquire(blocking=False):
            return
        try:
            self.last_attempt = time.time()
            self._set("working", "Looking for the router and fetching fresh Jio credentials...")
            hint = (read_config().get("pjsua", {}).get("registrar") or "")
            m = re.search(r"(\d+\.\d+\.\d+\.\d+)", hint)
            self.ip = find_router_ip(m.group(1) if m else None)
            if not self.ip:
                self.fails += 1
                return self._set("failed", "Router not found (is this PC on the JioFiber/AirFiber network?)")
            xml_text, status = fetch_credentials(self.ip)
            if xml_text:
                return self._apply(xml_text)
            if status in (407, 401, 403):
                self._set("need-otp", "First-time login: this device is not registered on the router yet.")
                return
            self.fails += 1
            self._set("failed", f"Router did not give credentials (HTTP {status}).")
        except Exception as e:
            self.fails += 1
            self._set("failed", f"Refresh failed: {e}")
        finally:
            self.lock.release()

    def start_otp(self):
        try:
            if not self.ip:
                self.ip = find_router_ip(None)
            if not self.ip:
                return False, "Router not found."
            self.cookie, phone = send_otp(self.ip)
            self._set("need-otp", "OTP sent. Enter it below.", phone=phone)
            return True, f"OTP sent to {phone}" if phone else "OTP sent"
        except Exception as e:
            return False, str(e)

    def submit_otp(self, otp):
        try:
            if not (self.ip and self.cookie):
                return False, "Press 'Send OTP' first."
            if not verify_otp(self.ip, self.cookie, otp):
                return False, "Wrong OTP. Try again."
            self._set("working", "OTP accepted, fetching credentials...")
            xml_text, status = fetch_credentials(self.ip)
            if not xml_text:
                return False, f"Registered, but the router still gave no credentials (HTTP {status})."
            self._apply(xml_text)
            return True, "Logged in"
        except Exception as e:
            self._set("need-otp", f"OTP step failed: {e}")
            return False, str(e)

    def _apply(self, xml_text):
        vals = parse_xml(xml_text)
        changed = write_credentials(vals, self.ip)
        self.fails = 0
        self._set("ok", "New credentials saved." if changed else "Credentials are already up to date.")
        if self.on_updated:
            self.on_updated(changed)


# --------------------------------------------------------------------------- command line
if __name__ == "__main__":
    ip = sys.argv[sys.argv.index("--ip") + 1] if "--ip" in sys.argv else None
    p = Provisioner()
    p.ip = ip or find_router_ip(None)
    if not p.ip:
        sys.exit("Router not found. Use:  python3 jio_provision.py --ip <router-ip>")
    print("Router:", p.ip)
    xml_text, status = fetch_credentials(p.ip)
    if not xml_text:
        print("This device is not registered yet -> sending OTP to your Jio number...")
        ok, msg = p.start_otp()
        print(msg)
        if not ok:
            sys.exit(1)
        for _ in range(3):
            ok, msg = p.submit_otp(input("OTP: ").strip())
            print(msg)
            if ok:
                sys.exit(0)
        sys.exit(1)
    p._apply(xml_text)
    print(p.message)
