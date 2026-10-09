# Jio SIP web calling

Place and receive calls on your JioFiber/AirFiber SIP line from a web page.

    python3 start.py

First time only: builds the SIP engine (`setup.sh`), logs in with an OTP and saves the credentials
to `config.json`. After that just run the same command: if Jio changes your password/credentials the
server fetches new ones from the router by itself (no OTP). It only asks you to log in again if the
router no longer recognises this PC or the fetch fails.

- Open http://localhost:8080 (`HOST=0.0.0.0 PORT=8080 python3 start.py` for other devices on your LAN)
- `python3 start.py --relogin` forces a new login; `python3 jio_provision.py` refreshes credentials only
- `config.json` and `tls/` are not in the repo. They are created on your first login and are git-ignored - never commit them
- Needs Linux with `ip`, `openssl`, PulseAudio/PipeWire utils (installed by `setup.sh`)

## Shout-out and credits

This project stands on the work of the **[JFC Group](https://github.com/JFC-Group)** - thank you!
@JFC-Group

- [JFC-Group](https://github.com/JFC-Group) - the organisation that made Jio SIP on third-party clients possible
- [JFC-pjproject](https://github.com/JFC-Group/JFC-pjproject) - the Jio-compatible pjproject work; `setup.sh` and the `jfc-*.patch` files follow the same approach
- [JFC-microsip](https://github.com/JFC-Group/JFC-microsip) - the MicroSIP build whose behaviour this project mirrors (Contact header, User-Agent)
- JFC SIP Configuration Tool - its credential-fetching flow is merged into `jio_provision.py`, so you no longer have to run it by hand

## Disclaimer

Unofficial, community project. Not affiliated with or endorsed by Jio or the JFC Group.