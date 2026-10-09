#!/usr/bin/env bash
#
# Builds libjiosip.so (pjsua + the Jio patches + AMR-WB + OpenSSL + our JNI glue) for Android
# and drops it into app/src/main/jniLibs/<abi>/.   Run this ONCE before building the APK.
#
#   export ANDROID_NDK_HOME=$HOME/Android/Sdk/ndk/<version>      (NDK r25 or newer)
#   ./native/build.sh                      # arm64-v8a + armeabi-v7a   (real phones)
#   ./native/build.sh arm64-v8a            # only one ABI
#   ./native/build.sh arm64-v8a x86_64     # add x86_64 for the emulator
#   ./native/build.sh --glue-only arm64-v8a   # only recompile jiosip.cpp (seconds) after changing it,
#                                             # re-using the pjproject/OpenSSL build from an earlier run
#
# Needs: bash, curl, tar, make, patch, python3, perl (OpenSSL), and a normal Linux/macOS host.
# Fetches: pjproject 2.15.1 + OpenSSL 3.0.x (GitHub), opencore-amr + vo-amrwbenc (archive.ubuntu.com).
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
WORK="$HERE/.work"
API=26
PJ_VER=2.15.1
OPENSSL_VER=3.0.15
AMR_URL=https://archive.ubuntu.com/ubuntu/pool/universe/o/opencore-amr/opencore-amr_0.1.6.orig.tar.gz
VOAMR_URL=https://archive.ubuntu.com/ubuntu/pool/universe/v/vo-amrwbenc/vo-amrwbenc_0.1.3.orig.tar.gz
PJ_URL=https://codeload.github.com/pjsip/pjproject/tar.gz/refs/tags/$PJ_VER
OPENSSL_URL=https://codeload.github.com/openssl/openssl/tar.gz/refs/tags/openssl-$OPENSSL_VER

NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
[ -n "$NDK" ] && [ -d "$NDK/toolchains/llvm/prebuilt" ] || {
  echo "Set ANDROID_NDK_HOME to your NDK folder (…/Android/Sdk/ndk/<version>)"; exit 1; }
case "$(uname -s)" in Linux) HOSTTAG=linux-x86_64;; Darwin) HOSTTAG=darwin-x86_64;; *) echo "unsupported host"; exit 1;; esac
TC="$NDK/toolchains/llvm/prebuilt/$HOSTTAG"
JOBS="$( (nproc || sysctl -n hw.ncpu) 2>/dev/null || echo 4)"

