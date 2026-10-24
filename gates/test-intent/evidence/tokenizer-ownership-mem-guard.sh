#!/usr/bin/env bash
# Usage: tmp/mem-guard.sh <log> -- <command...>
# Runs the command in its own process group. Every 5 s, samples Gradle test JVMs whose working
# directory is under this checkout. If one exceeds LIMIT_MIB (RSS + swap, default 6144), logs it and
# cancels the command's process group, which makes Gradle stop its test workers. Test workers are
# children of the shared daemon, so if that test JVM is still the same process 10 s later (same start
# time and working directory), it is killed directly.
set -u
log=$1; shift; [ "$1" = "--" ] && shift
limit=${LIMIT_MIB:-6144}; here=$(pwd)
start_of() { awk '{print $22}' /proc/$1/stat 2>/dev/null; }
same() { [ "$(start_of $1)" = "$2" ] && case "$(readlink /proc/$1/cwd 2>/dev/null)" in "$here"*) true;; *) false;; esac; }
setsid "$@" & pid=$!
while kill -0 "$pid" 2>/dev/null; do
  for p in $(pgrep -f "Gradle Test Executor"); do
    case "$(readlink /proc/$p/cwd 2>/dev/null)" in "$here"*) ;; *) continue;; esac
    st=$(start_of $p); rss=$(awk '/^VmRSS/{print int($2/1024)}' /proc/$p/status 2>/dev/null); sw=$(awk '/^VmSwap/{print int($2/1024)}' /proc/$p/status 2>/dev/null)
    [ -n "$rss" ] && [ -n "$st" ] || continue
    echo "$(date -u +%T) pid=$p rss=${rss}M swap=${sw}M" >>"$log"
    if [ $((rss+sw)) -gt "$limit" ]; then
      echo "$(date -u +%T) LIMIT pid=$p total=$((rss+sw))M > ${limit}M; cancelling" >>"$log"
      kill -TERM -- -"$pid" 2>/dev/null; sleep 10
      if same $p "$st"; then echo "$(date -u +%T) test JVM $p still running; killing it" >>"$log"; kill -KILL $p; fi
      sleep 10; kill -KILL -- -"$pid" 2>/dev/null; break 2
    fi
  done
  sleep 5
done
wait "$pid"
