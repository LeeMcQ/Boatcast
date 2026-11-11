# Boatcast field test

A test sheet to follow with real phones. Read it on your phone.

Nothing in Boatcast has been tested on real phones yet. This sheet is how that changes.

**Do not move to the next stage until the current one passes.** If a stage fails, write down what happened and stop.

## Before you start

1. Turn **mobile data off** on every phone.
2. Turn **Location on** for phones running the app (Android needs it to start or find a hotspot).
3. Turn **Bluetooth on** for phones using Join.
4. Any phone that had the page open before the update: **reload it once**.
5. For anything that fails, write down the **phone model and the Android or iOS version**.

## The delivery test

Every stage uses it. In the presenter console, find the **Delivery test** panel and press **Run test**.

- The host sends 20 numbered test signals, 1.5 seconds apart.
- They go down the same live connection the slides use.
- The slides on screen do not change.
- Every phone's page answers each signal.

The console then shows, for each phone:
- **Device type.**
- **Hops:** how many repeaters sit between the host and that phone.
- **Arrived:** how many of the 20 came back.
- **Typical** and **worst** time, in milliseconds (ms).
- **Reconnects:** how many times the page had to reconnect.

The times are "there and back". The real one-way delay is lower.

A verdict line turns **green** when nothing was lost and 95% of answers took 1 second or less.

Press **Copy results** to copy the table. Paste it into a note for each stage.

---

## Stage 1: one host, one phone

**You need**
- One Android phone running the Boatcast app (the host).
- One Android phone with a browser (the viewer).

**Steps**
1. On the host, open Boatcast and tap **Host**.
2. On the viewer, scan the **Wi-Fi code**, then the **page code**.
3. Check the viewer shows the first slide.
4. On the host console, change slides a few times. Check the viewer follows.
5. Find **Delivery test** and press **Run test**.
6. Wait about 30 seconds for all 20 signals.
7. Press **Copy results** and save them.

**Pass mark**
- All 20 arrive.
- Typical time under 500 ms.
- Worst time under 1 second (1,000 ms).

**Write down**

| Item | Result |
|---|---|
| Host phone model and Android version | |
| Viewer phone model and Android version | |
| Arrived (out of 20) | |
| Typical (ms) | |
| Worst (ms) | |
| Reconnects | |
| Verdict colour | |
| Pass or fail | |

---

## Stage 2: add an iPhone and a Windows laptop

**You need**
- The Stage 1 setup.
- An iPhone using Safari.
- A Windows laptop using Edge or Chrome.

**Steps**
1. On the iPhone and the laptop, join the host's Wi-Fi.
2. On the iPhone, open the page by scanning the **page code** with the camera. This keeps the address starting with `http://`.
3. If the iPhone asks about no internet, tap **Keep trying Wi-Fi** or **Use without internet**. Turn mobile data off if the page will not load.
4. On the laptop, open the page address shown on the host.
5. Check all three viewers show the same slide.
6. Press **Run test**. Copy the results.
7. On each device, lock the screen for 30 seconds. While it is locked, change the slide on the host twice.
8. Unlock each device. Do not reload.
9. Check each one shows the current slide within a few seconds.

**Pass mark**
- Same marks as Stage 1 for every device: all 20 arrive, typical under 500 ms, worst under 1 second.
- Every device catches up without reloading.

**Write down**

| Device | Arrived (of 20) | Typical (ms) | Worst (ms) | Reconnects | Caught up after lock? (seconds) |
|---|---|---|---|---|---|
| Android phone | | | | | |
| iPhone (model, iOS version) | | | | | |
| Windows laptop (browser) | | | | | |

---

## Stage 3: Windows laptop as the host

Windows often refuses to start its own Mobile Hotspot when the laptop has no internet to share. Its hotspot also takes only 8 devices. So here a phone makes an ordinary hotspot and the laptop joins it.

**You need**
- A Windows laptop with the Boatcast program.
- An Android phone to make the hotspot.
- Two more phones as viewers.

**Steps**
1. On the hotspot phone, turn on the ordinary hotspot. Write down its name and password.
2. On the laptop, join that hotspot.
3. On the laptop, double-click the Boatcast program.
4. The console opens in the browser. A `slides` folder appears next to the program. Sample slides show while it is empty.
5. When Windows asks about the firewall, tick **both Private and Public** networks, then allow access.
6. In the console, type the hotspot's **Wi-Fi name** and **password** into the Wi-Fi fields. Press **Show Wi-Fi code**.
7. On the two phones, scan the **Wi-Fi code**, then the **page code**.
8. Check both phones show the first slide.
9. Press **Run test**. Copy the results.

