#!/usr/bin/env bash
# Stark-TV-OS validation suite.
#   tests/validate.sh                  static checks, then tests on the TV ($STARK_TV, or the last ./stark connect)
#   tests/validate.sh --static         static checks only: no TV needed (what CI runs)
#   tests/validate.sh --reboot         also reboot the TV and check the startup animation plays on boot
#   tests/validate.sh --restore-cycle  also run ./stark restore, check the TV is back to stock, then reinstall Stark
# The TV tests leave no trace: its settings are snapshotted before and compared after.
# No pipefail: `producer | grep -q` must judge by grep, which exits early and leaves the producer with SIGPIPE.
set -u
cd "$(dirname "$0")/.." || exit 1

PKG=io.github.starktvos
APK=StarkTV.apk
SHOTS=shots/validate
STATIC_ONLY=0 REBOOT=0 RESTORE_CYCLE=0
for a in "$@"; do
  case "$a" in
    --static) STATIC_ONLY=1 ;;
    --reboot) REBOOT=1 ;;
    --restore-cycle) RESTORE_CYCLE=1 ;;
    -h|--help) sed -n '2,7p' "$0"; exit 0 ;;
    *) echo "Unknown option: $a"; exit 2 ;;
  esac
done

passes=0 fails=0 warns=0
pass()    { passes=$((passes + 1)); printf '  \033[32mpass\033[0m %s\n' "$*"; }
fail()    { fails=$((fails + 1)); printf '  \033[31mFAIL\033[0m %s\n' "$*"; }
warn()    { warns=$((warns + 1)); printf '  \033[33mwarn\033[0m %s\n' "$*"; }
info()    { printf '  \033[2minfo\033[0m %s\n' "$*"; }
section() { printf '\n\033[36m%s\033[0m\n' "$*"; }
check()   { local d="$1"; shift; if "$@" >/dev/null 2>&1; then pass "$d"; else fail "$d"; fi; }

TMP=$(mktemp -d)
cleanup_fns=""
cleanup() { local f; for f in $cleanup_fns; do "$f"; done; rm -rf "$TMP"; }
trap cleanup EXIT

