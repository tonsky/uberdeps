#!/bin/zsh -euo pipefail

cd "`dirname $0`/.."

clj -A:test -M -m uberdeps.test