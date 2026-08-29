#!/usr/bin/env bash
# Sums the JUnit XML counts for each module's unit test task. Local helper, not shipped.
cd "$(dirname "$0")/.." || exit 1
total=0
for f in app/build/test-results/testPlayDebugUnitTest \
         app/build/test-results/testFossDebugUnitTest \
         core/build/test-results/testDebugUnitTest \
         wear/build/test-results/testDebugUnitTest; do
  [ -d "$f" ] || continue
  read -r n fl er <<<"$(grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' "$f"/*.xml 2>/dev/null |
    sed 's/[a-z]*="//g;s/"//g' | awk '{t+=$1; f+=$3; e+=$4} END {print t, f, e}')"
  echo "$f tests=${n:-0} failures=${fl:-0} errors=${er:-0}"
  total=$((total + ${n:-0}))
done
echo "total $total"