find_bt() {
  local d
  for d in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" /opt/homebrew/share/android-commandlinetools \
           /usr/local/share/android-commandlinetools "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
    if [ -n "$d" ] && [ -d "$d/build-tools" ]; then ls -d "$d"/build-tools/* | sort -V | tail -1; return 0; fi
  done
  return 1
}

# =====================================================================================================
section "Scripts"
for f in stark build.sh tests/validate.sh; do check "bash syntax: $f" bash -n "$f"; done
if command -v shellcheck >/dev/null; then
  if out=$(shellcheck -S warning stark build.sh tests/validate.sh 2>&1); then pass "shellcheck: no warnings"
  else fail "shellcheck found problems:"; echo "$out" | head -40; fi
else
  warn "shellcheck not installed (brew install shellcheck)"
fi

# Values that reach the TV's shell must be validated: feed the CLI hostile input and expect a refusal.
if out=$(STARK_TV='1.2.3.4;reboot' ./stark status 2>&1); then fail "stark accepted an injected TV address"
elif echo "$out" | grep -q "Not an IP address"; then pass "stark rejects injected TV addresses"
else fail "stark rejected the address for the wrong reason: $out"; fi

# =====================================================================================================
section "Build"
if ./build.sh > "$TMP/build.log" 2>&1; then pass "build ($(tail -1 "$TMP/build.log" | sed 's/^Built //'))"
else fail "build failed"; tail -20 "$TMP/build.log"; exit 1; fi
BT=$(find_bt) || { fail "Android build-tools not found"; exit 1; }

size_kb=$(du -k "$APK" | cut -f1)
if [ "$size_kb" -le 1024 ]; then pass "APK size ${size_kb} KB (budget 1024 KB)"; else fail "APK is ${size_kb} KB, over the 1 MB budget"; fi

# =====================================================================================================
section "Signing (codeguard: certificates and crypto)"
"$BT/apksigner" verify -v --print-certs "$APK" > "$TMP/sig.txt" 2>&1
check "APK signature verifies" grep -q "^Verifies" "$TMP/sig.txt"
check "APK Signature Scheme v2 used" grep -q "v2 scheme (APK Signature Scheme v2): true" "$TMP/sig.txt"
check "signing key is EC" grep -q "Signer #1 key algorithm: EC" "$TMP/sig.txt"
check "signing key is 256-bit (P-256)" grep -q "Signer #1 key size (bits): 256" "$TMP/sig.txt"
apk_fp=$(grep "Signer #1 certificate SHA-256 digest" "$TMP/sig.txt" | awk '{print $NF}')

KEYSTORE="${STARK_KEYSTORE:-.stark/release.keystore}"
PASS_FILE="${STARK_KEYSTORE_PASS_FILE:-.stark/keystore.pass}"
if [ -f "$KEYSTORE" ] && [ -f "$PASS_FILE" ]; then
  keytool -exportcert -rfc -alias stark -keystore "$KEYSTORE" -storepass:file "$PASS_FILE" > "$TMP/cert.pem" 2>/dev/null
  openssl x509 -in "$TMP/cert.pem" -noout -text > "$TMP/cert.txt" 2>/dev/null
  check "certificate signed with ecdsa-with-SHA256" grep -q "Signature Algorithm: ecdsa-with-SHA256" "$TMP/cert.txt"
  check "certificate key on prime256v1 (P-256)" grep -qE "ASN1 OID: prime256v1|NIST CURVE: P-256" "$TMP/cert.txt"
  if grep -qE "md5|sha1With" "$TMP/cert.txt"; then fail "certificate uses MD5/SHA-1"; else pass "no MD5/SHA-1 in certificate"; fi
  if openssl x509 -in "$TMP/cert.pem" -noout -checkend 0 >/dev/null; then pass "certificate not expired ($(openssl x509 -in "$TMP/cert.pem" -noout -enddate | cut -d= -f2))"
  else fail "certificate expired"; fi
  if openssl x509 -in "$TMP/cert.pem" -noout -checkend $((10 * 365 * 86400)) >/dev/null; then pass "certificate valid for 10+ more years"
  else warn "certificate expires within 10 years: plan a key rotation (APK Signature Scheme v3)"; fi
  not_before=$(openssl x509 -in "$TMP/cert.pem" -noout -startdate | cut -d= -f2)
  if [ "$(date -j -f '%b %e %T %Y %Z' "$not_before" +%s 2>/dev/null || date -d "$not_before" +%s)" -le "$(date +%s)" ]; then
    pass "certificate already valid (from $not_before)"
  else fail "certificate not valid until $not_before"; fi
  ks_fp=$(openssl x509 -in "$TMP/cert.pem" -noout -fingerprint -sha256 | cut -d= -f2 | tr -d ':' | tr 'A-F' 'a-f')
  if [ -n "$apk_fp" ] && [ "$ks_fp" = "$apk_fp" ]; then pass "APK signed with the project key"; else fail "APK signer doesn't match the keystore"; fi
  subj=$(openssl x509 -in "$TMP/cert.pem" -noout -subject | sed 's/^subject= *//')
  iss=$(openssl x509 -in "$TMP/cert.pem" -noout -issuer | sed 's/^issuer= *//')
  [ "$subj" = "$iss" ] && info "self-signed ($subj): normal for Android app signing, where the key itself is the identity"
  perms=$(stat -f '%Lp' "$PASS_FILE" 2>/dev/null || stat -c '%a' "$PASS_FILE")
  if [ "$perms" = 600 ] || [ "$perms" = 400 ]; then pass "keystore password file is private (mode $perms)"; else fail "keystore password file mode $perms (want 600)"; fi
else
  warn "no local keystore: certificate checks skipped (CI builds sign with their own key)"
fi

# =====================================================================================================
section "Manifest"
"$BT/aapt2" dump xmltree --file AndroidManifest.xml "$APK" > "$TMP/manifest.txt"
"$BT/aapt2" dump badging "$APK" > "$TMP/badging.txt" 2>/dev/null
# aapt2 prints booleans as true/false or as (type 0x12)0xffffffff/0x0 depending on its version.
if grep -qE "debuggable\(0x0101000f\)=(true|\(type 0x12\)0xffffffff)" "$TMP/manifest.txt"; then fail "APK is debuggable"; else pass "not debuggable"; fi
check "allowBackup=false" grep -qE "allowBackup\(0x01010280\)=(false|\(type 0x12\)0x0)$" "$TMP/manifest.txt"
check "usesCleartextTraffic=false" grep -qE "usesCleartextTraffic\(0x010104ec\)=(false|\(type 0x12\)0x0)$" "$TMP/manifest.txt"
check "minSdk 26" grep -q "sdkVersion:'26'" "$TMP/badging.txt"
check "targetSdk 34" grep -q "targetSdkVersion:'34'" "$TMP/badging.txt"

expected_perms="android.permission.ACCESS_NETWORK_STATE android.permission.ACCESS_WIFI_STATE android.permission.INTERNET
android.permission.KILL_BACKGROUND_PROCESSES android.permission.PACKAGE_USAGE_STATS android.permission.QUERY_ALL_PACKAGES
android.permission.REQUEST_DELETE_PACKAGES android.permission.WRITE_SECURE_SETTINGS"
actual=$(grep "^uses-permission:" "$TMP/badging.txt" | sed "s/.*name='\([^']*\)'.*/\1/" | sort | tr '\n' ' ' | sed 's/ $//')
want=$(echo "$expected_perms" | tr ' ' '\n' | grep . | sort | tr '\n' ' ' | sed 's/ $//')
if [ "$actual" = "$want" ]; then pass "permissions are exactly the reviewed set (8)"; else fail "permissions changed: $actual"; fi

# Every component: name, exported, permission. Only the reviewed ones may be reachable from other apps.
awk '
  function flush() { if (comp != "") print comp, name, exported, perm; comp = "" }
  {
    indent = match($0, /[^ ]/) - 1
    if ($1 == "E:") {
      if (comp != "" && indent <= cindent) flush()
      if ($2 ~ /^(activity|activity-alias|service|receiver|provider)$/) { comp = $2; cindent = indent; name = "?"; exported = "unset"; perm = "-" }
      next
    }
    if ($1 == "A:" && comp != "" && indent == cindent + 2) {
      if ($0 ~ /:name\(0x01010003\)=/) { n = $0; sub(/.*:name\(0x01010003\)="/, "", n); sub(/".*/, "", n); name = n }
      if ($0 ~ /:exported\(0x01010010\)=/) exported = ($0 ~ /=true$|0xffffffff$/) ? "true" : ($0 ~ /=false$|\)0x0$/) ? "false" : "unknown"
      if ($0 ~ /:permission\(0x01010006\)=/) { p = $0; sub(/.*:permission\(0x01010006\)="/, "", p); sub(/".*/, "", p); perm = p }
    }
  }
  END { flush() }' "$TMP/manifest.txt" > "$TMP/components.txt"
