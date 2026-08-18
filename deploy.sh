#!/data/data/com.termux/files/usr/bin/bash
cd "$(dirname "$0")"
TOKEN=$(git config --global github.token)
GHUSER=Sekiguchi-Takashi
REPO=KakuregaApp
rm -f .github/workflows/build.yml
if [ ! -d .git ]; then git init -b main; fi
git remote remove origin 2>/dev/null
git remote add origin https://$GHUSER:$TOKEN@github.com/$GHUSER/$REPO.git
git add -A
git commit -m "${1:-update}"
git pull --rebase origin main
git push -u origin main
if [ "$2" = "notag" ]; then
  printf 'pushed (no tag)\n'
  exit 0
fi
git fetch --tags --force
LATEST=$(git tag --list 'v*' | sort -V | tail -1)
if [ -z "$LATEST" ]; then
  NEXT=v1.0.0
else
  P=${LATEST##*.}
  NEXT=${LATEST%.*}.$((P+1))
fi
git tag "$NEXT"
git push origin "$NEXT"
printf 'pushed and tagged %s\n' "$NEXT"
