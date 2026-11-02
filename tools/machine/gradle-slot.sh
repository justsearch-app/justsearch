#!/usr/bin/env bash
# Adopted 2026-10-20 (user yes, t-20261019-6c7427). Usage: gradle-slot.sh [-w seconds] ./gradlew <args...>
# Locks one Gradle call: this worktree first, then a free global slot. Slot 1 is the legacy
# gradle.lock, so callers still on `flock gradle.lock` are counted (they skip the worktree lock).
# Exit 64 usage, 73 a lock cannot be opened, 75 no lock in time: report it, do not retry in a loop.
set -u
usage() { echo "usage: gradle-slot.sh [-w seconds] ./gradlew <args...> ($*)" >&2; exit 64; }
wait=7200
if [ "${1:-}" = -w ]; then [ $# -ge 3 ] && [[ $2 =~ ^[0-9]+$ ]] || usage "bad -w"; wait=$2; shift 2; fi
[ $# -ge 1 ] || usage "no command"
slots=${JS_GRADLE_SLOTS:-2}; workers=${JS_GRADLE_MAX_WORKERS:-6}
[[ $slots =~ ^[1-9][0-9]*$ && $workers =~ ^[1-9][0-9]*$ ]] || usage "JS_GRADLE_SLOTS and JS_GRADLE_MAX_WORKERS must be positive integers"
args=("$@")
if [ "$(basename "$1")" = gradlew ]; then
  for a in "${@:2}"; do
    [ "$a" = -- ] && break
    case $a in -p|--project-dir|--project-dir=*|-p?*) usage "run from the build's own directory instead of $a";; esac
  done
  if [ "$slots" -gt 1 ] && ! printf '%s\n' "${@:2}" | grep -q -- '^--max-workers'; then
    args=("$1" "--max-workers=$workers" "${@:2}")
  fi
elif [ "$slots" -gt 1 ] && [[ ${GRADLE_OPTS:-} != *-Dorg.gradle.workers.max=* ]]; then
  # A script that runs Gradle itself (run-evidence.mjs): cap its builds the same way.
  export GRADLE_OPTS="${GRADLE_OPTS:+$GRADLE_OPTS }-Dorg.gradle.workers.max=$workers"
fi
# A nested call (a wrapped script that runs the wrapper again) would hold one slot while it queues for
# another, or wait on its own worktree lock. Refuse it at once; the inner command needs no wrapper.
# A false match (PID reuse, another PID namespace) only refuses: it never runs without a slot.
# /proc, not kill -0: a live process of another user answers EPERM. A holder in another PID namespace
# is invisible here; that call takes the normal path, as before.
if [[ ${JS_GRADLE_SLOT_HOLDER:-} =~ ^[1-9][0-9]*$ && -e /proc/$JS_GRADLE_SLOT_HOLDER ]]; then
  echo "gradle-slot: already inside a slot holder (pid $JS_GRADLE_SLOT_HOLDER); run the inner command without js-gradle-slot" >&2; exit 64
fi
# Admission precedes both the worktree lock and the global queue/slots. Re-exec only
# this shell (PID checked), so another invocation still enters admission normally.
if [ "${JS_GRADLE_ADMISSION_ENTERED:-}" != "$$" ]; then
  admission_root=$(git rev-parse --show-toplevel 2>/dev/null || pwd)
  exec python3 "$admission_root/scripts/dev/admission.py" heavy --purpose "Gradle slot: $PWD" -- \
    bash -c 'export JS_GRADLE_ADMISSION_ENTERED=$$; exec "$@"' gradle-admission "$0" -w "$wait" "$@"
fi
dir=${JS_GRADLE_SLOT_DIR:-/tmp/js-linux-port}; start=$(date +%s); deadline=$(( start + wait ))
mkdir -p "$dir" || { echo "gradle-slot: cannot create $dir" >&2; exit 73; }
root=$(git rev-parse --show-toplevel 2>/dev/null || pwd); mkdir -p "$root/.gradle"
exec 8>"$root/.gradle/agent-build.lock" || { echo "gradle-slot: cannot open the worktree lock" >&2; exit 73; }
flock -w "$wait" 8 || { echo "gradle-slot: worktree busy for ${wait}s" >&2; exit 75; }
# First come, first served: a ticket named by a sequence taken under the seq file's lock and held by
# its own flock, so a dead waiter's ticket is unlocked and pruned. Only a waiter with no older live
# ticket tries the slots; a ticket not refreshed for 2 min (a stopped waiter) is passed over. The slot
# flocks stay the only exclusion: if the queue fails, this call tries them unordered, as before.
# The check is repeated while holding a slot, so a stopped head that resumed is not overtaken; the
# head polls every second, so such a brief hold delays it by at most that.
q=$dir/queue; n=
qlog() {  # seq time slot wait. At 1 MB only the holder of the log's lock rotates, after checking
  # again, so a second rotation never overwrites the backup; a writer that finds it busy just appends.
  local log=$dir/queue.log
  if [ "$(stat -c %s "$log" || echo 0)" -ge 1000000 ] && exec 4>>"$dir/queue-log.lock" && flock -n 4; then
    [ "$(stat -c %s "$log" || echo 0)" -lt 1000000 ] || mv -f "$log" "$log.1"
  fi
  printf '%s %s %s %s\n' "${n:--}" "${EPOCHREALTIME/,/.}" "$1" "$2" >> "$log"; exec 4>&-
} 2>/dev/null
left=$(( deadline - EPOCHSECONDS )); [ "$left" -gt 10 ] && left=10
if { [ -d "$q" ] || mkdir -p "$q"; } && exec 7<>"$q/seq" && flock -w $(( left > 0 ? left : 0 )) 7; then
  read -r s <&7; [[ $s =~ ^[0-9]{1,15}$ ]] || s=0; s=$((10#$s + 1))
  echo "$s" > "$q/seq"; printf -v n %015d "$s"
  if exec 6>"$q/$n" && flock -n 6; then qlog - -; else exec 6>&-; n=; fi
fi 2>/dev/null; exec 7>&-
for sig in TERM INT HUP; do trap "rm -f \${n:+\"\$q/\$n\"}; trap - $sig; kill -$sig \$\$" "$sig"; done
older() {  # true while an older ticket's owner is alive and polled in the last 2 min
  local t
  for t in "$q"/[0-9]*; do
    [[ -n $n && $t < $q/$n ]] || return 1
    exec 5<"$t" || continue
    if flock -n 5; then rm -f "$t"
    elif (( EPOCHSECONDS - $(stat -c %Y "$t" || echo 0) <= 120 )); then exec 5<&-; return 0; fi
    exec 5<&-
  done 2>/dev/null; return 1
}
delay=1
while :; do
  head=; older || { head=1; for i in $(seq 1 "$slots"); do
    f="$dir/gradle.lock"; [ "$i" -gt 1 ] && f="$dir/gradle.lock.$i"
    exec 9>"$f" || { echo "gradle-slot: cannot open $f" >&2; exit 73; }
    if flock -n 9 && ! older; then
      qlog "$i" $(( $(date +%s) - start )); rm -f ${n:+"$q/$n"}; exec 6>&-
      echo "gradle-slot: slot $i after $(( $(date +%s) - start ))s" >&2
      # Own process group, so a signal reaches everything this call started. On a signal the
      # locks stay held until that group is gone (at most 60 s). On a normal exit they are
      # released at once: a daemon this call started stays in the group and outlives it.
      got=$(date +%s)
      # One line per call for the queue measurement (t-20261020-a7c3e1): asked, got, done, slot, rc, dir, command.
      record() { printf '%s %s %s %s %s %s %s\n' "$start" "$got" "$(date +%s)" "$i" "$1" "$PWD" "$(printf '%s ' "${args[@]}" | tr '\n' ' ' | cut -c1-200)" >> "$dir/slot.log" 2>/dev/null; }
      JS_GRADLE_SLOT_HOLDER=$$ setsid "${args[@]}" 8>&- 9>&- & child=$!
      trap 'kill -TERM -- -$child 2>/dev/null; wait $child; for _ in $(seq 300); do kill -0 -- -$child 2>/dev/null || break; sleep 0.2; done; record 143; exit 143' TERM INT HUP
      wait $child; rc=$?; record "$rc"; exit $rc
    fi
    exec 9>&-
  done; }
  [ -z "$n" ] || touch /dev/fd/6 2>/dev/null  # heartbeat
  now=$(date +%s); [ "$now" -ge "$deadline" ] && { rm -f ${n:+"$q/$n"}; echo "$start - $now - 75 $PWD $(printf '%s ' "${args[@]}" | tr '\n' ' ' | cut -c1-200)" >> "$dir/slot.log" 2>/dev/null; echo "gradle-slot: no slot in ${wait}s" >&2; exit 75; }
  left=$(( deadline - now )); [ "$delay" -gt "$left" ] && delay=$left
  sleep "${head:-$delay}" 6>&- 8>&- & wait $!; [ "$delay" -lt 4 ] && delay=$((delay * 2))
done
