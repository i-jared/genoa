#!/usr/bin/env bash
# Backs up the Genoa D1 database and proves the backup restores: export, import into a scratch
# database, then compare every table's row count with the source. Media in R2 is checked for
# consistency with D1 (every published asset exists), because R2 has no point-in-time restore.
#
#   scripts/ops/restore-drill.sh --local       rehearse against the local dev database
#   scripts/ops/restore-drill.sh --production  back up production and restore into a scratch D1
#
# Backups are written to .local/backups/ (ignored by Git, never packaged). The production scratch
# database is deleted afterwards. D1 Time Travel also allows restoring production in place to any
# minute in the last 30 days: `npx wrangler d1 time-travel restore genoa --timestamp=<ISO time>`.
set -euo pipefail
cd "$(dirname "$0")/../../backend"

mode=${1:-}
case "$mode" in
  --local) where=(--local); config=(); scratch_where=(--local --persist-to .wrangler/restore-drill) ;;
  --production) where=(--remote); config=(--config wrangler.production.jsonc); scratch_where=(--remote) ;;
  *) echo "usage: $0 --local | --production" >&2; exit 2 ;;
esac

stamp=$(date -u +%Y%m%dT%H%M%SZ)
backups=../.local/backups
mkdir -p "$backups" && chmod 700 "$backups"
dump="$backups/genoa-d1-${mode#--}-$stamp.sql"

if [[ $mode == --production ]]; then
  echo "Time Travel bookmark before export:"
  npx wrangler d1 time-travel info genoa ${config[@]+"${config[@]}"}
fi
echo "Exporting D1 to $dump"
npx wrangler d1 export genoa "${where[@]}" ${config[@]+"${config[@]}"} --output "$dump" >/dev/null
chmod 600 "$dump"

counts() { # database, location flags... -> "table count" lines
  local db=$1; shift
  local tables
  tables=$(npx wrangler d1 execute "$db" "$@" --json --command \
    "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE '_cf_%' AND name!='d1_migrations' ORDER BY name" |
    node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>console.log(JSON.parse(s)[0].results.map(r=>r.name).join(" ")))')
  # One row of scalar subqueries: D1 caps the number of terms in a compound SELECT.
  local query="" t
  for t in $tables; do query+="(SELECT COUNT(*) FROM \"$t\") \"$t\","; done
  npx wrangler d1 execute "$db" "$@" --json --command "SELECT ${query%,}" |
    node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{for(const [t,n] of Object.entries(JSON.parse(s)[0].results[0]))console.log(t,n)})'
}

scratch=genoa-restore-drill-$stamp
if [[ $mode == --production ]]; then
  npx wrangler d1 create "$scratch" >/dev/null
  trap 'npx wrangler d1 delete "$scratch" -y >/dev/null 2>&1 || true' EXIT
else
  rm -rf .wrangler/restore-drill
  trap 'rm -rf .wrangler/restore-drill' EXIT
  scratch=genoa
fi
echo "Restoring into scratch database"
npx wrangler d1 execute "$scratch" "${scratch_where[@]}" --file "$dump" --yes >/dev/null

source_counts=$(counts genoa "${where[@]}" ${config[@]+"${config[@]}"})
restored_counts=$(counts "$scratch" "${scratch_where[@]}")
if [[ "$source_counts" != "$restored_counts" ]]; then
  echo "Restore mismatch:" >&2
  diff <(echo "$source_counts") <(echo "$restored_counts") >&2 || true
  exit 1
fi
echo "Restored $(echo "$source_counts" | wc -l | tr -d ' ') tables with matching row counts:"
echo "$source_counts" | sed 's/^/  /'

# Media consistency: every published asset of a live message must exist in R2.
bucket=genoa-media
missing=0 checked=0
while read -r key; do
  [[ -z $key ]] && continue
  checked=$((checked + 1))
  if ! npx wrangler r2 object get "$bucket/$key" "${where[@]}" ${config[@]+"${config[@]}"} --pipe >/dev/null 2>&1; then
    echo "  missing: $key" >&2
    missing=$((missing + 1))
  fi
done < <(npx wrangler d1 execute genoa "${where[@]}" ${config[@]+"${config[@]}"} --json --command \
  "SELECT a.object_key FROM assets a JOIN messages m ON m.id=a.message_id WHERE m.state='ready' ORDER BY random() LIMIT 25" |
  node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>JSON.parse(s)[0].results.forEach(r=>console.log(r.object_key)))')
echo "Checked $checked sampled media objects; $missing missing."
# `if`, not a bare [[ ]]: bash 3.2 (macOS) doesn't apply errexit to failed [[ ]] tests.
if ((missing > 0)); then
  echo "Drill failed: D1 references media that R2 doesn't have. Backup kept at $dump" >&2
  exit 1
fi
echo "Drill passed. Backup kept at $dump"
