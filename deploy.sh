#!/data/data/com.termux/files/usr/bin/bash
cd "$(dirname "$0")"
TOKEN=$(git config --global github.token)
GHUSER=Sekiguchi-Takashi
REPO=KakuregaApp
API=https://api.github.com
curl -s -o /dev/null -H "Authorization: token $TOKEN" -d "{\"name\":\"$REPO\"}" $API/user/repos
if [ ! -d .git ]; then git init -b main; fi
git remote remove origin 2>/dev/null
git remote add origin https://$GHUSER:$TOKEN@github.com/$GHUSER/$REPO.git
git add -A
git commit -m "${1:-update}"
git pull --rebase origin main
git push -u origin main
LATEST=$(git ls-remote --tags origin | cut -f2 | sed 's|refs/tags/||' | grep -v '\^{}' | sort -V | tail -1)
if [ -z "$LATEST" ]; then
  NEXT=v1.0.0
else
  case "$LATEST" in
    v*.*.*)
      P=${LATEST##*.}
      NEXT=${LATEST%.*}.$((P+1))
      ;;
    v*.*)
      NEXT=${LATEST}.1
      ;;
    *)
      NUM=$(printf '%s' "$LATEST" | grep -o '[0-9]*$')
      if [ -z "$NUM" ]; then NEXT=${LATEST}1; else NEXT=${LATEST%$NUM}$((NUM+1)); fi
      ;;
  esac
fi
SHA=$(git rev-parse HEAD)
curl -s -o /dev/null -H "Authorization: token $TOKEN" -d "{\"ref\":\"refs/tags/$NEXT\",\"sha\":\"$SHA\"}" $API/repos/$GHUSER/$REPO/git/refs
printf 'pushed and tagged %s\n' "$NEXT"
