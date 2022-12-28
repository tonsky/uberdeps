#!/bin/sh -eu

cd "`dirname $0`/.."

clojure -A:test -M -m uberdeps.test