while read -r kind name exported perm; do
  case "$kind $name $exported $perm" in
    "activity .HomeActivity true -"|"activity .CenterActivity true -") pass "$kind $name: exported (launcher entry)" ;;
    "service .ClockDream true android.permission.BIND_DREAM_SERVICE") pass "$kind $name: only the system can bind" ;;
    "receiver .StateReceiver true android.permission.WRITE_SECURE_SETTINGS") pass "$kind $name: only ADB shell/system can send" ;;
    *" false "*) pass "$kind $name: not exported" ;;
    *) fail "unreviewed component: $kind $name exported=$exported permission=$perm" ;;
  esac
done < "$TMP/components.txt"
[ -s "$TMP/components.txt" ] || fail "no components parsed from the manifest"

# =====================================================================================================
section "Source (codeguard: credentials, crypto, insecure APIs)"
if git rev-parse --git-dir >/dev/null 2>&1; then
  git ls-files --cached --others --exclude-standard > "$TMP/files.txt"
  for f in .stark/keystore.pass .stark/release.keystore StarkTV.apk out/classes.dex shots/x.png; do
    check "git ignores $f" git check-ignore -q "$f"
  done
else
  find . -type f -not -path './.git/*' -not -path './out/*' -not -path './.stark/*' -not -path './shots/*' \
    -not -name '*.apk' -not -name '*.idsig' | sed 's|^\./||' > "$TMP/files.txt"
  warn "not a git repository: can't verify .gitignore"
