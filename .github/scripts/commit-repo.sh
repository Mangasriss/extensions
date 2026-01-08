#!/bin/bash
set -e

rsync -a --delete --force --exclude .git --exclude .gitignore --exclude README.md ../main/repo/ .
git config --global user.email "github-actions[bot]@users.noreply.github.com"
git config --global user.name "github-actions[bot]"
git status
if [ -n "$(git status --porcelain)" ]; then
  git add .
  git commit -m "Update extensions repo"
  git push

  # Purge cached index on jsDelivr
  curl https://purge.jsdelivr.net/gh/Mangasriss/extensions@repo/index.min.json
else
  echo "No changes to commit"
fi
