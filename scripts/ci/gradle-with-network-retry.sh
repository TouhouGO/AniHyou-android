#!/usr/bin/env bash
# Retry only known transient failures while fetching Gradle or dependencies.
set -u

log=$(mktemp) || exit 1
trap 'rm -f "$log"' EXIT

for attempt in 1 2 3; do
    printf 'Gradle attempt %s/3\n' "$attempt"
    ./gradlew "$@" 2>&1 | tee "$log"
    status=${PIPESTATUS[0]}
    if (( status == 0 )); then
        exit 0
    fi

    # Do not mask a simultaneous test, compiler, or permanent HTTP failure.
    if grep -Eiq '([0-9]+ tests? completed, [0-9]+ failed|There were failing tests|Compilation error|Compilation failed|^e: |HTTP response code: (403|404)|status code (403|404))' "$log"; then
        exit "$status"
    fi
    if ! { grep -Eiq '(HTTP response code: (502|503|504)|Received status code (502|503|504) from server)' "$log" &&
           grep -Eiq '(Downloading https?://|Could not (download|GET|get resource)|for URL: https?://)' "$log"; } &&
       ! { grep -Eiq 'Could not (download|GET|get resource)' "$log" &&
           grep -Eiq '(Read timed out|Connect timed out|connection timed out)' "$log"; } &&
       ! { grep -Eiq 'Downloading https?://[^ ]*/distributions/' "$log" &&
           grep -Eiq '(Read timed out|Connect timed out|connection timed out)' "$log"; }; then
        exit "$status"
    fi
    if (( attempt == 3 )); then
        exit "$status"
    fi
    printf 'Transient download failure; retrying Gradle.\n' >&2
done
