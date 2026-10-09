# JioFiber Calling for Android

Make and receive phone calls on your Android phone through your **JioFiber / AirFiber home router**
(the router's built-in Jio voice line), over Wi-Fi. The app has three tabs: **Call log · Dialer · Contacts**,
one-tap calling, a proper call screen, and rings like the phone's own dialer.

> **Unofficial.** Not affiliated with or endorsed by Reliance Jio. It talks to *your own* router with *your own*
> line. Use it only on a connection you own. Provided as is, with no warranty. See [Status](#status).

**Nothing personal is stored in this repository.** There is no phone number, password, key or certificate in
the code. The app gets everything it needs from *your* router when you log in, and keeps it only on *your* phone.

---

## What you need

| | |
|---|---|
| Router | A JioFiber / AirFiber router with the Jio voice line, and the phone connected to **its Wi-Fi** |
| Phone | Android 8.0 or newer, 64-bit ARM (almost every phone made since 2016) |
| Jio SIM | The Jio number the router's line belongs to, to receive the one-time **OTP SMS** at first login |
| A computer | Only to build the app (or use GitHub to build it for you; see Option A) |

The app only works while the phone is on the router's Wi-Fi. It does not work over mobile data.

---

## Install, Option A: let GitHub build it (no tools to install)

Easiest. You need a free GitHub account.

1. Create a new repository on GitHub and upload this whole folder to it (or fork this repo).
2. Open the repository's **Actions** tab, choose **Build APK**, click **Run workflow**.
   (If GitHub asks, click "I understand my workflows, enable them" first.)
3. Wait roughly 15–25 minutes the first time. When the run turns green, open it and download the artifact **JioFiberCalling-debug-apk**.
4. Unzip it: you get **app-debug.apk**. Copy it to your phone (USB, Drive, email to yourself...).
5. On the phone open the APK. Android will ask to allow **"Install unknown apps"** for the app you opened it from:
   allow it, then **Install**.
6. Continue at [First run](#first-run).

## Install, Option B: build it on your own computer

Works on **Linux** and **macOS**. On **Windows** use [WSL2](https://learn.microsoft.com/windows/wsl/install)
(Ubuntu) and run everything below inside it, or just use Option A.

### 1. One-time setup

Ubuntu / Debian:
```bash
sudo apt update
sudo apt install -y openjdk-17-jdk curl unzip make patch perl python3 tar adb
```
macOS (with [Homebrew](https://brew.sh)): `brew install openjdk@17 android-platform-tools`
(and make sure `make`, `patch`, `perl`, `python3` exist; Xcode command-line tools provide them).

Android command-line tools + NDK (Linux shown; get the right file for macOS from
<https://developer.android.com/studio#command-line-tools-only>):
```bash
mkdir -p ~/Android/cmdline-tools && cd ~/Android/cmdline-tools
curl -LO https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip -q commandlinetools-linux-*_latest.zip && mv cmdline-tools latest && rm commandlinetools-linux-*_latest.zip

export ANDROID_HOME=$HOME/Android
$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --sdk_root=$ANDROID_HOME --licenses
$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --sdk_root=$ANDROID_HOME \
    "platform-tools" "platforms;android-34" "build-tools;34.0.0" "ndk;26.1.10909125"
```

### 2. Build

From the project folder:
```bash
export ANDROID_HOME=$HOME/Android
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/26.1.10909125
echo "sdk.dir=$ANDROID_HOME" > local.properties

bash native/build.sh arm64-v8a     # builds the SIP engine: 10-15 minutes, needs internet
./gradlew assembleDebug            # builds the app (first run downloads Gradle)
```
The APK is at **`app/build/outputs/apk/debug/app-debug.apk`**.
(Use `bash native/build.sh arm64-v8a armeabi-v7a` if your phone is an old 32-bit one.)

### 3. Install on the phone

Turn on **Developer options → USB debugging** on the phone (Settings → About phone → tap *Build number* 7 times),
plug it in, accept the "Allow USB debugging" prompt, then:
```bash
adb devices                                                    # your phone should be listed as "device"
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
Or copy the APK to the phone and open it, as in Option A step 5.

---

## First run

1. Connect the phone to your **JioFiber Wi-Fi**.
2. Open **JioFiber Calling** and allow **Microphone**, **Contacts** and **Notifications**.
3. The **Jio login** page opens by itself the first time:
   1. tap **Send OTP**: Jio sends an SMS with a code to the number tied to your router;
   2. type the code and tap **Log in**.

   The app now has your line's credentials. The status pill at the top turns **Connected**.
   (If your router already knows the phone it logs in without an OTP.)
4. Accept the **"Keep the line alive"** prompt: turn off battery optimisation and allow full-screen call alerts,
   so incoming calls reach you when the screen is off. On Xiaomi / Oppo / Vivo / Realme / Samsung also set
   Battery → *No restrictions* and enable *Auto-start* for the app ([why](https://dontkillmyapp.com)).
5. Test: open **Dialer**, enter a number, tap the green button. Then ask someone to call your Jio number.

**You never have to log in again** unless the router forgets the phone. If Jio changes your password the app
notices (registration is rejected), fetches the new one from the router by itself, and carries on.

## Using it

* **Call log:** your history (kept on the phone). Tap a row to call back; long-press for more. *Missed* filter.
* **Dialer:** keypad with contact suggestions; the green button on an empty number redials the last one.
* **Contacts:** your phone contacts, synced automatically; search and fast-scroll; tap to call.
* **Call screen:** mute, keypad, speaker, hold. Hold the phone to your ear and the screen turns off; move it away and it comes back.
* **Ringing:** your normal ringtone and vibration, following the ringer mode and Do Not Disturb.
* **⋮ menu:** Jio login / refresh credentials, Settings, SIP log (for troubleshooting), sync contacts.

---

## Troubleshooting

| What you see | What to do |
|---|---|
| *This phone is not connected to Wi-Fi* | Join the JioFiber Wi-Fi, then tap **Fix login / refresh credentials**. |
| *Router not found* | You're probably on a different Wi-Fi. If it *is* the JioFiber one, type the router address on the login page (usually `192.168.31.1`). |
| *SIP library not found … libc++_shared.so* | The native build was done with an old script. Pull the latest code and run `bash native/build.sh arm64-v8a` again. |
| *Registration failed: 403* | Usually fixes itself within a minute (the app fetches fresh credentials). If not, tap the pill → **Fix login**. |
| OTP never arrives | The SMS goes to the Jio number the router line belongs to. Make sure that SIM can receive SMS, then **Send OTP** again. |
| Install fails with *INSTALL_FAILED_UPDATE_INCOMPATIBLE* | An earlier build signed with a different key is installed. `adb uninstall com.example.myapp`, then install again. |
| No incoming calls with the screen off | Battery optimisation / auto-start (step 4 above). Keep the phone on the router's Wi-Fi. |
| Something else | Tap the status pill → **SIP log** → **Copy**. The log can contain your number: remove it before sharing. |

Start completely fresh any time: `adb shell pm clear com.example.myapp` (forgets the login and the call log).

---

## Privacy and security

* The repo contains no credentials, keys or personal data. Build output (APKs, `.so`, `local.properties`, keystores) is git-ignored
  because it can embed paths and secrets from your machine. **Don't commit those.**
* On the phone, your Jio username/password are kept in the app's private storage (protected by Android's app sandbox, not additionally encrypted).
  The call log and a contacts cache are stored in the app's private database. Nothing is sent anywhere except to your own router.
* The TLS certificate the router connects back to is generated on your phone the first time the app starts.
* The router sees this phone as `android-xxxxxxxx` (derived from the phone's Android ID, so it survives reinstalling).
* Debug APKs are signed with the standard Android debug key from your own machine (or GitHub's runner).
  To sign a release build, create a git-ignored `keystore.properties` next to `settings.gradle`:
  ```
  storeFile=my-release-key.jks
  storePassword=...
  keyAlias=...
  keyPassword=...
  ```
  and run `./gradlew assembleRelease`.

## How it works (short)

* **SIP engine:** [PJSIP](https://www.pjsip.org) (pjsua) with two small compatibility patches for the Jio router
  (`native/patches/`), AMR-WB / AMR audio only, OpenSSL for TLS, wrapped by `native/jiosip.cpp` (JNI).
* **Login:** the app asks the router (HTTPS, port 8443) for the line's credentials; an unknown phone gets an OTP step.
  `JioProtocol.java` / `Provisioner.java` implement it, with a 60-second rate limit and a cap of 3 automatic retries.
* **Always on:** a foreground service keeps the line registered (CPU + Wi-Fi locks, restarts after reboot, follows Wi-Fi changes).

```
app/src/main/java/com/example/myapp/   the Android app (UI, call logic, login, service)
native/                                 build.sh, jiosip.cpp (JNI), config_site.h, patches/
.github/workflows/build-apk.yml         one-click cloud build
```

## Status

Early / experimental. Finding the router and fetching the line's credentials has been verified on a real JioFiber router;
voice calling is implemented but is still being validated on more phones and routers, so expect rough edges and
please report what you find (include the SIP log with your number removed). The router decides how many devices one
line may register.

Verification so far: the build steps in Option B have been used on one Linux machine. The GitHub workflow (Option A)
has not been run yet, so the first run may need a small fix. Please open an issue if it does.

## Licence and credits

This project builds on [PJSIP](https://github.com/pjsip/pjproject) (GPL-2.0-or-later, with a commercial option),
[OpenSSL](https://www.openssl.org) 3 (Apache-2.0) and the opencore-amr / vo-amrwbenc codecs (Apache-2.0), and its router
login is ported from a companion web app. PJSIP is GPL-2.0-or-later (or commercial), and an app that includes it is generally
distributed under GPL-compatible terms. **Before publishing, check the licences of these components and add a matching `LICENSE`
file** (this is not legal advice).
