#!/usr/bin/env python3
"""
start.py  --  the one command:   python3 start.py        (Windows: python start.py)

It detects the machine it is running on and does the right thing:

  Linux, Debian / Ubuntu   builds the SIP engine with apt + setup.sh; browser mic/speakers.
  macOS (Intel / Apple)    builds the SIP engine with Homebrew + setup.sh; uses this Mac's own mic/speakers.
  Windows                  runs everything inside WSL2 (Ubuntu): checks WSL, offers to install it,
                           enables mirrored networking, then re-launches itself there.
  other Linux              tells you which packages to install; the rest works the same.

Then, on every OS:
  1. no config.json yet        -> creates it itself (built-in defaults)
  2. SIP engine not built yet  -> offers to build it (one time)
  3. no Jio credentials yet    -> logs in ONCE (finds the router by itself, sends an OTP, you type it),
                                  fetches the credentials and saves them
  4. starts the web server

After that you never log in again: if Jio changes the password / credentials, server.py notices
the registration failure, fetches fresh credentials from the router by itself (no OTP, no phone
number) and restarts the line. Only if that also fails does the page / terminal ask you to log in.

Options:  --relogin   force a fresh login now        (port / host:  PORT=8080 HOST=0.0.0.0 python3 start.py)
"""
import json, os, platform, re, shutil, subprocess, sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
os.chdir(HERE)
sys.path.insert(0, str(HERE))
import jio_provision as jp

SYSTEM = platform.system()          # "Linux" | "Darwin" | "Windows"


# --------------------------------------------------------------------------- what machine is this?
def os_release():
    """/etc/os-release as a dict (Linux only)."""
    info = {}
    try:
        for line in Path("/etc/os-release").read_text().splitlines():
            if "=" in line:
                k, v = line.split("=", 1)
                info[k] = v.strip().strip('"')
    except OSError:
        pass
    return info


def is_wsl():
    return SYSTEM == "Linux" and "microsoft" in platform.release().lower()


def describe_machine():
    if SYSTEM == "Linux":
        rel = os_release()
        name = rel.get("PRETTY_NAME", "Linux")
        return name + (" (inside WSL)" if is_wsl() else "")
    if SYSTEM == "Darwin":
        return f"macOS {platform.mac_ver()[0]} ({platform.machine()})"
    return f"{SYSTEM} {platform.release()}"


def is_debian_family():
    rel = os_release()
    return bool({"debian", "ubuntu"} & set((rel.get("ID", "") + " " + rel.get("ID_LIKE", "")).lower().split()))


def ask(prompt, default_yes=True):
    ans = input(prompt + (" [Y/n]: " if default_yes else " [y/N]: ")).strip().lower()
    return (ans in ("", "y", "yes")) if default_yes else (ans in ("y", "yes"))


# --------------------------------------------------------------------------- Windows -> WSL2
def wsl_distros():
    """Installed WSL distributions ('wsl -l -q' prints UTF-16)."""
    try:
        raw = subprocess.run(["wsl", "-l", "-q"], capture_output=True, timeout=20).stdout
    except Exception:
        return []
    text = raw.decode("utf-16-le", "ignore") if b"\x00" in raw else raw.decode("utf-8", "ignore")
    return [d.strip().lstrip("\ufeff") for d in text.splitlines() if d.strip()]


def ensure_mirrored_networking():
    """Incoming calls need WSL to share the PC's network address (Windows 11 22H2+)."""
    cfg = Path(os.environ.get("USERPROFILE", str(Path.home()))) / ".wslconfig"
    text = cfg.read_text() if cfg.exists() else ""
    if re.search(r"(?im)^\s*networkingMode\s*=\s*mirrored", text):
        return
    print("\nFor incoming calls and clear two-way audio, WSL should share this PC's network address")
    print("('mirrored' networking, needs Windows 11 22H2 or newer).")
    if not ask(f"Add it to {cfg} and restart WSL now?"):
        return
    if re.search(r"(?im)^\s*\[wsl2\]", text):
        text = re.sub(r"(?im)^(\s*\[wsl2\][^\n]*\n)", r"\1networkingMode=mirrored\n", text, count=1)
    else:
        text += ("\n" if text and not text.endswith("\n") else "") + "[wsl2]\nnetworkingMode=mirrored\n"
    cfg.write_text(text)
    subprocess.call(["wsl", "--shutdown"])
    print("Done. WSL will restart with the new setting.")


