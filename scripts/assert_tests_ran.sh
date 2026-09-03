#!/usr/bin/env bash
# Guards against silently-green 0-test runs: a GMD invocation whose
# instrumentation matches nothing exits 0 with 'Starting 0 tests', and a
# Gradle unit-test filter/config typo can just as silently run zero JVM
# tests. Parse the JUnit XML, print the executed count, and fail when
# nothing ran.
#
# Usage: assert_tests_ran.sh <min-tests> [gradle-module-dir] [kind]
#   kind is "androidTest" (default, GMD / connected) or "unit"
#     (JVM unit tests under build/test-results).
# The module dir defaults to "app"; pass e.g. "shippedsmoke" for lanes
# whose instrumentation lives in another module.
set -u
MIN="${1:-1}"
export RESULTS_ROOT="${2:-app}"
export RESULTS_KIND="${3:-androidTest}"

count=$(python3 - <<'PY'
import glob, os, xml.etree.ElementTree as ET
root_dir = os.environ.get('RESULTS_ROOT', 'app')
kind = os.environ.get('RESULTS_KIND', 'androidTest')
if kind == 'unit':
    patterns = (f'{root_dir}/**/build/test-results/**/*.xml',)
elif kind == 'androidTest':
    patterns = (f'{root_dir}/**/build/outputs/androidTest-results/**/*.xml',)
else:
    raise SystemExit(f'unknown RESULTS_KIND={kind!r}')
total = 0
for f in sorted(set(p for pat in patterns for p in glob.glob(pat, recursive=True))):
    try:
        root = ET.parse(f).getroot()
    except Exception:
        continue
    total += len(list(root.iter('testcase')))
print(total)
PY
)

echo "executed-test-count: ${count} ${RESULTS_KIND} tests ran under ${RESULTS_ROOT} in this job"
if [ "${count}" -lt "${MIN}" ]; then
  echo "ERROR: Only ${count} tests ran (expected >= ${MIN}) - the filter or runner matched nothing; treating as failure."
  python3 - <<'PY'
import glob, os, xml.etree.ElementTree as ET
root_dir = os.environ.get('RESULTS_ROOT', 'app')
kind = os.environ.get('RESULTS_KIND', 'androidTest')
if kind == 'unit':
    patterns = (f'{root_dir}/**/build/test-results/**/*.xml',)
else:
    patterns = (f'{root_dir}/**/build/outputs/androidTest-results/**/*.xml',)
files = sorted(set(p for pat in patterns for p in glob.glob(pat, recursive=True)))
if not files:
    print(f"ERROR: No JUnit XML found at all under {kind} results")
for f in files[:4]:
    try:
        root = ET.parse(f).getroot()
    except Exception as e:
        print(f"ERROR: {f}: {e}")
        continue
    bits = [f"file={f}", f"root=<{root.tag} {dict(root.attrib)}>"]
    for suite in ([root] if root.tag == 'testsuite' else root.iter('testsuite')):
        bits.append(f"suite={dict(suite.attrib)}")
    for tag in ('system-out', 'system-err'):
        for node in root.iter(tag):
            txt = (node.text or '').strip()
            if txt:
                bits.append(f"{tag}={txt[:700]}")
    text = '%0A'.join(b.replace('%', '%25').replace('\n', ' ') for b in bits)[:3800]
    print(f"ERROR: {text}")
PY
  python3 - <<'PY'
import glob, os, re
root_dir = os.environ.get('RESULTS_ROOT', 'app')
roots = [
    f'{root_dir}/build/outputs/androidTest-results',
    f'{root_dir}/build/intermediates/managed_device_android_test_additional_output',
    f'{root_dir}/build/intermediates/utp',
    f'{root_dir}/build/test-results',
]
files = []
for r in roots:
    for pat in ('**/*.log', '**/*.txt', '**/*output*'):
        files += [f for f in glob.glob(os.path.join(r, pat), recursive=True) if os.path.isfile(f)]
files = sorted(set(files), key=os.path.getsize, reverse=True)
listing = ' | '.join(f"{f}({os.path.getsize(f)}B)" for f in files[:15]) or 'no log/txt files found'
print(f"ERROR: {listing[:3800]}")
for f in files[:3]:
    try:
        data = open(f, encoding='utf-8', errors='replace').read()
    except Exception:
        continue
    lines = [l for l in data.splitlines() if re.search(
        r'INSTRUMENTATION|numtests|TestRequestBuilder|TestLoader|ClassPathScanner|Exception|Error|error', l)]
    if not lines:
        lines = data.splitlines()[-30:]
    text = '%0A'.join(l[:300].replace('%', '%25') for l in lines[-40:])[:3800]
    print(f"ERROR title=utp-log:{os.path.basename(f)}::{text}")
PY
  exit 1
fi
exit 0
