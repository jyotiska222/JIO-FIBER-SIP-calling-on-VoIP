# Jio SIP web calling (self-healing)

Place and receive calls on your JioFiber/AirFiber SIP line from a web page.

    python3 start.py

First time only: builds the SIP engine (`setup.sh`), logs in with an OTP and saves the credentials
to `config.json`. After that just run the same command: if Jio changes your password/credentials the
server fetches new ones from the router by itself (no OTP). It only asks you to log in again if the
router no longer recognises this PC or the fetch fails.

- Open http://localhost:8080 (`HOST=0.0.0.0 PORT=8080 python3 start.py` for other devices on your LAN)
- `python3 start.py --relogin` forces a new login; `python3 jio_provision.py` refreshes credentials only
- `config.json` and `tls/` do not exist in the repo; they are created on your first login and git-ignored locally and git-ignored - never commit them
- Needs Linux with `ip`, `openssl`, PulseAudio/PipeWire utils (installed by `setup.sh`)
