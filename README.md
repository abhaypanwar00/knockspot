# Knockspot

**An allowlist for your Android hotspot. Unknown devices knock — you decide.**

Share your phone's hotspot with roommates once, and their phones remember it forever. Every time you turn the hotspot on, they connect automatically, take up the connection slots, and your own laptop gets left out. The usual fixes are changing the password or blocking and unblocking devices by hand, over and over.

Knockspot flips the default: **only your trusted devices join automatically.** Anyone else is turned away and *knocks* — you get a notification and choose:

> **Knock knock** — Rahul's phone wants to join your hotspot
> `Allow once` · `Always allow` · `Block`

"Allow once" lasts until the hotspot turns off, so there's nothing to undo afterwards.

When someone asks you for the hotspot, tap **Let someone in** first (in the app, on the notification, or as a Quick Settings tile). The next new device that connects gets in on its first try as a guest, and Knockspot tells you who joined.

No root needed. Knockspot uses [Shizuku](https://shizuku.rikka.app/) to reach Android's built-in hotspot allowlist, which phones support but don't show in Settings.

## Features

- **Allowlist mode** — only trusted devices and active guests can join.
- **Knock notifications** — unknown devices are blocked and you're asked what to do, with the app closed.
- **Let someone in** — open a 2-minute window so the next new device connects on the first try, then it closes automatically.
- **Real device names** — shows the name each device reports, like "Macbook", the same as your phone's hotspot settings.
- **Guest passes** — allow a device until the hotspot turns off, or for an hour. Passes end on their own.
- **Two-way sync with your phone's blocklist** — devices you block or unblock in your phone's hotspot settings show up in Knockspot, and the other way round.
- **Rename devices** — "Rahul's phone" instead of `ca:cf:53:c0:db:d4`.
- **Quick Settings tiles** — switch protection on or off, or let someone in, in one tap.
- **Device limit notice** — tells you when your hotspot's own connection limit turned someone away.
- **Keeps working when closed** — the allowlist lives in the system hotspot configuration, so Android enforces it even if Knockspot isn't running. Knockspot also re-applies it if the phone's Settings app switches it off.

## How it works

Since Android 11 the hotspot (`SoftApConfiguration`) has an allowlist mode: when `clientControlByUser` is on, only MAC addresses on the allowed list may associate, and every other device is rejected with a `onBlockedClientConnecting` callback. These are `@SystemApi`s reserved for the Settings app, guarded by the `NETWORK_SETTINGS` permission.

The shell user (what ADB runs as) holds `NETWORK_SETTINGS`. Shizuku runs a service as that user and lets apps make binder calls through it, so Knockspot can:

1. read and write the hotspot configuration through `IWifiManager` (allowlist mode, allowed list, blocked list), and
2. register a `SoftApCallback` to receive hotspot state, connected clients and knock events, and
3. register a `TetheringEventCallback` to read the hostname each client advertised over DHCP.

A turned-away device is disconnected the moment it associates; whether it retries is up to that device. That's why **Let someone in** briefly pauses allowlist mode instead: the newcomer connects normally, becomes a guest, and allowlist mode comes straight back on — at which point Android disconnects any other unknown device that slipped in.

```
hotspot event ─▶ HotspotController ─▶ Guard ─▶ Policy (pure Kotlin) ─▶ DeviceStore (DataStore/JSON)
                       ▲                                                       │
                       └──────────── apply allowlist / blocklist ◀─────────────┘
```

All access rules live in [`Policy.kt`](app/src/main/java/io/github/abhaypanwar00/knockspot/core/Policy.kt) as pure functions with [unit tests](app/src/test/java/io/github/abhaypanwar00/knockspot/core/PolicyTest.kt). The rest of the app only does I/O: read the system state, run it through the policy, write the result back.

## Requirements

- Android 11 or newer.
- [Shizuku](https://shizuku.rikka.app/) installed and running.
- A phone whose Wi-Fi hardware supports the hotspot allowlist (Knockspot tells you if yours doesn't).

| Phone | Android | Status |
|---|---|---|
| OnePlus Nord CE5 (CPH2717) | OxygenOS 16 / Android 16 | ✅ Allowlist and knock events work |

Tried it on another phone? Please [open an issue](../../issues) with the result so this table can grow.

## Install

1. Download the latest APK from [Releases](../../releases) and install it.
2. Install [Shizuku](https://shizuku.rikka.app/download/) and start it, either with **Wireless debugging** on the phone, or from a computer:
   ```bash
   adb shell '<the command shown in Shizuku under "Start by connecting to a computer">'
   ```
   Shizuku needs to be started again after every reboot.
3. Open Knockspot and follow the setup card: allow Shizuku, notifications, and background running.
4. Connect your own laptop/phone to the hotspot, then turn **Protection** on. Devices connected at that moment become trusted.

## Build

```bash
./gradlew testDebugUnitTest   # policy unit tests
./gradlew assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
```

Requires JDK 17+ and the Android SDK (platform 36).

## Limitations

- Android identifies devices by MAC address. Phones use a randomized MAC per network by default; it stays the same for your hotspot, but if someone resets their phone's network settings, it looks like a new device and knocks again.
- After a reboot, knocks aren't detected until Shizuku is started again. Protection itself stays on, because the allowlist is stored by the system.
- A device that was turned away has to connect again after you allow it — Android can't hold it in a waiting state. Use **Let someone in** before they connect to avoid this.
- While the door is open, the *first* new device to connect gets in. If a roommate's phone auto-joins first, block it from the notification and open the door again.
- Some phone makers may change how the hotspot behaves. If your phone's own hotspot settings switch the allowlist off, Knockspot switches it back within a minute while it's running.

## Credits

- [Shizuku](https://github.com/RikkaApps/Shizuku) by RikkaApps — privileged API access without root.
- [Delta](https://github.com/supershadoe/delta) by supershadoe — showed that hotspot settings can be managed through Shizuku.
- [AndroidHiddenApiBypass](https://github.com/LSPosed/AndroidHiddenApiBypass) by LSPosed.

## License

[MIT](LICENSE) © 2026 Abhay Panwar