def run_in_wsl():
    print("Windows detected. The SIP engine is Linux software, so this runs inside WSL2 (Ubuntu).")
    if not shutil.which("wsl"):
        sys.exit("WSL is not available. Open PowerShell as Administrator, run:  wsl --install -d Ubuntu\n"
                 "restart the PC, then run  python start.py  again.")
    distros = wsl_distros()
    pick = next((d for d in distros if re.match(r"(?i)(ubuntu|debian)", d)), None)
    if not pick:
        sys.exit("No Ubuntu/Debian found in WSL. Open PowerShell as Administrator, run:\n"
                 "    wsl --install -d Ubuntu\n"
                 "restart the PC, finish the Ubuntu first-run setup, then run  python start.py  again.")
    ensure_mirrored_networking()
    try:
        linux_path = subprocess.check_output(
            ["wsl", "-d", pick, "--exec", "wslpath", "-a", HERE.as_posix()], timeout=30).decode().strip()
    except Exception as e:
        sys.exit(f"Could not translate the project path for WSL: {e}")
    print(f"Starting inside WSL ({pick}) at {linux_path}\n")
    sys.exit(subprocess.call(["wsl", "-d", pick, "--cd", linux_path, "--exec", "python3", "start.py"] + sys.argv[1:]))


# --------------------------------------------------------------------------- shared steps
def ensure_config():
    """No config file ships with the project; a blank one is created here from built-in defaults."""
    if not jp.CONFIG_PATH.exists():
        jp.CONFIG_PATH.write_text(json.dumps(jp.DEFAULT_CONFIG, indent=2))
        try:
            os.chmod(jp.CONFIG_PATH, 0o600)
        except OSError:
            pass
        print("Created a blank config.json (filled in after login).")


def ensure_pjsua():
    binary = jp.read_config().get("pjsua", {}).get("binary", "")
    if binary and (Path(binary).exists() or shutil.which(binary)):
        return
    print("\npjsua-jio (the SIP engine) is not installed yet.")
    if SYSTEM == "Linux" and not is_debian_family():
        print("This Linux is not Debian/Ubuntu-based, so setup.sh cannot install packages for you.")
        print("Install a C compiler, make, curl, pkg-config, OpenSSL + ALSA + uuid dev packages,")
        print("opencore-amr / vo-amrwbenc dev packages and pulseaudio-utils, then run:  bash setup.sh")
        return
    if SYSTEM == "Darwin" and not shutil.which("brew"):
        print("Homebrew is needed to build it on macOS. Install it from https://brew.sh , then run start.py again.")
        return
    how = "apt" if SYSTEM == "Linux" else "Homebrew"
    if ask(f"Build and install it now with setup.sh ({how}; needs sudo, takes a few minutes)?"):
        if subprocess.call(["bash", str(HERE / "setup.sh")]) != 0:
            sys.exit("setup.sh failed - fix the error above and run start.py again.")
    else:
        print("Continuing without it: real calls will not work until you run  bash setup.sh")


def login():
    """Interactive first-time login. Returns True when credentials were saved."""
    while True:
        p = jp.Provisioner()
        hint = jp.read_config().get("pjsua", {}).get("registrar", "")
        m = re.search(r"(\d+\.\d+\.\d+\.\d+)", hint)
        p.ip = jp.find_router_ip(m.group(1) if m else None)
        if not p.ip:
            p.ip = input("Router not found automatically. Enter the router IP (blank = quit): ").strip()
            if not p.ip:
                return False
        print(f"Router: {p.ip}")
        try:
            xml_text, status = jp.fetch_credentials(p.ip)
        except Exception as e:
            print(f"Could not reach the router: {e}")
            if not ask("Try again?"):
                return False
            continue
        if xml_text:
            p._apply(xml_text)
            print(p.message)
            return True
        print("First-time login: an OTP will be sent to the Jio number registered on this line.")
        if not ask("Send OTP now?"):
            return False
        ok, msg = p.start_otp()
        print(msg)
        if not ok:
            continue
        for _ in range(3):
            ok, msg = p.submit_otp(input("Enter OTP: ").strip())
            print(msg)
            if ok:
                return True
        print("OTP failed 3 times - starting over.")


def launch_server():
    server = str(HERE / "server.py")
    if SYSTEM == "Windows":                       # (not reached normally: Windows runs inside WSL)
        sys.exit(subprocess.call([sys.executable, server]))
    os.execv(sys.executable, [sys.executable, server])


def main():
    print(f"Machine: {describe_machine()}")
    if SYSTEM == "Windows":
        run_in_wsl()
    if SYSTEM not in ("Linux", "Darwin"):
        sys.exit(f"Unsupported system: {SYSTEM}")
    if is_wsl():
        print("(Running inside WSL: audio goes through the browser, as on Linux.)")
    ensure_config()
    ensure_pjsua()
    if "--relogin" in sys.argv or jp.needs_login():
        if not login():
            print("\nNo credentials. The server will still start; the web page will ask you to log in.")
    else:
        print("Credentials found - starting. (They refresh automatically if Jio changes them.)")
    launch_server()


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        pass