fi
grep -vE '\.(mp4|ttf|png|jpg|webp)$|^tests/validate\.sh$' "$TMP/files.txt" > "$TMP/text-files.txt"
publish_secrets=$(tr '\n' '\0' < "$TMP/text-files.txt" | xargs -0 grep -nIE \
  -e 'AKIA[0-9A-Z]{16}' -e '(sk|pk)_(live|test)_[0-9A-Za-z]{10,}' -e 'AIza[0-9A-Za-z_-]{35}' \
  -e 'gh[pousr]_[0-9A-Za-z]{30,}' -e 'eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.' \
  -e '-----BEGIN [A-Z ]*PRIVATE KEY-----' -e '-----BEGIN CERTIFICATE-----' 2>/dev/null)
generic=$(tr '\n' '\0' < "$TMP/text-files.txt" | xargs -0 grep -nIiE \
  "(password|passwd|secret|api_?key|access_?token)[[:space:]]*[:=][[:space:]]*[\"'][^\"']{6,}" 2>/dev/null)
if [ -z "$publish_secrets$generic" ]; then pass "no credentials, keys or certificates in publishable files ($(wc -l < "$TMP/text-files.txt" | tr -d ' ') files)"
else fail "possible secrets:"; echo "$publish_secrets$generic" | head -20; fi

weak=$(grep -rnE '\b(MD5|MD4|SHA-?1|DESede|RC4|Blowfish)\b|AES/(ECB|CBC)|"DES"|sha1WithRSA|-keyalg RSA' app/src build.sh stark 2>/dev/null)
if [ -z "$weak" ]; then pass "no banned or deprecated crypto"; else fail "weak crypto:"; echo "$weak"; fi

risky=$(grep -rnE 'addJavascriptInterface|setJavaScriptEnabled|MODE_WORLD_(READABLE|WRITEABLE)|X509TrustManager|HostnameVerifier|ALLOW_ALL_HOSTNAME|Runtime\.getRuntime\(\)\.exec|ProcessBuilder|setAllowFileAccess|DexClassLoader|System\.loadLibrary' app/src 2>/dev/null)
if [ -z "$risky" ]; then pass "no risky APIs (WebView bridges, custom TLS trust, exec, dynamic code)"; else fail "risky APIs:"; echo "$risky"; fi

cleartext=$(grep -rnI 'http://' app/src app/res app/AndroidManifest.xml 2>/dev/null | grep -v 'schemas.android.com')
if [ -z "$cleartext" ]; then pass "no cleartext http:// URLs"; else fail "cleartext URLs:"; echo "$cleartext"; fi

check "font licence shipped with the font" test -f app/assets/fonts/OFL.txt

if [ $STATIC_ONLY = 1 ]; then
  section "Result"
  printf '  %d passed, %d failed, %d warnings\n' "$passes" "$fails" "$warns"
  [ $fails = 0 ]; exit
fi

# =====================================================================================================
section "TV"
TV="${STARK_TV:-}"
[ -z "$TV" ] && [ -f .stark/tv ] && TV=$(head -1 .stark/tv)
[ -z "$TV" ] && { warn "no TV configured (./stark connect <ip>): device tests skipped"; exit $((fails > 0)); }
[[ "$TV" == *:* ]] || TV="$TV:5555"
adb connect "$TV" >/dev/null 2>&1
if [ "$(adb -s "$TV" get-state 2>/dev/null)" != device ]; then fail "can't reach $TV"; exit 1; fi
pass "connected to $TV ($(adb -s "$TV" shell getprop ro.product.model | tr -d '\r'))"