**Pass mark**
- Both phones answer the test.

**If no phone can open the page**

Write it down. Either that hotspot blocks device-to-device traffic, or the firewall is blocking. Note the model of the hotspot phone. Do not carry on to Stage 4.

Whether phones on a hotspot can reach each other depends on the phone that makes the hotspot. This stage is how you check it.

**Write down**

| Item | Result |
|---|---|
| Hotspot phone model and Android version | |
| Laptop and Windows version | |
| Firewall: both network types ticked? | |
| Phone 1 opened the page? | |
| Phone 2 opened the page? | |
| Phone 1: arrived / typical / worst | |
| Phone 2: arrived / typical / worst | |
| Pass or fail | |

---

## Stage 4: fill the hotspot

This finds the real limit for that host phone.

**You need**
- The Stage 1 host (or the Stage 3 setup).
- As many phones as you can gather.

**Steps**
1. Join phones one at a time. Open the page on each.
2. After each one, check the console's phone count went up by one.
3. Keep going until one phone is refused. That is the limit.
4. Write down how many phones were connected when the next one was refused.
5. Press **Run test** with all of them connected. Copy the results.

**Pass mark**
- Nothing lost.
- The number connected equals the number that answered.

**Write down**

| Item | Result |
|---|---|
| Host phone model and Android version | |
| Phones connected when the next was refused | |
| Phones connected (console) | |
| Phones that answered the test | |
| Typical (ms) | |
| Worst (ms) | |
| Pass or fail | |

---

## Stage 5: one repeater

Repeaters are Android app only. Keep this stage separate from the earlier ones: it is a bonus tier.

**You need**
- The host from Stage 1.
- A second Android phone with the app (the repeater).
- A third phone in a browser, to sit behind the repeater.

**Steps**
1. On the repeater, scan the host's **Wi-Fi code** to join it.
2. Turn **mobile data off** on the repeater.
3. Open Boatcast on the repeater and tap **Join**. Wait for it to start repeating.
4. On the third phone, scan the **repeater's** Wi-Fi code and page code.
5. Check the third phone shows the slides.
6. On the host console, press **Run test**. Copy the results.
7. Check the third phone shows **1 hop**.

**Pass mark**
- Nothing lost.
- Worst time under 2 seconds.
- The phone behind the repeater shows 1 hop.

**Write down**

| Item | Result |
|---|---|
| Host phone model and Android version | |
| Repeater phone model and Android version | |
| Phone behind it: model | |
| Hops shown | |
| Arrived (out of 20) | |
| Typical (ms) | |
| Worst (ms) | |
| Pass or fail | |

---

## Stage 6: failover and a 60-minute run

**You need**
- The Stage 5 setup.
- One extra Android phone with the app in Join mode, and one browser phone, both behind the repeater.

**Part A: switch the repeater off**
1. Note the time. Turn the repeater off.
2. Time how long the Android app phone takes to show slides again. It should move by itself.
3. Time the browser phone. It cannot move by itself. Scan another code (the host's, or another repeater's) and time how long until it shows slides.

**Part B: 60 minutes**
1. Turn the repeater back on and let everything settle.
2. Write down the battery % now for the host, the repeater and one viewer.
3. Press **Run test**. Copy the results.
4. Turn the screens off on the host and the repeater. Leave them for 60 minutes.
5. Turn the screens on. Check the slides still follow the host.
6. Write down the battery % again.
7. Press **Run test** again. Copy the results.

Battery has not been measured on real phones before. These numbers are the first.

**Pass mark**
- Both phones show slides again after the repeater goes off.
- Nothing lost in the end test.
- Write the times and battery numbers down, whatever they are.

**Write down**

| Item | Result |
|---|---|
| Time for the Android app phone to show slides again (seconds) | |
| Time for the browser phone, after scanning another code (seconds) | |
| Host battery: start % / end % | |
| Repeater battery: start % / end % | |
| Viewer battery: start % / end % (phone model) | |
| Start test: arrived / typical / worst | |
| End test: arrived / typical / worst | |
| Pass or fail | |

---

## What to send back

1. The copied results from each stage (use **Copy results**).
2. The filled-in tables.
3. The phone model and Android or iOS version for anything that failed.
