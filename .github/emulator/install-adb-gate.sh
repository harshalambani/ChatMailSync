#!/bin/sh
# QA-02: see the header of .github/workflows/emulator-tests.yml.
# android-emulator-runner prepends $ANDROID_HOME/platform-tools to PATH itself, so the only
# way to sit in front of its boot poll is to wrap the adb binary in that directory. Run from
# pre-emulator-launch-script, i.e. after the runner has installed platform-tools and before
# it starts the emulator. Safe to run twice.
set -eu
dir="$ANDROID_HOME/platform-tools"
if [ ! -f "$dir/adb.real" ]; then
  mv "$dir/adb" "$dir/adb.real"
fi
cat > "$dir/adb" <<'SHIM'
#!/bin/sh
REAL="$(dirname "$0")/adb.real"
# Only the runner's boot poll: adb -s emulator-NNNN shell getprop sys.boot_completed
if [ "${1:-}" = "-s" ] && [ "${3:-}" = "shell" ] && [ "${4:-}" = "getprop" ] && [ "${5:-}" = "sys.boot_completed" ]; then
  out=$("$REAL" "$@") || exit $?
  if [ "$(printf '%s' "$out" | tr -d '[:space:]')" = "1" ]; then
    svc=$("$REAL" -s "$2" shell service check input 2>&1 | tr -d '\r' || true)
    case "$svc" in
      *"not found"*|"") echo "QA-02: boot_completed=1 but the input service is not published yet" >&2; echo 0; exit 0 ;;
      *found*) ;;
      *) echo "QA-02: input service state unknown ($svc)" >&2; echo 0; exit 0 ;;
    esac
    echo "QA-02: input service is up" >&2
  fi
  printf '%s\n' "$out"
  exit 0
fi
exec "$REAL" "$@"
SHIM
chmod +x "$dir/adb"
echo "QA-02: adb wrapped at $dir/adb"
