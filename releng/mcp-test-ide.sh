#!/usr/bin/env bash
#
# Runs MCP script files against a throwaway Eclipse IDE.
#
# Starts the IDE on a workspace of its own, on a port of its own, waits for the
# server, runs the scripts, and takes the IDE down again. Nothing touches the
# workspace anybody is working in, and a failed run leaves no process behind.
#
# Usage: releng/mcp-test-ide.sh [options] (<script.json> ... | --serve)
#
#   --ide DIR        The Eclipse installation to run, on macOS the .app bundle
#                    or the directory holding it. Required unless
#                    ECLIPSE_TEST_IDE is set.
#   --port N         Port for the test server, default 8743. It must not be the
#                    one a working IDE uses, which is the whole point.
#   --workspace DIR  Use this workspace instead of a temporary one, and keep it.
#   --keep           Do not delete the temporary workspace, for looking at the
#                    log after a failure.
#   --junit FILE     Passed to the runner.
#   --timeout SEC    How long to wait for the server to come up, default 180.
#   --shared-config  Use the installation's own configuration area, so the test
#                    IDE runs whatever is substituted into it. Off by default.
#   --bundle JAR     Run this bundle jar instead of the installed one of that
#                    symbolic name, in the private configuration only. Repeatable.
#   --vm JAVA        The java executable, for an installation without a JRE.
#   --serve          Run no scripts: stay up until this script is terminated,
#                    then take the IDE down. Prints the endpoint file when ready.
#                    If the IDE exits on its own, exits with its status and,
#                    when that is not 0, keeps the workspace.
#
# The installation is REUSED, not copied. Only the workspace, the configuration
# area and the p2 data area are fresh: the configuration is 3 MB of the 553 MB,
# and it is where bundles.info lives, which is the file a substitution edits. So
# the test IDE shares every bundle jar and still runs the shipped ones, whatever
# anybody has substituted into the installation somebody works in.
#
# The p2 data area has to be its own, and that is not an optimisation. Sharing
# the installation's one made p2 treat the installation as a shared install and
# rewrite its SDKProfile into a surrogate pointing at this temporary workspace,
# which broke provisioning in the real IDE as soon as the workspace was deleted.

set -euo pipefail

ide=${ECLIPSE_TEST_IDE:-}
port=8743
workspace=
keep=0
junit=
timeout=180
isolate=1
serve=0
vm=
bundles=()
scripts=()

while [ $# -gt 0 ]; do
  case "$1" in
    --ide) ide=$2; shift 2;;
    --port) port=$2; shift 2;;
    --workspace) workspace=$2; keep=1; shift 2;;
    --keep) keep=1; shift;;
    --shared-config) isolate=0; shift;;
    --bundle) bundles+=("$2"); shift 2;;
    --vm) vm=$2; shift 2;;
    --serve) serve=1; shift;;
    --junit) junit=$2; shift 2;;
    --timeout) timeout=$2; shift 2;;
    -h|--help) awk 'NR>1 && /^#/ {sub(/^# ?/,""); print; next} NR>1 {exit}' "$0"; exit 0;;
    *) scripts+=("$1"); shift;;
  esac
done