tv()   { adb -s "$TV" "$@" </dev/null; }
tvsh() { adb -s "$TV" shell "$@" </dev/null 2>/dev/null | tr -d '\r'; }
key()  { tvsh input keyevent "$@"; }
home_dump()   { tvsh dumpsys activity "$PKG/.HomeActivity"; }
center_dump() { tvsh dumpsys activity "$PKG/.CenterActivity"; }
val() { grep -m1 "stark\.$1=" | sed "s/.*stark\.$1=//"; }
resolve_home() { tvsh cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1; }
resumed() { tvsh dumpsys activity activities | grep -m1 'mResumedActivity'; }
wait_for() { local secs="$1"; shift; local _; for _ in $(seq 1 "$secs"); do "$@" && return 0; sleep 1; done; return 1; }
shot() { mkdir -p "$SHOTS"; tv exec-out screencap -p > "$SHOTS/$1.png"; }

snapshot() {
  echo "dns=$(tvsh settings get global private_dns_mode) $(tvsh settings get global private_dns_specifier)"
  echo "anim=$(tvsh settings get global window_animation_scale)"
  echo "saver=$(tvsh settings get secure screensaver_components)"
  echo "adb=$(tvsh settings get global adb_enabled)"
  echo "home=$(resolve_home)"
  tvsh pm list packages -d | sort
}
snapshot > "$TMP/before.txt"
mkdir -p "$SHOTS"

if tv install -r "$APK" 2>&1 | grep -q Success; then pass "install -r (upgrade keeps data)"; else fail "install failed"; exit 1; fi
tvsh pm grant "$PKG" android.permission.WRITE_SECURE_SETTINGS
if tvsh dumpsys package "$PKG" | grep -q "android.permission.WRITE_SECURE_SETTINGS: granted=true"; then pass "WRITE_SECURE_SETTINGS granted"
else fail "WRITE_SECURE_SETTINGS not granted"; fi
if tvsh appops get "$PKG" GET_USAGE_STATS | grep -q allow; then pass "usage access granted"; else warn "usage access not granted (Biggest caches hidden)"; fi

home=$(resolve_home)
if [[ "$home" == "$PKG"* ]]; then pass "Home resolves to Stark"; else fail "Home resolves to $home (run ./stark home)"; fi

# ---------- launch ----------
# Right after an install Android is still verifying and compiling the APK: one launch to settle, then the median of 3.
# As the home app, a force-stopped launcher is restarted by the system at once, so `am start -W` often reports 0.
# The system's "Displayed ... +1s23ms" line times the real process start however it was launched.
cold_start() {
  local line="" d _
  tvsh logcat -c
  tvsh am force-stop "$PKG"
  tvsh am start -n "$PKG/.HomeActivity" >/dev/null
  for _ in 1 2 3 4 5 6 7 8 9 10; do
    line=$(tvsh logcat -d -s ActivityManager:I | grep -m1 "Displayed $PKG/.HomeActivity: +")
    [ -n "$line" ] && break; sleep 1
  done
  d=$(printf '%s' "$line" | sed -n 's/.*HomeActivity: +\([0-9s]*\)ms.*/\1/p')
  case "$d" in
    "") ;;
    *s*) echo $(( ${d%%s*} * 1000 + 10#${d#*s} )) ;;
    *) echo "$d" ;;
  esac
}
cold_start >/dev/null; sleep 3
start=$( (cold_start; sleep 2; cold_start; sleep 2; cold_start) | sort -n | sed -n 2p)
if [ -z "$start" ]; then fail "cold start not measured"
elif [ "$start" -le 1500 ]; then pass "cold start ${start} ms (budget 1500 ms)"
elif [ "$start" -le 3000 ]; then warn "cold start ${start} ms (budget 1500 ms)"
else fail "cold start ${start} ms"; fi
tvsh logcat -c

