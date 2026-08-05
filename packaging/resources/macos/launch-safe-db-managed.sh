#!/bin/sh
set -eu

if [ "$#" -ne 2 ]; then
    echo "Usage: $0 /absolute/path/to/safe-db /absolute/path/to/launch-profile.json" >&2
    exit 2
fi

exec "$1" --launch-profile "$2"
