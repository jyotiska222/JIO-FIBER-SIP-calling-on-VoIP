#!/usr/bin/env bash
# Builds pjsua 2.15.1 with the Jio-compatibility changes (MicroSIP-like Contact header and User-Agent),
# then points config.json at it.  Does NOT use apt's pjsua (it can't be patched).
#
#   1. Contact header:  +sip.instance="<00000000-...-0000XXXXXXXX>"
#      (no "urn:uuid:" prefix, UPPERCASE hex, sent on every Contact)   -> jfc-contact.patch
#   1b. Incoming calls: BYE / hold re-INVITE are routed through the router's proxy, exactly like
#      outgoing calls already are (stock pjsua forgets this for incoming calls) -> jfc-incoming-route.patch
#   2. User-Agent: MicroSIP/3.21.4  (pjsua has no command-line option for this)
#      Change it with   PJ_UA="..." bash setup.sh     or disable with   PJ_UA=none
#
# Run:  bash setup.sh   (start.py offers to run it for you)
set -e
cd "$(dirname "$0")"
PROJ="$(pwd)"
PATCH="$PROJ/jfc-contact.patch"
PATCH2="$PROJ/jfc-incoming-route.patch"
UA="${PJ_UA:-MicroSIP/3.21.4}"
SUDO=""; [ "$(id -u)" -ne 0 ] && SUDO="sudo"
[ -f "$PATCH" ] || { echo "missing $PATCH (keep it next to this script)"; exit 1; }
[ -f "$PATCH2" ] || { echo "missing $PATCH2 (keep it next to this script)"; exit 1; }

$SUDO apt-get update -y || true
$SUDO apt-get install -y build-essential curl pkg-config libssl-dev libasound2-dev uuid-dev \
    libopencore-amrnb-dev libopencore-amrwb-dev libvo-amrwbenc-dev pulseaudio-utils
# ^ pulseaudio-utils (pactl/pacat/parec) lets server.py connect your BROWSER microphone/speakers
#   to the call through virtual audio devices. Works on PipeWire systems too.
# ^ AMR-NB / AMR-WB codec libraries. Jio's core only accepts AMR/AMR-WB in the call's
#   SDP (confirmed by a real 488 "SDP not supported" otherwise). Stock pjproject ships
#   with AMR support OFF; ./configure only turns it on if these libraries are present
#   at build time, which is why they're installed here before building.

BUILD="$HOME/pjsua-jio-build"
rm -rf "$BUILD" && mkdir -p "$BUILD" && cd "$BUILD"
echo "== Downloading pjproject 2.15.1 =="
curl -fL https://codeload.github.com/pjsip/pjproject/tar.gz/refs/tags/2.15.1 -o pj.tgz
tar xzf pj.tgz
cd pjproject-2.15.1

echo "== Applying Contact/+sip.instance patch =="
patch -p1 < "$PATCH"

echo "== Applying incoming-call routing patch (fixes hold + immediate hang-up on INCOMING calls) =="
patch -p1 < "$PATCH2"

if [ "$UA" != "none" ]; then
  echo "== Setting User-Agent to: $UA =="
  python3 - "$UA" <<'PY'
import re, sys
p = "pjsip-apps/src/pjsua/pjsua_app_config.c"
s = open(p).read()
pat = r'pj_ansi_snprintf\(tmp, sizeof\(tmp\), "PJSUA[ /]v%s %s", pj_get_version\(\),\s*pj_get_sys_info\(\)->info\.ptr\);'
s2, n = re.subn(pat, lambda m: 'pj_ansi_snprintf(tmp, sizeof(tmp), "%s", "' + sys.argv[1] + '");', s)
assert n == 1, "User-Agent line not found in pjsua_app_config.c"
open(p, "w").write(s2)
PY
fi

echo "== Building (a few minutes) =="
./configure --disable-video --disable-sdl --disable-ffmpeg --disable-v4l2 --disable-libyuv \
            --disable-openh264 --disable-vpx --with-ssl
make dep
make -j"$(nproc)"

PJ="$(find pjsip-apps/bin -maxdepth 1 -name 'pjsua-*' ! -name '*.o' | head -n1)"
[ -n "$PJ" ] || { echo "pjsua binary not found after build"; exit 1; }
$SUDO install -m 755 "$PJ" /usr/local/bin/pjsua-jio

cd "$PROJ"
python3 - <<'PY'
import json
import jio_provision
try:
    c = json.load(open("config.json"))
except Exception:
    c = json.loads(json.dumps(jio_provision.DEFAULT_CONFIG))
c["pjsua"]["binary"] = "/usr/local/bin/pjsua-jio"
json.dump(c, open("config.json", "w"), indent=2)
print("config.json -> pjsua.binary = /usr/local/bin/pjsua-jio")
PY
echo "Done. Restart:  python3 server.py"