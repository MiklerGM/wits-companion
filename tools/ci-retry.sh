#!/usr/bin/env bash
#
# Runs a build command, retrying ONLY when it failed for a reason a retry can fix.
#
# Maven Central intermittently returns 403 to GitHub-hosted runners — a whole-build failure
# that has nothing to do with the code. The first CI run on this repo died that way, before a
# single test compiled, with every artifact refused by repo.maven.apache.org.
#
# What this deliberately does NOT do is retry a failing build. A flaky-test retry loop turns a
# real defect into an intermittent one and trains everybody to re-run until green; on a project
# whose test suite is the safety argument, that is the worst possible habit to build in. So the
# output is inspected first, and anything that is not a dependency-resolution or transport
# failure exits immediately with the original status.
#
#   tools/ci-retry.sh ./gradlew :app:testDebugUnitTest
#
set -uo pipefail

ATTEMPTS="${CI_RETRY_ATTEMPTS:-3}"
log="$(mktemp)"
trap 'rm -f "$log"' EXIT

for attempt in $(seq 1 "$ATTEMPTS"); do
  if "$@" 2>&1 | tee "$log"; then
    exit 0
  fi

  # Transient: the artifact could not be fetched. Everything else is a real failure.
  if ! grep -qE 'Could not (resolve|GET|get resource)|status code 40[39]|Connection (reset|timed out)|Read timed out' "$log"; then
    echo "::error::Build failed for a reason a retry will not fix — not retrying."
    exit 1
  fi

  if [ "$attempt" -eq "$ATTEMPTS" ]; then
    echo "::error::Dependency resolution still failing after ${ATTEMPTS} attempts."
    exit 1
  fi

  backoff=$((attempt * 30))
  echo "::warning::Dependency resolution failed (attempt ${attempt}/${ATTEMPTS}); retrying in ${backoff}s."
  sleep "$backoff"
done