[ -n "$ide" ] || { echo "Give --ide <installation> or set ECLIPSE_TEST_IDE." >&2; exit 2; }
# on macOS the launcher and the installation sit apart inside the application bundle
launcher=$ide/eclipse
home=$ide
for app in "$ide" "$ide/Eclipse.app" "$ide"/*.app "$ide/../.."; do
  if [ -x "$app/Contents/MacOS/eclipse" ]; then
    launcher=$app/Contents/MacOS/eclipse
    home=$app/Contents/Eclipse
    break
  fi
done
[ -x "$launcher" ] || { echo "No launcher at $launcher, nor an .app bundle with Contents/MacOS/eclipse in $ide." >&2; exit 2; }
[ "$serve" = "1" ] || [ ${#scripts[@]} -gt 0 ] || { echo "Give at least one script file, or --serve." >&2; exit 2; }
if [ "$serve" = "1" ] && { [ ${#scripts[@]} -gt 0 ] || [ -n "$junit" ]; }; then
  echo "--serve runs no scripts, so it does not take script files or --junit." >&2
  exit 2
fi
for jar in ${bundles[@]+"${bundles[@]}"}; do
  [ -f "$jar" ] || { echo "No such bundle jar: $jar" >&2; exit 2; }
done
if [ ${#bundles[@]} -gt 0 ] && [ "$isolate" = "0" ]; then
  echo "--bundle needs the private configuration, not --shared-config." >&2
  exit 2
fi

here=$(cd "$(dirname "$0")" && pwd)

if [ -z "$workspace" ]; then
  workspace=$(mktemp -d -t mcp-test-ide-XXXXXX)
fi
mkdir -p "$workspace"
# absolute, so that holders matches what the launcher passes on
workspace=$(cd "$workspace" && pwd -P)

endpoint="$workspace/.metadata/.plugins/com.vogella.eclipse.mcp.server/endpoint.json"
log="$workspace/.metadata/.log"
pid=
launched=0
runner=

# java or eclipse processes with this whole -data argument, so a sibling "ws 2"
# does not match "ws"; anywhere on the line for a --vm path with spaces
holders() {
  ps -axo pid=,command= | MCP_DATA="-data $workspace -" awk 'index($0 " -", ENVIRON["MCP_DATA"]) && /\/(java|eclipse) / {print $1}'
}

# the launcher can exit while its JVM runs on, which the group check misses
# without setsid (macOS); pid is cleared once reaped, since it may be reused
alive() {
  { [ -n "$pid" ] && { kill -0 -"$pid" 2>/dev/null || kill -0 "$pid" 2>/dev/null; }; } || [ -n "$(holders)" ]
}

show_tails() {
  tail -20 "$workspace/launch.log" >&2 || true
  [ ! -f "$log" ] || tail -20 "$log" >&2
}

# an IDE opened through the chooser has no -data on its command line, but it
# holds the workspace lock, which Java takes as an fcntl lock
workspace_locked() {
  [ -f "$workspace/.metadata/.lock" ] || return 1
  python3 - "$workspace/.metadata/.lock" <<'LOCK'
import fcntl, sys
try:
    with open(sys.argv[1], "r+") as f:
        fcntl.lockf(f, fcntl.LOCK_EX | fcntl.LOCK_NB)
except OSError:
    sys.exit(0)
sys.exit(1)
LOCK
}

# cleanup kills whatever holds the workspace and the run rewrites its settings,
# so it must not be somebody's IDE
in_use=$(holders | paste -s -d ' ' -)
if [ -n "$in_use" ]; then
  echo "The workspace $workspace is in use by pid $in_use." >&2
  exit 2
fi
if workspace_locked; then
  echo "The workspace $workspace is locked by a running IDE." >&2
  exit 2
fi

cleanup() {
  local status=$?
  # a second Ctrl-C during the wait would orphan the IDE
  trap '' INT TERM
  trap - EXIT
  [ -z "$runner" ] || kill -TERM "$runner" 2>/dev/null || true
  # kill the group, then anything still holding the workspace, since the launcher may exit while the JVM runs on
  if [ "$launched" = "1" ]; then
    [ -z "$pid" ] || kill -TERM -"$pid" 2>/dev/null || kill -TERM "$pid" 2>/dev/null || true
    for holder in $(holders); do
      kill -TERM "$holder" 2>/dev/null || true
    done
    for _ in $(seq 1 20); do
      alive || break
      sleep 1
    done
    [ -z "$pid" ] || kill -9 -"$pid" 2>/dev/null || true
    for stray in $(holders); do
      kill -9 "$stray" 2>/dev/null || true
    done
  fi
  if [ "$keep" = "0" ]; then
    rm -rf "$workspace"
  else
    echo "workspace kept at $workspace"
    [ ! -f "$log" ] || echo "log at $log"
  fi
  exit $status
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# Whether the server runs at all is an INSTANCE preference, so it belongs to the
# workspace: a workspace nobody has switched it on in comes up with nothing
# listening and no way left to ask why. Writing it before the first start is the
# only way out of that circle, since the tool that would switch it on is the one
# that cannot be reached.
settings="$workspace/.metadata/.plugins/org.eclipse.core.runtime/.settings"
mkdir -p "$settings"
cat > "$settings/com.vogella.eclipse.mcp.server.prefs" <<PREFS
eclipse.preferences.version=1
enabled=true
port=$port
callTimeoutSeconds=60
PREFS

launch_args=()
if [ "$isolate" = "1" ]; then
  # bundles.info lives in the configuration area and its bundle paths are
  # relative to the installation, so a configuration of our own shares every jar
  # and still decides for itself which ones to load
  config="$workspace/configuration"
  mkdir -p "$config/org.eclipse.equinox.simpleconfigurator"
  cp "$home/configuration/config.ini" "$config/config.ini"
  cp "$home/configuration/org.eclipse.equinox.simpleconfigurator/bundles.info" \
     "$config/org.eclipse.equinox.simpleconfigurator/bundles.info"
  python3 - "$home" "$config" ${bundles[@]+"${bundles[@]}"} <<'PY'
import pathlib, re, sys, zipfile

install, config = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])

# p2's data area is written relative to the configuration directory, so a
# configuration of our own has to say where it is. It gets one of its OWN, and
# that is the whole point rather than a detail: pinning it at the installation
# is what this script used to do, and it corrupted the installation it was
# reusing. A configuration area outside the installation plus a profile
# registry inside it is exactly the shared-install shape p2 is built for, so p2
# built a surrogate profile for the temporary workspace and wrote the surrogate
# markers into the real SDKProfile. A surrogate profile does not own the base
# units, it references them from the shared install, so once the temporary
# workspace was deleted the installation's own profile pointed at nothing and
# every later `p2.director` run failed with "Missing requirement:
# org.eclipse.core.runtime ... could not be found".
# The cost of a private p2 area is that p2's picture of the installation is
# empty inside the test IDE, so eclipse_get_installation and the provisioning
# tools have nothing to report there. That is the right trade: those tools must
# never run in a throwaway IDE anyway, and the alternative was writing to the
# profile of the installation somebody works in.
p2 = config / "p2"
p2.mkdir(parents=True, exist_ok=True)
ini = config / "config.ini"
ini.write_text(re.sub(r"(?m)^eclipse\.p2\.data\.area=.*$",
                      "eclipse.p2.data.area=" + str(p2) + "/", ini.read_text()))

# and any line pointing at a substituted jar goes back to the shipped one, so a
# test never silently measures somebody else's patched bundle
info = config / "org.eclipse.equinox.simpleconfigurator" / "bundles.info"
out, restored = [], []
for line in info.read_text().splitlines():
    parts = line.split(",")
    if len(parts) >= 3 and "mcp-substituted" in parts[2]:
        shipped = sorted((install / "plugins").glob(parts[0] + "_*.jar"))
        if shipped:
            jar = shipped[-1]
            version = jar.stem[len(parts[0]) + 1:]
            parts[1], parts[2] = version, "plugins/" + jar.name
            restored.append(parts[0])
            line = ",".join(parts)
    out.append(line)
info.write_text("\n".join(out) + "\n")
if restored:
    print("restored to the shipped jar: " + ", ".join(restored))

def manifest_header(text, name):
    # header names are case-insensitive
    found = re.search(r"(?mi)^%s: *(.*(?:\r?\n .*)*)" % name, text)
    return re.sub(r"\r?\n ", "", found.group(1)).split(";")[0].strip() if found else None

lines = info.read_text().splitlines()
for jar in map(pathlib.Path, sys.argv[3:]):
    jar = jar.resolve()
    try:
        text = zipfile.ZipFile(jar).read("META-INF/MANIFEST.MF").decode("utf-8")
    except (OSError, KeyError, UnicodeDecodeError, zipfile.BadZipFile) as e:
        sys.exit("%s has no readable manifest: %s" % (jar, e))
    name = manifest_header(text, "Bundle-SymbolicName")
    version = manifest_header(text, "Bundle-Version")
    if not name or not version:
        sys.exit("%s is no OSGi bundle: it lacks Bundle-SymbolicName or Bundle-Version." % jar)
    hits = [i for i, line in enumerate(lines) if line.startswith(name + ",")]
    if not hits:
        sys.exit("%s is %s, which this installation does not have." % (jar, name))
    # the framework would resolve the highest version, which need not be the line replaced
    if len(hits) > 1:
        sys.exit("This installation lists %s %d times, so --bundle cannot tell which to replace." % (name, len(hits)))
    parts = lines[hits[0]].split(",")
    parts[1], parts[2] = version, jar.as_uri()
    lines[hits[0]] = ",".join(parts)
    print("running %s %s from %s" % (name, version, jar))
info.write_text("\n".join(lines) + "\n")
PY
  launch_args+=(-configuration "$config")
  # a reused workspace keeps the framework's cache, which would go on running an earlier jar of this version
  if [ ${#bundles[@]} -gt 0 ] || [ -d "$config/org.eclipse.osgi" ]; then
    launch_args+=(-clean)
  fi
fi

[ -n "$vm" ] && launch_args=(-vm "$vm" ${launch_args[@]+"${launch_args[@]}"})

echo "starting $ide on $workspace, port $port"
# macOS has no setsid; the workspace sweep in cleanup covers what the group kill misses there
detach=()
command -v setsid > /dev/null && detach=(setsid)
# a stale endpoint file plus a foreign listener on the port would read as up
rm -f "$endpoint"
${detach[@]+"${detach[@]}"} "$launcher" -data "$workspace" ${launch_args[@]+"${launch_args[@]}"} -nosplash > "$workspace/launch.log" 2>&1 &
pid=$!
launched=1

deadline=$((SECONDS + timeout))
until [ -f "$endpoint" ] && curl -s -o /dev/null --max-time 2 "http://127.0.0.1:$port/mcp"; do
  if ! alive; then
    echo "the IDE exited before the server came up; last lines of its log:" >&2
    show_tails
    exit 1
  fi
  [ $SECONDS -lt $deadline ] || { echo "the server did not come up within ${timeout}s" >&2; exit 1; }
  sleep 2
done
echo "server up after $((timeout - (deadline - SECONDS)))s"

if [ "$serve" = "1" ]; then
  echo "serving; endpoint file $endpoint"
  while alive; do
    sleep 2
  done
  code=0
  wait "$pid" || code=$?
  pid=
  if [ "$code" != "0" ]; then
    # keep the evidence of a crash rather than deleting it with the workspace
    echo "the IDE exited with status $code; last lines of its log:" >&2
    show_tails
    keep=1
    exit "$code"
  fi
  echo "the IDE exited"
  exit 0
fi

args=(--workspace "$workspace")
[ -n "$junit" ] && args+=(--junit "$junit")
# in the background, so a TERM is handled at once rather than after the run
"$here/mcp-script.py" "${args[@]}" "${scripts[@]}" &
runner=$!
code=0
wait "$runner" || code=$?
runner=
exit "$code"
