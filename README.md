# Earphone Input Changer

Tiny root app to reroute USB-C audio on Android 14–17.

- **Output** — force media playback to the phone speaker or back to the earphone (`AudioSystem.setForceUse`, reversible, doesn't touch device state).
- **Microphone** — fake-disconnect the earphone mic so recordings and calls use the phone's builtin mic, and restore it later (`AudioService.setWiredDeviceConnectionState` with the device's real ALSA address).

Works on any AOSP-based ROM. Only needs root (Magisk, KernelSU, APatch — the su path is autodetected). No Shizuku, no priv-app module, no platform signature.

## Why root

`MODIFY_AUDIO_ROUTING` is a signature|privileged permission, so every audio
service call is checked against the calling UID. UID 0 is always allowed,
shell (uid 2000, what Shizuku uses) is not. The app shells out to `su` and
runs a helper through `app_process`, which talks to the audio services as
root.

## Usage

1. Install the APK from `apk/` (or build your own).
2. Open it, plug in your USB-C earphone.
3. Approve root on the first command.
4. Tap **Speaker** to move audio to the phone, **Earphone** to give it back.
5. Tap **Phone mic** to record with the builtin mic, **Earphone mic** to restore.

The device list at the top is captured automatically when the earphone is
plugged in and reused later, so switching works even after the framework
forgets the device.

Options:

- *Auto* — switch to the phone mic whenever a USB-C earphone is plugged in.
- *Advanced output* — use the device-state method for output instead of
  force-use, for ROMs that ignore it.

## Building

```sh
SDK=/path/to/sdk BT=/path/to/build-tools KS=my.keystore KS_ALIAS=me ./build.sh
```

Plain aapt2 + javac + d8 + apksigner, no gradle.

## Notes

- The helper prints everything to logcat under `usbroute` and writes results
  to the app's own external files dir, since app_process stdout doesn't
  reliably survive the su pipe.
- Restoring a faked-out device can fail on some ROMs if the framework has
  already dropped it; a physical replug always fixes it. No kernel-level
  tricks (usb authorized toggles etc.) are used anywhere — those can kill
  the shared ALSA card of USB-C headsets.

## License

MIT
