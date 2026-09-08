#!/usr/bin/env bash
# Áp deploy/rabbitmq/topology.yaml lên broker.
# Idempotent: chạy lại nhiều lần không sao.
#
#   ./scripts/apply-rabbitmq-topology.sh [host] [user] [pass]
set -euo pipefail

HOST="${1:-localhost:15672}"
USER="${2:-nexaticket}"
PASS="${3:-nexaticket}"
TOPOLOGY="$(dirname "$0")/../deploy/rabbitmq/topology.yaml"
VHOST="%2F"

api() {
  curl -fsS -u "$USER:$PASS" -H 'content-type: application/json' "$@"
}

echo "==> Áp topology lên $HOST"

# --- exchanges ---
python3 - "$TOPOLOGY" <<'PY' | while IFS=$'\t' read -r name type args; do
import sys, yaml, json
spec = yaml.safe_load(open(sys.argv[1], encoding='utf-8'))
for ex in spec.get('exchanges', []):
    print('\t'.join([ex['name'], ex['type'], json.dumps(ex.get('arguments', {}))]))
PY
  echo "    exchange $name ($type)"
  api -X PUT "http://$HOST/api/exchanges/$VHOST/$name" \
      -d "{\"type\":\"$type\",\"durable\":true,\"arguments\":$args}"
done

# --- queues + bindings + DLQ ---
python3 - "$TOPOLOGY" <<'PY' | while IFS=$'\t' read -r name exchange key consumers; do
import sys, yaml
spec = yaml.safe_load(open(sys.argv[1], encoding='utf-8'))
for q in spec.get('queues', []):
    print('\t'.join([q['name'], q['exchange'], q['routing_key'], str(q.get('consumers', 0))]))
PY
  echo "    queue $name  <- $exchange / $key"
  api -X PUT "http://$HOST/api/queues/$VHOST/$name" -d '{
      "durable": true,
      "arguments": {
        "x-queue-type": "quorum",
        "x-dead-letter-exchange": "nexaticket.dlx",
        "x-dead-letter-routing-key": "'"$name"'"
      }}'
  api -X POST "http://$HOST/api/bindings/$VHOST/e/$exchange/q/$name" \
      -d "{\"routing_key\":\"$key\"}"

  api -X PUT "http://$HOST/api/queues/$VHOST/$name.dlq" \
      -d '{"durable": true, "arguments": {"x-queue-type": "quorum"}}'
  api -X POST "http://$HOST/api/bindings/$VHOST/e/nexaticket.dlx/q/$name.dlq" \
      -d "{\"routing_key\":\"$name\"}"
done

echo "==> Xong"