tiles_ready() { local t; t=$(home_dump | val tiles); [ -n "$t" ] && [ "${t%/*}" = "${t#*/}" ] && [ "${t#*/}" -gt 0 ]; }
if wait_for 15 tiles_ready; then pass "all app tiles rendered ($(home_dump | val tiles))"; else fail "tiles not rendered: $(home_dump | val tiles)"; fi
d=$(home_dump)
rows=$(echo "$d" | val rows)
if [ "${rows:-0}" -ge 3 ]; then pass "home has $rows rows"; else fail "home has ${rows:-0} rows"; fi
check "favourites row first" sh -c "echo \"\$0\" | grep -q 'stark.row.0=Favourites'" "$d"
check "quick actions present" sh -c "echo \"\$0\" | grep -q 'stark.row.1=Quick actions tiles=7'" "$d"
check "something is focused" sh -c "echo \"\$0\" | grep -q 'stark.focus=' && ! echo \"\$0\" | grep -q 'stark.focus=none'" "$d"
check "eclipse art loaded" sh -c "echo \"\$0\" | grep -q 'stark.art=true'" "$d"
check "not in safe mode" sh -c "echo \"\$0\" | grep -q 'stark.safeMode=false'" "$d"
shot home

# ---------- smoothness and memory ----------
tvsh dumpsys gfxinfo "$PKG" reset >/dev/null
for k in DPAD_RIGHT DPAD_RIGHT DPAD_RIGHT DPAD_DOWN DPAD_RIGHT DPAD_DOWN DPAD_DOWN DPAD_LEFT DPAD_DOWN DPAD_DOWN \
         DPAD_DOWN DPAD_RIGHT DPAD_RIGHT DPAD_UP DPAD_UP DPAD_UP DPAD_UP DPAD_UP DPAD_UP DPAD_LEFT DPAD_LEFT; do key "$k"; done
key DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_UP DPAD_UP DPAD_UP DPAD_UP DPAD_UP DPAD_UP
sleep 1
gfx=$(tvsh dumpsys gfxinfo "$PKG")
frames=$(echo "$gfx" | grep -m1 "Total frames rendered" | awk '{print $NF}')
jank=$(echo "$gfx" | grep -m1 "Janky frames" | sed 's/.*(\([0-9.]*\)%).*/\1/')
p90=$(echo "$gfx" | grep -m1 "90th percentile" | awk '{print $NF}' | tr -d 'ms')
info "$frames frames, ${jank}% janky, 90th percentile ${p90} ms"
jank_i=${jank%.*}
if [ "${jank_i:-100}" -le 10 ]; then pass "janky frames ${jank}% (budget 10%)"
elif [ "${jank_i:-100}" -le 20 ]; then warn "janky frames ${jank}% (budget 10%)"
else fail "janky frames ${jank}%"; fi
if [ "${p90:-999}" -le 20 ]; then pass "90th percentile frame ${p90} ms (budget 20 ms)"
elif [ "${p90:-999}" -le 34 ]; then warn "90th percentile frame ${p90} ms (budget 20 ms)"
else fail "90th percentile frame ${p90} ms"; fi
pss=$(tvsh dumpsys meminfo "$PKG" | awk '/TOTAL PSS:|^ *TOTAL /{print int($2/1024); exit}')
if [ "${pss:-999}" -le 60 ]; then pass "home uses ${pss} MB (budget 60 MB)"
elif [ "${pss:-999}" -le 90 ]; then warn "home uses ${pss} MB (budget 60 MB)"
else fail "home uses ${pss} MB"; fi

# ---------- long-press menu ----------
tvsh am start -n "$PKG/.HomeActivity" >/dev/null
key HOME; sleep 1
tvsh input keyevent --longpress DPAD_CENTER; sleep 1
if home_dump | grep -q "stark.menu=true"; then pass "long-press OK opens the app menu"; shot menu; else fail "long-press menu didn't open"; fi
key VOLUME_UP; sleep 0.3; key VOLUME_DOWN
if home_dump | grep -q "stark.menu=true"; then pass "volume keys pass through while the menu is open"; else fail "volume key closed the menu"; fi
key BACK; sleep 0.5
if home_dump | grep -q "stark.menu=false"; then pass "Back closes the menu"; else fail "menu still open after Back"; fi

