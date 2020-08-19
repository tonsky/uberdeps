#!/bin/zsh -euo pipefail

cd "`dirname $0`/.."

clj -A:test -m uberdeps.test