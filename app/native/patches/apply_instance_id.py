#!/usr/bin/env python3
"""
Third Jio compatibility change (after jfc-contact.patch and jfc-incoming-route.patch).

jfc-contact.patch builds  +sip.instance="<00000000-0000-1000-8000-0000XXXXXXXX>"  where XXXXXXXX is a
hash of the machine's HOSTNAME (that is how the JioFiber provisioning tool derived it:
hostname hash, e.g. E25CE11F). On Android the hostname is always "localhost", which would give
a different ID than the one the router provisioned, and the router would reject the registration
as an unknown device.

This script lets the app choose the 8 hex digits: if the environment variable JFC_INSTANCE_ID
(8 hex chars) is set, it replaces the hostname hash. Usage:  apply_instance_id.py <pjproject-dir>
"""
import sys, re, pathlib

root = pathlib.Path(sys.argv[1])
p = root / "pjsip/src/pjsua-lib/pjsua_acc.c"
s = p.read_text()

if "JFC_INSTANCE_ID" in s:
    print("instance-id patch already applied"); sys.exit(0)

anchor = "pj_val_to_hex_digit_capital(((char*)&hval)[3], instprm + pos + 6);"
if anchor not in s:
    sys.exit("anchor not found - apply jfc-contact.patch first")

s = s.replace(anchor, anchor + """
        {   /* JFC-ANDROID: take the device id from the app instead of the hostname hash */
            const char *jfc_e = getenv("JFC_INSTANCE_ID");
            if (jfc_e && strlen(jfc_e) == 8)
                memcpy(instprm + pos, jfc_e, 8);
        }""", 1)

if "#include <stdlib.h>" not in s:
    s = s.replace('#include "pjsua_imp.h"', '#include "pjsua_imp.h"\n#include <stdlib.h>\n#include <string.h>', 1)

p.write_text(s)
print("instance-id patch applied")