# ---------- startup animation ----------
tvsh am start -n "$PKG/.HomeActivity" --ez io.github.starktvos.SPLASH true >/dev/null
sleep 1.5
s=$(home_dump | val splash)
if [ "$s" = playing ] || [ "$s" = starting ]; then pass "startup animation plays ($s)"; shot splash; else fail "startup animation state: $s"; fi
splash_idle() { [ "$(home_dump | val splash)" = idle ]; }
if wait_for 10 splash_idle; then pass "animation ends on its own and returns to Home"; else fail "animation still showing after 10 s"; fi
tvsh am start -n "$PKG/.HomeActivity" --ez io.github.starktvos.SPLASH true >/dev/null
sleep 1.5; key DPAD_CENTER; sleep 1
if splash_idle; then pass "any key skips the animation"; else fail "key didn't skip the animation"; fi
if resumed | grep -q "$PKG/.HomeActivity"; then pass "skipping with OK doesn't open the focused app"; else fail "OK while skipping opened: $(resumed)"; fi

# ---------- Control Center ----------
for page in main performance caches network mirror security sideloaded apps get home about; do
  tvsh am start -n "$PKG/.CenterActivity" --es page "$page" >/dev/null
  page_ready() { center_dump | grep -q "stark.page=$page" && ! center_dump | grep -q "stark.page.title=loading"; }
  if wait_for 8 page_ready; then
    n=$(center_dump | val page.items)
    if [ "${n:-0}" -gt 0 ] || [ "$page" = sideloaded ]; then pass "Control page '$page' ($n items)"; else fail "Control page '$page' is empty"; fi
    shot "center-$page"
  else
    fail "Control page '$page' didn't load"
  fi
done
key BACK; key BACK; sleep 0.5

# ---------- packages come and go ----------
apps=$(home_dump | val apps | tr ',' '\n')
third=$(tvsh pm list packages -3 -e | sed 's/^package://')
owners=$(tvsh dumpsys wifi | grep -oE 'cname=[A-Za-z0-9_.]+' | cut -d= -f2 | sort -u)
victim=""
for p in org.videolan.vlc com.phlox.tvwebbrowser com.yablio.sendfilestotv com.ionitech.airscreen $(echo "$apps" | grep -xF "$third"); do
  echo "$apps" | grep -qxF "$p" || continue
  echo "$third" | grep -qxF "$p" || continue
  echo "$owners" | grep -qxF "$p" && continue
  victim="$p"; break
