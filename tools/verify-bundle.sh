#!/usr/bin/env bash
# Assert a built or installed bundle is actually shippable.
#
#   ./tools/verify-bundle.sh /path/to/P2PMonitor.app
#
# macOS refuses to register a widget extension that is not sandboxed:
#   pkd: rejecting; Ignoring mis-configured plugin ...: plug-ins must be sandboxed
# An ad-hoc / linker-signed build (what CODE_SIGNING_ALLOWED=NO produces) carries
# no entitlements at all, so the widget silently never appears in the gallery.
# This check exists so that failure is loud instead of silent.
set -uo pipefail

APP="${1:?usage: verify-bundle.sh /path/to/P2PMonitor.app}"
APPEX="$APP/Contents/PlugIns/P2PWidget.appex"
GROUP="UN798LFFKG.group.dev.dfanso.p2pmonitor"
fail=0

say() { printf '%s\n' "$*"; }
bad() { printf '  FAIL  %s\n' "$*"; fail=1; }
ok()  { printf '  ok    %s\n' "$*"; }

ents() { codesign -d --entitlements - --xml "$1" 2>/dev/null | plutil -convert xml1 -o - - 2>/dev/null; }

say "verifying $APP"

[ -d "$APP" ]   || { bad "app bundle missing"; exit 1; }
[ -d "$APPEX" ] || bad "widget extension missing at Contents/PlugIns/P2PWidget.appex"

# Ad-hoc signing is the tell-tale of a CODE_SIGNING_ALLOWED=NO build.
# NOTE: capture into a variable rather than `codesign ... | grep -q`. Under
# `set -o pipefail`, grep -q exits at the first match, codesign is killed by
# SIGPIPE, and the pipeline reports failure even though the pattern matched.
# That produced a false FAIL on the app (long output) while the appex (short
# output) passed.
for target in "$APP" "$APPEX"; do
  name=$(basename "$target")
  sig=$(codesign -dv "$target" 2>&1 || true)

  case "$sig" in
    *Signature=adhoc*|*adhoc,linker-signed*)
      bad "$name is ad-hoc signed — built with CODE_SIGNING_ALLOWED=NO, not shippable" ;;
    *) ok "$name is not ad-hoc signed" ;;
  esac

  case "$sig" in
    *TeamIdentifier=UN798LFFKG*) ok "$name carries team UN798LFFKG" ;;
    *) bad "$name has no team identifier" ;;
  esac
done

# The sandbox entitlement on the extension is what pkd actually gates on.
app_ents=$(ents "$APP")
appex_ents=$(ents "$APPEX")

case "$appex_ents" in
  *com.apple.security.app-sandbox*)
    ok "widget is sandboxed (pkd requires this or it is never registered)" ;;
  *) bad "widget lacks com.apple.security.app-sandbox — pkd will reject it" ;;
esac

case "$app_ents" in
  *"$GROUP"*) ok "P2PMonitor.app carries the app group" ;;
  *) bad "P2PMonitor.app is missing app group $GROUP" ;;
esac
case "$appex_ents" in
  *"$GROUP"*) ok "P2PWidget.appex carries the app group" ;;
  *) bad "P2PWidget.appex is missing app group $GROUP" ;;
esac

# The collector needs network; the widget must NOT have it.
case "$app_ents" in
  *com.apple.security.network.client*) ok "app has network.client" ;;
  *) bad "app is missing network.client — the collector cannot poll" ;;
esac
case "$appex_ents" in
  *com.apple.security.network.client*)
    bad "widget has network.client — it must only read the store" ;;
  *) ok "widget correctly has no network access" ;;
esac

if [ "$fail" -eq 0 ]; then say "PASS"; else say "FAILED"; fi
exit "$fail"
