#!/usr/bin/env bash
# Runs ZAP's baseline scan (.github/zap/baseline.yaml) against the app the Compose stack is running,
# from inside the stack's network, and writes the reports to the given folder:
#
#     .github/scripts/zap-scan.sh /tmp/zap
#
# zap.html is the report, zap.sarif.json the same alerts for code scanning and zap.json for this
# script, which lists any alert the plan doesn't accept. Exits 0 when there are none, 2 when there
# are, and anything else when the scan itself failed. Run from the root of the checkout, with the
# stack up.
set -euo pipefail

out=$1
mkdir -p "$out"
# ZAP runs as a user of its own, which writes the reports there.
chmod 777 "$out"

app=$(docker compose --profile app ps --quiet app)
if [ -z "$app" ]; then
  echo "The app isn't running. Start it with: docker compose --profile app up --build --wait" >&2
  exit 1
fi
network=$(docker inspect "$app" --format '{{range $name, $_ := .NetworkSettings.Networks}}{{$name}}{{end}}')
image=$(sed -n 's/^FROM //p' .github/zap/Dockerfile)

# The pinned add-ons, checked against their hashes and mounted where ZAP loads its own.
addons=$(mktemp -d)
trap 'rm -r -- "$addons"' EXIT
mounts=()
while read -r url sha; do
  file=${url##*/}
  curl -fsSL --retry 3 -o "$addons/$file" "$url"
  echo "$sha  $addons/$file" | sha256sum --check --quiet
  mounts+=(-v "$addons/$file:/zap/plugin/$file:ro")
done < <(grep -v '^#' .github/zap/addons.txt)

# -silent: no update checks or other requests of ZAP's own, so it runs as pinned.
docker run --rm --network "$network" -v "$out:/zap/wrk" "${mounts[@]}" \
  -v "$PWD/.github/zap/baseline.yaml:/zap/baseline.yaml:ro" "$image" \
  zap.sh -cmd -silent -autorun /zap/baseline.yaml

# Every alert left once the plan's filters have marked the accepted ones as false positives.
python3 - "$out/zap.json" <<'PY'
import json, sys

alerts = [
    alert
    for site in json.load(open(sys.argv[1]))["site"]
    for alert in site["alerts"]
    if alert["confidence"] != "0" and alert["instances"]
]
for alert in alerts:
    print(f"ALERT: {alert['name']} [{alert['pluginid']}], {alert['riskdesc']}")
    for instance in alert["instances"][:5]:
        print(f"    {instance['method']} {instance['uri']} {instance.get('param', '')}")
print(f"Alerts the plan doesn't accept: {len(alerts)}")
sys.exit(2 if alerts else 0)
PY
