#!/bin/bash
# End-to-end checks for boatcast. Usage: tests/smoke.sh [path-to-binary]
set -u
BIN=${1:-./target/release/boatcast}
cd "$(dirname "$0")/.."
PASS=0; FAIL=0
ok()   { echo "PASS  $1"; PASS=$((PASS+1)); }
bad()  { echo "FAIL  $1"; FAIL=$((FAIL+1)); }
check(){ if eval "$2"; then ok "$1"; else bad "$1"; fi; }
cleanup(){ kill $(jobs -p) 2>/dev/null; wait 2>/dev/null; }
trap cleanup EXIT

LANIP=$(hostname -I | awk '{print $1}')
$BIN --deck examples/demo-deck --port 18080 --pin 4321 --wifi-ssid "Hall WiFi" --wifi-pass 's3cret;pass' >/tmp/sc-host.log 2>&1 &
sleep 0.8
H=http://127.0.0.1:18080

# Pages, gzip, ETag
hdr=$(curl -s -D - -o /dev/null -H 'Accept-Encoding: gzip' $H/)
check "viewer served gzipped"      '[[ "$hdr" == *"Content-Encoding: gzip"* ]]'
etag=$(echo "$hdr" | awk -F': ' 'tolower($1)=="etag"{print $2}' | tr -d '\r')
code=$(curl -s -o /dev/null -w '%{http_code}' -H "If-None-Match: $etag" $H/)
check "unchanged viewer returns 304" '[[ "$code" == 304 ]]'
check "console page served"        '[[ $(curl -s -o /dev/null -w "%{http_code}" $H/console) == 200 ]]'
check "manifest + service worker"  '[[ $(curl -s $H/manifest.webmanifest) == *standalone* && $(curl -s $H/sw.js) == *respondWith* ]]'

# Deck and assets
deck=$(curl -s $H/deck.json)
img=$(echo "$deck" | python3 -c 'import sys,json;print([s["image"] for s in json.load(sys.stdin)["slides"] if "image" in s][0])')
check "deck.json hides speaker notes" '[[ "$deck" != *notes* ]]'
check "image has a content-hashed URL" '[[ "$img" =~ ^/a/[0-9a-f]{16}\.webp$ ]]'
ahdr=$(curl -s -D - -o /dev/null $H$img)
check "image cached as immutable"  '[[ "$ahdr" == *immutable* && "$ahdr" == *image/webp* ]]'
check "full deck (with notes) for localhost console" '[[ $(curl -s $H/api/deck-full) == *notes* ]]'

# Live stream and control
timeout 3 curl -sN $H/events > /tmp/sc-ev.txt &
sleep 0.4
curl -s -X POST $H/control -d '{"action":"next"}' >/dev/null
sleep 0.4
check "stream starts with retry + policy + snapshot" 'head -c 300 /tmp/sc-ev.txt | grep -q "^retry: 1000" && grep -q "event: policy" /tmp/sc-ev.txt && grep -q "\"seq\":0" /tmp/sc-ev.txt'
check "next reaches the stream"     'grep -q "\"slide\":1" /tmp/sc-ev.txt'

# Throttle: three clicks in quick succession -> first at once, the rest merged into one trailing update
sleep 0.3
timeout 2 curl -sN $H/events > /tmp/sc-th.txt &
sleep 0.3
for a in next prev next; do curl -s -X POST $H/control -d "{\"action\":\"$a\"}" >/dev/null; done
sleep 0.5
n=$(grep -c "^event: state" /tmp/sc-th.txt)
check "3 quick clicks -> snapshot + 2 events (leading + 1 trailing), got $n" '[[ "$n" == 3 ]]'

