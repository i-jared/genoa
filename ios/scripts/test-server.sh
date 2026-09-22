#!/bin/zsh
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
test_root="$(mktemp -d /tmp/genoa-ios-server.XXXXXX)"
export GENOA_TEST_REPO="$repo_root" GENOA_TEST_ROOT="$test_root"
python3 - <<'PY'
import json, os
from pathlib import Path
repo = Path(os.environ['GENOA_TEST_REPO'])
root = Path(os.environ['GENOA_TEST_ROOT'])
config = {
 'name': 'genoa-ios-local-test', 'main': str(repo/'backend/src/index.ts'),
 'compatibility_date': '2026-09-16', 'compatibility_flags': ['nodejs_compat'],
 'vars': {'APP_URL': 'http://localhost:18888', 'ENVIRONMENT': 'development',
          'BETTER_AUTH_SECRET': 'local-ios-test-secret-at-least-thirty-two-characters', 'BETA_INVITE_CODE': 'local-genoa-beta'},
 'd1_databases': [{'binding':'DB','database_name':'genoa-ios-test','database_id':'00000000-0000-0000-0000-000000000000','migrations_dir':str(repo/'backend/migrations')}],
 'r2_buckets': [{'binding':'MEDIA','bucket_name':'genoa-ios-test'}],
 'durable_objects': {'bindings':[{'name':'ROOMS','class_name':'ConversationRoom'}]},
 'migrations':[{'tag':'v1','new_sqlite_classes':['ConversationRoom']}],
 'queues': {'producers':[{'binding':'JOBS','queue':'genoa-ios-test-jobs'}], 'consumers':[{'queue':'genoa-ios-test-jobs','max_batch_size':5,'max_retries':5}]}
}
(root/'wrangler.json').write_text(json.dumps(config))
PY
cd "$repo_root/backend"
./node_modules/.bin/wrangler d1 migrations apply genoa-ios-test --local --config "$test_root/wrangler.json" --persist-to "$test_root/state"
print "Isolated iOS test server: http://localhost:18888 (state: $test_root)"
exec ./node_modules/.bin/wrangler dev --config "$test_root/wrangler.json" --persist-to "$test_root/state" --port 18888 --ip 127.0.0.1