GLUE_ONLY=0; ARGS=()
for a in "$@"; do if [ "$a" = "--glue-only" ]; then GLUE_ONLY=1; else ARGS+=("$a"); fi; done
ABIS=("${ARGS[@]+"${ARGS[@]}"}"); [ ${#ABIS[@]} -gt 0 ] || ABIS=(arm64-v8a armeabi-v7a)
mkdir -p "$WORK/dl"

fetch() {  # url file
  [ -s "$WORK/dl/$2" ] || { echo "== downloading $2"; curl -fL --retry 3 -o "$WORK/dl/$2" "$1"; }
}
fetch "$PJ_URL"      pjproject-$PJ_VER.tar.gz
fetch "$OPENSSL_URL" openssl-$OPENSSL_VER.tar.gz
fetch "$AMR_URL"     opencore-amr.tar.gz
fetch "$VOAMR_URL"   vo-amrwbenc.tar.gz

build_abi() {
  local ABI="$1" TRIPLE CLANG OSSL
  case "$ABI" in
    arm64-v8a)   TRIPLE=aarch64-linux-android;  CLANG=aarch64-linux-android;     OSSL=android-arm64;;
    armeabi-v7a) TRIPLE=arm-linux-androideabi;  CLANG=armv7a-linux-androideabi;  OSSL=android-arm;;
    x86_64)      TRIPLE=x86_64-linux-android;   CLANG=x86_64-linux-android;      OSSL=android-x86_64;;
    x86)         TRIPLE=i686-linux-android;     CLANG=i686-linux-android;        OSSL=android-x86;;
    *) echo "unknown ABI $ABI"; exit 1;;
  esac
  local B="$WORK/$ABI" DEPS="$WORK/$ABI/deps"
  local CC="$TC/bin/${CLANG}${API}-clang" CXX="$TC/bin/${CLANG}${API}-clang++"
  local AR="$TC/bin/llvm-ar" RANLIB="$TC/bin/llvm-ranlib" STRIP="$TC/bin/llvm-strip"
  [ -x "$CC" ] || { echo "compiler not found: $CC"; exit 1; }
  local PJ="$B/pjproject-$PJ_VER"
  echo; echo "################  $ABI  ################"
  if [ "$GLUE_ONLY" = 1 ]; then
    [ -f "$PJ/build.mak" ] && [ -d "$DEPS/lib" ] || { echo "No earlier build found for $ABI: run once without --glue-only"; exit 1; }
  else
  rm -rf "$B"; mkdir -p "$B" "$DEPS"

  # ---- OpenSSL (TLS) --------------------------------------------------------------------
  echo "== OpenSSL for $ABI"
  tar xzf "$WORK/dl/openssl-$OPENSSL_VER.tar.gz" -C "$B"
  ( cd "$B/openssl-openssl-$OPENSSL_VER"
    export ANDROID_NDK_ROOT="$NDK" PATH="$TC/bin:$PATH"
    unset CC CXX AR RANLIB
    ./Configure "$OSSL" -D__ANDROID_API__=$API no-shared no-tests no-engine \
        --prefix="$DEPS" --libdir=lib >/dev/null
    make -j"$JOBS" build_libs >/dev/null
    make install_dev >/dev/null )

  # ---- AMR codecs (the Jio core only accepts AMR / AMR-WB) --------------------------------
  for pkg in opencore-amr vo-amrwbenc; do
    echo "== $pkg for $ABI"
    mkdir -p "$B/$pkg"; tar xzf "$WORK/dl/$pkg.tar.gz" -C "$B/$pkg" --strip-components=1
    ( cd "$B/$pkg"
      ./configure --host="$TRIPLE" --prefix="$DEPS" --disable-shared --enable-static \
          CC="$CC" CXX="$CXX" AR="$AR" RANLIB="$RANLIB" \
          CFLAGS="-fPIC -O2" CXXFLAGS="-fPIC -O2" >/dev/null
      make -j"$JOBS" >/dev/null
      make install >/dev/null )
  done

  # ---- pjproject + Jio patches ------------------------------------------------------------
  echo "== pjproject $PJ_VER for $ABI"
  tar xzf "$WORK/dl/pjproject-$PJ_VER.tar.gz" -C "$B"
  ( cd "$PJ"
    patch -p1 --no-backup-if-mismatch < "$HERE/patches/jfc-contact.patch"        >/dev/null
    patch -p1 --no-backup-if-mismatch < "$HERE/patches/jfc-incoming-route.patch" >/dev/null
    python3 "$HERE/patches/apply_instance_id.py" .
    cp "$HERE/config_site.h" pjlib/include/pj/config_site.h

    export TARGET_ABI="$ABI"
    ./configure --host="$TRIPLE" \
        CC="$CC" CXX="$CXX" AR="$AR" RANLIB="$RANLIB" \
        CFLAGS="-fPIC -O2" CXXFLAGS="-fPIC -O2" LDFLAGS="-L$DEPS/lib" \
        --with-ssl="$DEPS" --with-opencore-amr="$DEPS" --with-opencore-amrwbenc="$DEPS" \
        --disable-video --disable-sdl --disable-ffmpeg --disable-v4l2 --disable-libyuv \
        --disable-openh264 --disable-vpx --disable-opus --disable-libwebrtc \
        --disable-android-mediacodec --disable-darwin-ssl --disable-silk \
        --disable-gsm-codec --disable-ilbc-codec --disable-g7221-codec \
        > "$B/pj-configure.log" 2>&1 || { tail -30 "$B/pj-configure.log"; exit 1; }
    grep -q "AMR-WB support enabled" "$B/pj-configure.log" || {
        echo "ERROR: AMR-WB was not detected (see $B/pj-configure.log)"; exit 1; }
    grep -q "SSL support enabled" "$B/pj-configure.log" || {
        echo "ERROR: OpenSSL was not detected (see $B/pj-configure.log)"; exit 1; }
    make dep >/dev/null 2>&1
    make -j"$JOBS" lib >"$B/pj-make.log" 2>&1 || { tail -40 "$B/pj-make.log"; exit 1; } )

  fi   # end of the "not glue-only" part

  # ---- our JNI glue -> libjiosip.so ----------------------------------------------------------
  echo "== libjiosip.so for $ABI"
  local OUT="$ROOT/app/src/main/jniLibs/$ABI"; mkdir -p "$OUT"
  cat > "$B/glue.mk" <<MK
include $PJ/build.mak
all:
	$CXX -shared -fPIC -O2 -Wall -Wno-unused-parameter -I$DEPS/include -o $OUT/libjiosip.so $HERE/jiosip.cpp \\
	    \$(PJ_CXXFLAGS) \$(PJ_LDXXFLAGS) \$(PJ_LDXXLIBS) \\
	    -L$DEPS/lib -lssl -lcrypto -static-libstdc++ -llog -lOpenSLES -lm -Wl,--exclude-libs,ALL -Wl,-z,max-page-size=16384
MK
  make -s -f "$B/glue.mk"
  cp "$TC/sysroot/usr/lib/$TRIPLE/libc++_shared.so" "$OUT/"   # libjiosip.so needs the C++ runtime next to it
  "$STRIP" --strip-unneeded "$OUT/libjiosip.so"
  ls -la "$OUT/libjiosip.so"
}

for abi in "${ABIS[@]}"; do build_abi "$abi"; done
echo; echo "Done. Now build the APK:  ./gradlew assembleDebug"
