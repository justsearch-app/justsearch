#!/bin/sh
set -eu
for file in "$@"; do
  if [ -f "$file" ]; then
    echo "processing $file"
    gzip --keep --force "$file"
  fi
done
echo done