done
if [ -n "$victim" ]; then
  reenable() { tvsh pm enable "$victim" >/dev/null; }
  cleanup_fns="$cleanup_fns reenable"
  tvsh am start -n "$PKG/.HomeActivity" >/dev/null; sleep 1
  before=$(home_dump | val tiles); before=${before#*/}
  tvsh pm disable-user --user 0 "$victim" >/dev/null
  gone() { local t; t=$(home_dump | val tiles); [ "${t#*/}" -lt "$before" ]; }
  if wait_for 8 gone; then pass "home drops a disabled app ($victim)"; else fail "home still shows disabled $victim"; fi
  tvsh pm enable "$victim" >/dev/null
  back() { local t; t=$(home_dump | val tiles); [ "${t#*/}" = "$before" ]; }
  if wait_for 8 back; then pass "home shows it again when re-enabled"; else fail "home didn't pick $victim up again"; fi
else
  warn "no safe third-party app to toggle: package add/remove test skipped"
fi

# ---------- random input (monkey) ----------
tvsh am start -n "$PKG/.HomeActivity" >/dev/null; sleep 1
tvsh logcat -c
monkey=$(tvsh monkey -p "$PKG" -s 4242 --throttle 60 --pct-touch 0 --pct-motion 0 --pct-trackball 0 \
  --pct-syskeys 0 --pct-appswitch 0 --pct-anyevent 0 --pct-flip 0 --pct-pinchzoom 0 \
  --pct-nav 65 --pct-majornav 35 -v 1500 2>&1)
if echo "$monkey" | grep -q "Monkey finished" && ! echo "$monkey" | grep -qE "// CRASH|// NOT RESPONDING"; then
  pass "1500 random remote presses: no crash, no freeze"
else
  fail "monkey run failed:"; echo "$monkey" | grep -E "CRASH|NOT RESPONDING|Exception" | head -10
fi
crashes=$(tvsh logcat -d -b crash 2>/dev/null | grep -c "$PKG")
anrs=$(tvsh logcat -d | grep -cE "ANR in $PKG")
if [ "${crashes:-0}" = 0 ] && [ "${anrs:-0}" = 0 ]; then pass "crash log clean"; else fail "$crashes crash and $anrs ANR lines in logcat"; fi

# ---------- recovery ----------
tvsh am force-stop "$PKG"
tvsh am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null
rows_back() { [ "$(home_dump | val rows)" -ge 3 ] 2>/dev/null; }
if wait_for 10 rows_back; then pass "comes back after being force-stopped"; else fail "home didn't recover after force-stop"; fi

# ---------- state receiver ----------
if tvsh dumpsys package "$PKG" | grep -A3 "StateReceiver" | grep -q "WRITE_SECURE_SETTINGS"; then
  pass "state receiver is permission-protected on the TV"
else
  info "receiver permission not shown by this dumpsys (checked statically above)"
fi

# ---------- optional: reboot ----------
if [ $REBOOT = 1 ]; then
  info "rebooting the TV (about 1-2 minutes)..."
  tv reboot
  sleep 25
  online() { adb connect "$TV" >/dev/null 2>&1; [ "$(adb -s "$TV" get-state 2>/dev/null)" = device ]; }
  if wait_for 150 online; then pass "TV back online after reboot"; else fail "TV didn't come back"; fi
  booted() { [ "$(home_dump | val splash.last)" = boot ]; }
  if wait_for 60 booted; then pass "startup animation played on boot"; else fail "no startup animation on boot ($(home_dump | val splash.last))"; fi
fi

# ---------- optional: restore and reinstall ----------
if [ $RESTORE_CYCLE = 1 ]; then
  STARK_TV="$TV" ./stark restore > "$TMP/restore.log" 2>&1 </dev/null
  h=$(resolve_home)
  if [[ "$h" != "$PKG"* ]]; then pass "./stark restore gives Home back to $h"; else fail "Home still Stark after restore"; fi
  if [ -z "$(tvsh pm list packages -d | grep -E 'tvlauncher|com\.tcl\.(bi|messagebox|esticker)$')" ]; then pass "restore re-enabled every app"; else fail "apps still disabled after restore"; fi
  STARK_TV="$TV" ./stark home > "$TMP/home.log" 2>&1 </dev/null
  STARK_TV="$TV" ./stark tune > /dev/null 2>&1 </dev/null
  STARK_TV="$TV" ./stark debloat > /dev/null 2>&1 </dev/null
  if [[ "$(resolve_home)" == "$PKG"* ]]; then pass "./stark home makes Stark the home screen again"; else fail "couldn't make Stark home again"; fi
  snapshot > "$TMP/before.txt"
fi

# ---------- nothing changed ----------
snapshot > "$TMP/after.txt"
if diff -q "$TMP/before.txt" "$TMP/after.txt" >/dev/null; then pass "TV settings identical before and after the tests"
else fail "the tests changed TV settings:"; diff "$TMP/before.txt" "$TMP/after.txt"; fi
tvsh am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null

section "Result"
printf '  %d passed, %d failed, %d warnings. Screenshots: %s/\n' "$passes" "$fails" "$warns" "$SHOTS"
[ $fails = 0 ]
