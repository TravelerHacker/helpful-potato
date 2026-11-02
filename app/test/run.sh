#!/bin/bash
# Unit-test the pure-Java CH9329 encoder on the host JVM.
set -e; cd "$(dirname "$0")/.."; if [ -n "${JAVA_HOME:-}" ]; then export PATH=$JAVA_HOME/bin:$PATH; fi
mkdir -p build/test && javac -d build/test src/com/phonehands/hid/Ch9329.java test/Ch9329Test.java && java -cp build/test Ch9329Test