# Errors and auth
check "bad goto rejected (400)"     '[[ $(curl -s -o /dev/null -w "%{http_code}" -X POST $H/control -d "{\"action\":\"goto\",\"slide\":99}") == 400 ]]'
check "LAN control without PIN -> 403" '[[ $(curl -s -o /dev/null -w "%{http_code}" -X POST http://$LANIP:18080/control -d "{\"action\":\"next\"}") == 403 ]]'
check "LAN control with PIN -> 200" '[[ $(curl -s -o /dev/null -w "%{http_code}" -H "X-Pin: 4321" -X POST http://$LANIP:18080/control -d "{\"action\":\"prev\"}") == 200 ]]'
check "LAN deck-full without PIN -> 403" '[[ $(curl -s -o /dev/null -w "%{http_code}" http://$LANIP:18080/api/deck-full) == 403 ]]'

# QR codes
check "Wi-Fi QR svg"                '[[ $(curl -s $H/qr/wifi.svg) == *"<svg"* ]]'
check "page QR svg"                 '[[ $(curl -s $H/qr/url.svg) == *"<svg"* ]]'

# Typed slides replace the deck
v1=$(curl -s $H/status | python3 -c 'import sys,json;print(json.load(sys.stdin)["deck"])')
curl -s -X POST $H/control -d '{"action":"load_text","title":"Lyrics","text":"# Verse 1\nline a\nline b\n\n# Chorus\nline c"}' >/dev/null
st=$(curl -s $H/status)
check "load_text swaps the deck (2 slides, new version)" '[[ $(echo "$st" | python3 -c "import sys,json;d=json.load(sys.stdin);print(d[\"slides\"], d[\"deck\"]!=\"$v1\")") == "2 True" ]]'
curl -s -X POST $H/control -d '{"action":"reload"}' >/dev/null
check "reload restores the folder deck" '[[ $(curl -s $H/status | python3 -c "import sys,json;print(json.load(sys.stdin)[\"slides\"])") == 4 ]]'

# Relay mode: mirrors the deck, follows the host, refuses control
$BIN relay --upstream http://127.0.0.1:18080 --port 18081 >/tmp/sc-relay.log 2>&1 &
sleep 1.2
R=http://127.0.0.1:18081
check "relay serves the host's deck" '[[ "$(curl -s $R/deck.json)" == "$(curl -s $H/deck.json)" ]]'
check "relay serves mirrored image"  '[[ $(curl -s -o /dev/null -w "%{http_code}" $R$img) == 200 ]]'
timeout 2 curl -sN $R/events > /tmp/sc-rev.txt &
sleep 0.4
curl -s -X POST $H/control -d '{"action":"goto","slide":2}' >/dev/null
sleep 0.5
check "host change reaches relay viewers" 'grep -q "\"slide\":2" /tmp/sc-rev.txt'
check "relay refuses control (409)"  '[[ $(curl -s -o /dev/null -w "%{http_code}" -X POST $R/control -d "{\"action\":\"next\"}") == 409 ]]'
check "relay status shows upstream connected" '[[ $(curl -s $R/status) == *"\"connected\":true"* ]]'

# Captive portal answers
$BIN --port 18090 --captive >/tmp/sc-cap.log 2>&1 &
sleep 0.6
C=http://127.0.0.1:18090
p1=$(curl -s -o /dev/null -w '%{http_code}' -H 'Host: connectivitycheck.gstatic.com' $C/generate_204)
check "before joining: Android probe redirected (302)" '[[ "$p1" == 302 ]]'
timeout 1 curl -sN $C/events >/dev/null
p2=$(curl -s -o /dev/null -w '%{http_code}' -H 'Host: connectivitycheck.gstatic.com' $C/generate_204)
p3=$(curl -s -H 'Host: captive.apple.com' $C/hotspot-detect.html)
check "after joining: Android probe 204" '[[ "$p2" == 204 ]]'
check "after joining: Apple probe Success" '[[ "$p3" == *Success* ]]'
check "unknown site name -> portal redirect" '[[ $(curl -s -o /dev/null -w "%{http_code}" -H "Host: example.org" $C/anything) == 302 ]]'

echo "---- $PASS passed, $FAIL failed"
exit $FAIL
