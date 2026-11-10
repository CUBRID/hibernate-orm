#!/usr/bin/env bash
# Find the state left by the previous scheduled run and the commits tested since then.
# Writes baseline/cubrid-report-state.json (only when such a run exists) and compare.json.
# Needs GH_TOKEN, GITHUB_REPOSITORY, GITHUB_RUN_ID and RESULTS_DIR (this run's summaries).
set -uo pipefail

mkdir -p baseline
echo '{}' > compare.json

for id in $(gh run list -R "$GITHUB_REPOSITORY" --workflow cubrid-ci.yml --branch main --event schedule \
        --status completed --limit 10 --json databaseId -q ".[].databaseId | select(. != $GITHUB_RUN_ID)"); do
    if gh run download "$id" -R "$GITHUB_REPOSITORY" -n cubrid-report-state -D baseline 2>/dev/null; then
        echo "baseline: run $id"
        break
    fi
done

state=baseline/cubrid-report-state.json
if [ ! -f "$state" ]; then
    echo "baseline: none"
    exit 0
fi
base=$(jq -r '[.versions[].env.commit | select(. != null and . != "unknown")] | first // empty' "$state")
head=$(jq -rs '[.[].env.commit | select(. != null and . != "unknown")] | first // empty' \
    "$RESULTS_DIR"/cubrid-results-*.json 2>/dev/null)
if [ -n "$base" ] && [ -n "$head" ]; then
    gh api "repos/$GITHUB_REPOSITORY/compare/$base...$head" \
        --jq '{commits: ([.commits[] | select((.parents | length) == 1)] | length),
               keys: ([.commits[].commit.message | scan("HHH-[0-9]+")] | unique),
               url: .html_url}' > compare.json.tmp && mv compare.json.tmp compare.json
fi
cat compare.json
