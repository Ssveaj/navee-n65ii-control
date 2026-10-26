# NAVEE N65i II Control — Firmware Modifications & Speed Unlocking

**Rocket Scooter** is an independent project developed for the NAVEE N65i II electric scooter.

## Important — read before using

Before modifying your scooter or changing its speed with the Rocket Scooter app, please read the
following carefully.

This project is intended for educational purposes and responsible testing on enclosed private
property that is closed to public access. Do **not** operate a speed-modified scooter on public
roads, bicycle paths, sidewalks, or other areas accessible to public traffic where prohibited.

## Safety information

- Modifying firmware may increase the scooter's maximum speed.
- Higher speeds increase braking distance and the risk of accidents.
- Installing incompatible firmware may permanently damage your scooter's controller.
- Modifications may void your warranty or affect insurance coverage.
- Always wear appropriate protective equipment.
- Never endanger pedestrians, other road users, or bystanders.
- You are responsible for complying with your local laws and regulations.

## How to get started

### Step 1 — Check your scooter

This app targets one exact configuration. Make sure your NAVEE N65i II matches:

| | Required |
|---|---|
| Model | **6001** (PID 2509 / pid5 25092) |
| Dashboard (meter) | **2.0.5.8** (`2058`) |
| Controller (BLDC) | **0.0.0.5** |

Connecting and reading/toggling works on any N65i II; the **firmware flash requires the exact
BLDC 0.0.0.5** above. Any other version is safely refused (it will not flash, so it cannot brick it).

### Step 2 — Get the APK

Rocket Scooter is an **Android** app. iOS (iPhone) is not supported. Two options:

**Option A — download the prebuilt APK (easiest)**
Grab the latest APK from the [Releases](../../releases) page, transfer it to your phone, enable
"install from unknown sources", install and launch. Done.

**Option B — build it yourself**
1. Download the source code from this repository (the stock controller image needed for flashing is
   included under `control/src/main/assets/firmware/`).
2. Install **Android Studio**, or use the Gradle wrapper directly. You need **JDK 17+** and **Android SDK 34**.
3. Build:
   ```
   gradlew.bat :control:assembleDebug      (Windows)
   ./gradlew :control:assembleDebug        (macOS / Linux)
   ```
   Output: `control/build/outputs/apk/debug/N65i-Control.apk`.
4. Transfer the APK to your phone, enable "install from unknown sources", install and launch.

### Step 3 — When you open the app

1. Turn on the scooter and make sure Bluetooth is on.
2. Tap **Scan & connect** — the app finds and connects to your N65i II (and remembers it next time).
3. Check the header: it should show **NAVEE N65i II**, dashboard **2.0.5.8 / BLDC 0.0.0.5**, battery and live speed.
4. **Settings** tab — toggle lights, lock, cruise, traction control, eco, etc., and adjust regen / start speed.
5. **Firmware** tab — set the **Turbo cap (20–40 km/h)**, run **Build + dry run** (offline check),
   then **Arm → Start** to flash.
6. When the flash reports **success**, power-cycle the scooter and wait 5–10 seconds before riding.

**Flash safely:**
- Make sure the battery is charged, phone close by, scooter parked with the drive wheel off the ground.
- **Do not power off the scooter or the phone while flashing.**
- If a flash goes wrong, use **Firmware → Recovery stock** to restore the original image.

## Sample
<img width="450" height="1000" alt="image" src="https://github.com/user-attachments/assets/5fadb98a-afba-4c43-9dcf-cee22f776969" /><img width="450" height="1000" alt="image" src="https://github.com/user-attachments/assets/bf02cc37-ed37-4950-82d7-f1197a4e1827" /> <img width="450" height="1000" alt="image" src="https://github.com/user-attachments/assets/1a83a7b9-7422-441b-a88c-665390522bfe" /> <img width="450" height="1000" alt="image" src="https://github.com/user-attachments/assets/d47d2267-2027-4d4e-a818-1a4485eb35b7" />



