#!/usr/bin/env bash
# Runs ZAP's baseline scan (.github/zap/baseline.yaml) against the app the Compose stack is running,
# from inside the stack's network, and writes the reports to the given folder:
#
#     .github/scripts/zap-scan.sh /tmp/zap
#
# zap.html is the report, code-scanning.sarif the same alerts for GitHub's code scanning, and
# zap.json is for this script, which lists any alert the plan doesn't accept. Exits 0 when there
# are none, 2 when there are, and anything else when the scan itself failed or didn't run as
# planned. Run from the root of the checkout, with the stack up.
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

# -silent: no update checks or other requests of ZAP's own, so it runs as pinned. ZAP exits 1 when
# the plan fails and 2 when it has warnings, such as a rule it doesn't know: both mean the scan
# isn't the one the plan describes, so neither is reported as an alert.
zap=0
docker run --rm --network "$network" -v "$out:/zap/wrk" "${mounts[@]}" \
  -v "$PWD/.github/zap/baseline.yaml:/zap/baseline.yaml:ro" "$image" \
  zap.sh -cmd -silent -autorun /zap/baseline.yaml || zap=$?
if [ "$zap" != 0 ]; then
  echo "ZAP's plan failed or had warnings (exit $zap). See its log above." >&2
  exit 3
fi

# Code scanning only takes locations in the repository, so each alert's URL becomes its path, as if
# it were a file, and the full URL leads its message. A built asset's path drops its content hash,
# so an alert on it stays the same alert from one build to the next.
# A file of its own: ZAP's, owned by the user it runs as, may not be writable here.
python3 - "$out/zap.sarif.json" "$out/code-scanning.sarif" <<'PY'
import json, re, sys
from urllib.parse import urlsplit

sarif = json.load(open(sys.argv[1]))
for result in sarif["runs"][0]["results"]:
    for location in result["locations"]:
        artifact = location["physicalLocation"]["artifactLocation"]
        url = urlsplit(artifact["uri"])
        path = re.sub(r"^(assets/.+)-[\w-]{8}(\.\w+)$", r"\1\2", url.path.lstrip("/"))
        artifact["uri"] = (path or "index.html") + (f"?{url.query}" if url.query else "")
        result["message"]["text"] = f"{url.geturl()}\n\n{result['message']['text']}"
json.dump(sarif, open(sys.argv[2], "w"), indent=2)
PY

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
