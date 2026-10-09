#!/usr/bin/env python3
"""
start.py  --  the one command:   python3 start.py

  1. no config.json yet        -> creates it itself (built-in defaults)
  2. pjsua-jio not built yet   -> offers to run setup.sh (one time)
  3. no Jio credentials yet    -> logs in ONCE (finds the router with `ip route`, sends an OTP,
                                  you type it), fetches the credentials and saves them
  4. starts the web server

After that you never log in again: if Jio changes the password / credentials, server.py notices
the registration failure, fetches fresh credentials from the router by itself (no OTP, no phone
number) and restarts the line. Only if that also fails does the page / terminal ask you to log in.

Options:  --relogin   force a fresh login now        (all other args are passed to server.py env:
          PORT=8080 HOST=0.0.0.0 python3 start.py)
"""
import json, os, shutil, subprocess, sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
os.chdir(HERE)
sys.path.insert(0, str(HERE))
import jio_provision as jp


def ensure_config():
    """No config file ships with the project; a blank one is created here from built-in defaults."""
    if not jp.CONFIG_PATH.exists():
        jp.CONFIG_PATH.write_text(json.dumps(jp.DEFAULT_CONFIG, indent=2))
        os.chmod(jp.CONFIG_PATH, 0o600)
        print("Created a blank config.json (filled in after login).")


def ensure_pjsua():
    binary = jp.read_config().get("pjsua", {}).get("binary", "")
    if binary and (Path(binary).exists() or shutil.which(binary)):
        return
    print("\npjsua-jio (the SIP engine) is not installed yet.")
    if input("Build and install it now with setup.sh? (needs sudo, takes a few minutes) [Y/n]: ").strip().lower() in ("", "y", "yes"):
        if subprocess.call(["bash", str(HERE / "setup.sh")]) != 0:
            sys.exit("setup.sh failed - fix the error above and run start.py again.")
    else:
        print("Continuing without it: real calls will not work until you run  bash setup.sh")


def login():
    """Interactive first-time login. Returns True when credentials were saved."""
    while True:
        p = jp.Provisioner()
        hint = jp.read_config().get("pjsua", {}).get("registrar", "")
        import re
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
            if input("Try again? [Y/n]: ").strip().lower() in ("n", "no"):
                return False
            continue
        if xml_text:
            p._apply(xml_text)
            print(p.message)
            return True
        print("First-time login: an OTP will be sent to the Jio number registered on this line.")
        if input("Send OTP now? [Y/n]: ").strip().lower() in ("n", "no"):
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


def main():
    ensure_config()
    ensure_pjsua()
    if "--relogin" in sys.argv or jp.needs_login():
        if not login():
            print("\nNo credentials. The server will still start; the web page will ask you to log in.")
    else:
        print("Credentials found - starting. (They refresh automatically if Jio changes them.)")
    os.execv(sys.executable, [sys.executable, str(HERE / "server.py")])


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        pass
