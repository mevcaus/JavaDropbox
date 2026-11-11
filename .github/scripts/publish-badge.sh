#!/usr/bin/env bash
# Commits a badge file (from coverage.py --badge) to the badges branch, which holds nothing else
# and which the README's coverage badges read. Run from a checkout whose credentials can push.
#
#     publish-badge.sh backend.json
#
# The backend and frontend workflows each publish their own file and can finish at the same
# moment, so a push that loses the race starts again from the other's commit.
set -euo pipefail

file=$1
name=$(basename "$file")
work=$(mktemp -d)
cp "$file" "$work/$name"

git config user.name 'github-actions[bot]'
git config user.email '41898282+github-actions[bot]@users.noreply.github.com'

for attempt in 1 2 3 4 5; do
  if git fetch --depth 1 origin badges 2>/dev/null; then
    git switch --force -C badges FETCH_HEAD
  else
    # The first time: a branch with no history of main's.
    git switch --force --orphan badges
    git rm -rf --quiet --ignore-unmatch .
  fi
  cp "$work/$name" "$name"
  git add "$name"
  if git diff --cached --quiet; then
    echo "$name is unchanged"
    exit 0
  fi
  git commit --quiet -m "update $name"
  if git push --quiet origin badges; then
    exit 0
  fi
  echo "The push lost a race (attempt $attempt), trying again"
  sleep $((attempt * 2))
done
echo "Could not push $name to the badges branch" >&2
exit 1
