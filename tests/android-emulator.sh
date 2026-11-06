#!/usr/bin/env bash
# Runs in CI with an Android emulator attached (see .github/workflows/build.yml).
# Fails on: install error, server binary missing or broken on Android, relay not
# following the host, app crash on start or after tapping Host.
# Reports (without failing) whether the emulator could start a local-only hotspot.
set -u
APK=$1
BIN_FALLBACK=${2:-}
PKG=io.github.leemcq.boatcast

fail() {
  echo "FAIL: $*"
  adb logcat -d | grep -iE "boatcast|AndroidRuntime|FATAL" | tail -n 120
  exit 1
}
pass() { echo "PASS: $*"; }

adb install -r "$APK" || fail "install"
pass "APK installs"

# 1. The server binary is extracted from the APK and runs on Android (bionic).
LIBDIR=$(adb shell dumpsys package "$PKG" | tr -d '\r' | grep -m1 -o 'legacyNativeLibraryDir=[^ ]*' | cut -d= -f2)
ABI=$(adb shell getprop ro.product.cpu.abi | tr -d '\r')
BIN="$LIBDIR/$ABI/libboatcast.so"
echo "native lib dir: $LIBDIR  abi: $ABI"
if adb shell ls -l "$BIN" 2>/dev/null | grep -q libboatcast; then
  pass "server binary extracted to $BIN"
else
  adb shell ls -lR "$LIBDIR" 2>&1 | head -20
  fail "server binary not extracted"
fi
if ! adb shell "$BIN" -h 2>/dev/null | grep -q "boatcast"; then
  echo "shell cannot exec from the app's lib dir; using the pushed copy"
  [ -n "$BIN_FALLBACK" ] || fail "no fallback binary"
  adb push "$BIN_FALLBACK" /data/local/tmp/boatcast >/dev/null
  adb shell chmod 755 /data/local/tmp/boatcast
  BIN=/data/local/tmp/boatcast
fi
adb shell "$BIN" -h | head -2 | grep -q boatcast || fail "server binary does not run on Android"
pass "server binary runs on Android"

# 2. Host and repeater (relay) on the device.
adb shell "$BIN" --port 18080 --host-ip 127.0.0.1 >/tmp/host.log 2>&1 &
sleep 1
adb shell "$BIN" relay --upstream http://127.0.0.1:18080 --port 18081 --host-ip 127.0.0.1 >/tmp/relay.log 2>&1 &
sleep 3
adb forward tcp:18080 tcp:18080 >/dev/null
adb forward tcp:18081 tcp:18081 >/dev/null
curl -s localhost:18081/status >/tmp/r1.json
cat /tmp/r1.json; echo
grep -q '"connected":true' /tmp/r1.json || { cat /tmp/host.log /tmp/relay.log; fail "relay did not connect to the host"; }
pass "relay follows the host on Android"
before=$(grep -o '"seq":[0-9]*' /tmp/r1.json)
curl -s -X POST localhost:18080/control -d '{"action":"next"}' >/dev/null
sleep 1
after=$(curl -s localhost:18081/status | grep -o '"seq":[0-9]*')
echo "relay seq $before -> $after"
[ "$before" != "$after" ] || fail "slide change did not reach the relay"
pass "slide change reaches the relay"
adb shell pkill -f libboatcast.so 2>/dev/null; adb shell pkill -f /data/local/tmp/boatcast 2>/dev/null

# 3. The app starts, shows its buttons, and survives tapping Host.
adb shell pm grant "$PKG" android.permission.ACCESS_FINE_LOCATION
adb shell pm grant "$PKG" android.permission.ACCESS_COARSE_LOCATION
adb shell cmd location set-location-enabled true 2>/dev/null || adb shell settings put secure location_mode 3
adb logcat -c
adb shell am start -W -n "$PKG/.MainActivity" >/dev/null || fail "launch"
sleep 4
adb shell pidof "$PKG" >/dev/null || fail "app died on start"
adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
adb pull /sdcard/ui.xml /tmp/ui.xml >/dev/null 2>&1
for b in Host Repeater "Pick slides" Stop; do
  grep -q "text=\"$b\"" /tmp/ui.xml || fail "button '$b' missing"
done
pass "app starts with Host, Repeater, Pick slides, Stop"

read -r x1 y1 x2 y2 < <(grep -o 'text="Host"[^>]*' /tmp/ui.xml | grep -o 'bounds="[^"]*"' | head -1 | tr -c '0-9\n' ' ')
adb shell input tap $(( (x1 + x2) / 2 )) $(( (y1 + y2) / 2 ))
sleep 10
adb shell pidof "$PKG" >/dev/null || fail "app died after tapping Host"
if adb logcat -d | grep -A12 "FATAL EXCEPTION" | grep -q "$PKG"; then fail "crash in the app"; fi
pass "app survives tapping Host"

adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
adb pull /sdcard/ui.xml /tmp/ui2.xml >/dev/null 2>&1
echo "---- status line after Host:"
grep -o 'text="[^"]*"' /tmp/ui2.xml | head -3
echo "---- server log:"
adb logcat -d -s boatcast | tail -n 20
adb forward tcp:18090 tcp:8080 >/dev/null
if curl -s -m 3 localhost:18090/status >/tmp/app.json && grep -q boatcast /tmp/app.json; then
  echo "INFO: the emulator started a hotspot; app server status:"
  cat /tmp/app.json; echo
else
  echo "INFO: no app server on :8080 (the emulator may not support local-only hotspots; see status above)"
fi
echo "---- all hard checks passed"
