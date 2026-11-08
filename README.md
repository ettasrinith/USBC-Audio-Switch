# USBC Audio Switch

Tiny root app to reroute USB-C **and Bluetooth** audio on Android 14–17.

- **Output** — force media playback to the phone speaker or back to the earphone (`AudioSystem.setForceUse`, reversible, doesn't touch device state).
- **Microphone** — fake-disconnect the earphone mic (USB-C, BT-SCO and BLE headset) so recordings and calls use the phone's builtin mic, and restore it later (`AudioService.setWiredDeviceConnectionState` with the device's real address).
- **Bluetooth** — fake-disconnect a connected BT device (A2DP / BLE / SCO) so media falls back to the phone speaker, and restore it afterwards. Best-effort: the BT stack re-asserts its connection on some ROMs.
- **Root on open** — the app asks for superuser as soon as it opens (that's what pops the Magisk / KernelSU / APatch grant dialog) and shows a status chip; if root is denied an in-app dialog explains what to do and offers a retry.
- **Neat logs** — every event is timestamped and tagged (`app` / `root` / `helper`), mirrored to logcat under `usbroute`, kept in the on-screen log panel and written to `Android/data/com.lasm.usb/files/usbroute-log.txt`. **Clear** and **Share** buttons included.

Works on any AOSP-based ROM. Only needs root (Magisk, KernelSU, APatch — the su path is autodetected and cached). No Shizuku, no priv-app module, no platform signature.

## Why root

`MODIFY_AUDIO_ROUTING` is a signature|privileged permission, so every audio
service call is checked against the calling UID. UID 0 is always allowed,
shell (uid 2000, what Shizuku uses) is not. The app shells out to `su` and
runs a helper through `app_process`, which talks to the audio services as
root.

## No hardcoded addresses

Device addresses (BT MACs, USB port paths, ALSA `card=X;device=Y`) are **never
hardcoded** — they are captured live from `AudioDeviceInfo` whenever a device
is present, persisted, and passed to the helper base64-encoded for every
operation. The helper additionally refuses to touch a device type the app did
not report. The `0x…` constants in the source are framework device *type*
identifiers (speaker/USB/BT classes), not addresses.

## Usage

1. Install the APK (build your own — the one in `apk/` is an older release).
2. Open it and approve the superuser prompt (Magisk / KernelSU / APatch). The root chip in the header turns green once granted; tap it to re-check.
3. Plug in your USB-C earphone or connect a Bluetooth device — it appears under **Devices** automatically.
4. Tap **Speaker** to move audio to the phone, **Earphone** to give it back.
5. Tap **BT → phone speaker** to mute BT audio into the speaker, **Restore BT audio** to bring it back.
6. Tap **Phone mic** to record with the builtin mic, **Earphone mic** to restore.

Options:

- *Auto* — switch to the phone mic whenever a USB-C earphone is plugged in.
- *Advanced output* — use the device-state method for output instead of
  force-use, for ROMs that ignore it.

## Helper commands (root side)

```
out speaker|earphone [addrs]     # USB output via device-state
mic phone|earphone [addrs]       # USB + BT-SCO + BLE headset mics
bt speaker|restore [addrs]       # BT output fake-disconnect / restore
force speaker|headphones|none    # AudioSystem.setForceUse(FOR_MEDIA, …)
force-rec speaker|none           # AudioSystem.setForceUse(FOR_RECORD, …)
debug                            # dump policy manager + AudioService state
```

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
  already dropped it; a physical replug always fixes it (replug or BT
  reconnect). No kernel-level tricks (usb authorized toggles etc.) are used
  anywhere — those can kill the shared ALSA card of USB-C headsets.

## License

MIT

## Credits

- [Lesser AudioSwitch](https://play.google.com/store/apps/details?id=com.nordskog.LesserAudioSwitch) by Robert Nordskog — the original idea and the device fake-connect technique this app is based on.
- [LesserAudioSwitchAndroid11](https://github.com/michioxd/LesserAudioSwitchAndroid11) by michioxd — reference for porting the technique to modern Android via the audio service.
