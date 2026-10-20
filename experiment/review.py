#!/usr/bin/env python3
"""Agent checks of a suite (experiment/control/CHECKS.md): one new folder and one headless session per
batch, and the calibration score.

  experiment/review.py prepare <workdir> --suite <repo> <ref> BATCH...
  experiment/review.py prepare <workdir> --sample <dir> BATCH...
  experiment/review.py run <workdir> [BATCH...] [--budget USD] [--dry-run]
  experiment/review.py score <workdir> <answer key>

prepare  For each batch: <workdir>/<batch>/ holds the specification and the suite, or the calibration sample in
         place of api-tests/, without the service sources, the generating agent's plan and report, run reports
         or git (decision D9); and <workdir>/<batch>.brief.txt: the common brief, the note on running three
         checks in one session (D10), the briefs of CHK-1, CHK-3A and CHK-5 verbatim from CHECKS.md, and the
         batch's tests (experiment/review-batches.txt). A session never runs in a folder another one used.
run      claude -p in each batch folder: safe mode, the generation model and effort, tools limited to reading
         and writing files, a budget cap per session, a minimal environment. The result JSON (cost, duration, usage) goes to
         <workdir>/<batch>.result.json; the verdicts to <workdir>/<batch>/review-out/<CHECK>-<batch>.jsonl.
score    The calibration rule of CHECKS.md §4: every expected FLAG is FLAG and every expected PASS is PASS;
         UNCLEAR, a missing or a duplicate verdict is a miss. A kind mismatch is reported but does not count.
"""
import argparse
import csv
import glob
import hashlib
import io
import json
import os
import re
import shutil
import subprocess
import sys
import tarfile
import time

ROOT = os.path.dirname(os.path.dirname(os.path.realpath(__file__)))
CHECKS_MD = os.path.join(ROOT, 'experiment', 'control', 'CHECKS.md')
BATCHES = os.path.join(ROOT, 'experiment', 'review-batches.txt')
SNAPSHOT = os.path.join(ROOT, 'experiment', 'snapshot.sh')
CHECKS = ('CHK-1', 'CHK-3A', 'CHK-5')
BASE = 'sut-v2'
# D9: the checking agents get the specification and the tests, not the code or the generating agent's account.
REMOVE = ('player-service', 'analytics-service', 'proto', 'infra', '.git')
SUITE_SKIP = ('api-tests/results', 'api-tests/target', 'api-tests/TEST-PLAN.md', 'api-tests/TEST-REPORT.md')
SESSION_TIMEOUT = 60 * 60


def clean_env():
    """A minimal environment, so nothing of the caller (paths, session variables) reaches the agent."""
    claude = shutil.which('claude') or die('claude is not on PATH')
    env = {k: os.environ[k] for k in ('HOME', 'USER', 'LOGNAME') if k in os.environ}
    env.update(LANG='en_US.UTF-8', DISABLE_AUTOUPDATER='1',
               PATH=os.pathsep.join([os.path.dirname(claude), '/usr/bin', '/bin', '/usr/sbin', '/sbin']))
    return env

D10_NOTE = '''This session performs three checks on the same batch: CHK-1, CHK-3A and CHK-5. Their briefs follow.
Judge every test of the batch for each check separately, and write one file per check:
review-out/CHK-1-{batch}.jsonl, review-out/CHK-3A-{batch}.jsonl and review-out/CHK-5-{batch}.jsonl.
Each check stays inside its own scope: what a brief lists under "Not in scope" is not a finding of that check.'''


def die(msg):
    sys.exit(f'error: {msg}')


def briefs():
    """The common brief and the three check briefs, exactly as CHECKS.md states them."""
    text = open(CHECKS_MD).read()
    common = re.search(r'### 3\.0 .*?```text\n(.*?)```', text, re.S)
    out = {'common': common.group(1).rstrip()} if common else die('no common brief in CHECKS.md')
    for check in CHECKS:
        m = re.search(r'#{3,4} ' + re.escape(check) + r' .*?\*\*Brief:\*\*\s*```text\n(.*?)```', text, re.S)
        out[check] = m.group(1).rstrip() if m else die(f'no brief of {check} in CHECKS.md')
    return out


def tests_in(java):
    """[(method, display name)] of the @Test and @ParameterizedTest methods of one class, in source order."""
    src = open(java).read()
    token = re.compile(r'@(?:Test|ParameterizedTest)\b|@DisplayName\(\s*"((?:[^"\\]|\\.)*)"\s*\)|\bvoid\s+(\w+)\s*\(')
    out, is_test, name = [], False, ''
    for m in token.finditer(src):
        if m.group(0).startswith('@Test') or m.group(0).startswith('@ParameterizedTest'):
            is_test = True
        elif m.group(1) is not None:
            name = m.group(1)
        else:
            if is_test:
                out.append((m.group(2), name))
            is_test, name = False, ''
    return out


def batch_spec():
    spec = {}
    for line in open(BATCHES):
        line = line.split('#', 1)[0].strip()
        if line:
            parts = line.split()
            rng = None
            if len(parts) > 2:
                lo, hi = parts[2].split('..')
                rng = (int(lo[3:]), int(hi[3:]))
            spec.setdefault(parts[0], []).append((parts[1], rng))
    return spec


def batch_tests(suite_dir, batch):
    """['Class#method — display name'] of one batch, resolved against the suite's source."""
    spec = batch_spec().get(batch) or die(f'unknown batch {batch} (see {BATCHES})')
    out = []
    for cls, rng in spec:
        files = glob.glob(os.path.join(suite_dir, 'src', 'test', 'java', '**', cls + '.java'), recursive=True)
        if len(files) != 1:
            die(f'{batch}: class {cls} found {len(files)} times in {suite_dir}')
        for method, name in tests_in(files[0]):
            cl = re.match(r'CL-(\d+)', name)
            if rng and not (cl and rng[0] <= int(cl.group(1)) <= rng[1]):
                continue
            out.append(f'{cls}#{method} — {name}')
    if not out:
        die(f'{batch}: no tests')
    return out


def sha(text):
    return hashlib.sha256(text.encode()).hexdigest()[:12]


def prepare(args):
    os.makedirs(args.workdir, exist_ok=True)
    work = os.path.realpath(args.workdir)
    b = briefs()
    manifest_path = os.path.join(work, 'manifest.json')
    manifest = json.load(open(manifest_path)) if os.path.exists(manifest_path) else {'batches': {}}
    manifest.update({'checks_md': sha(open(CHECKS_MD).read()),
                     'control_commit': subprocess.run(['git', '-C', ROOT, 'log', '-1', '--format=%h', '--',
                                                       'experiment/control'], capture_output=True,
                                                      text=True).stdout.strip()})
    if args.suite:
        repo, ref = os.path.realpath(args.suite[0]), args.suite[1]
        commit = subprocess.run(['git', '-C', repo, 'rev-parse', '--short', ref + '^{commit}'],
                                capture_output=True, text=True, check=True).stdout.strip()
        manifest['source'] = {'suite': repo, 'ref': ref, 'commit': commit}
    else:
        manifest['source'] = {'sample': os.path.realpath(args.sample)}
    for batch in args.batches:
        dest = os.path.join(work, batch)
        if os.path.exists(dest):
            die(f'{dest} exists: every session needs a new folder')
        subprocess.run([SNAPSHOT, 'B', dest, BASE], check=True, stdout=subprocess.DEVNULL)
        for name in REMOVE:
            shutil.rmtree(os.path.join(dest, name), ignore_errors=True)
        if args.suite:
            data = subprocess.run(['git', '-C', repo, 'archive', '--format=tar', ref, '--', 'api-tests'],
                                  capture_output=True, check=True).stdout
            with tarfile.open(fileobj=io.BytesIO(data)) as tar:
                keep = [m for m in tar.getmembers() if not m.name.startswith(SUITE_SKIP)]
                tar.extractall(dest, members=keep, filter='data')
        else:
            shutil.copytree(args.sample, os.path.join(dest, 'api-tests'), ignore=shutil.ignore_patterns('target'))
        os.makedirs(os.path.join(dest, 'review-out'))
        tests = batch_tests(os.path.join(dest, 'api-tests'), batch)
        brief = '\n\n'.join([b['common'], D10_NOTE.format(batch=batch), b['CHK-1'], b['CHK-3A'], b['CHK-5'],
                             f'Batch {batch}: {len(tests)} tests.\n' + '\n'.join('- ' + t for t in tests)]) + '\n'
        with open(os.path.join(work, batch + '.brief.txt'), 'w') as f:
            f.write(brief)
        manifest['batches'][batch] = {'folder': dest, 'tests': [t.split(' — ')[0] for t in tests],
                                      'brief_sha': sha(brief)}
        print(f'{batch}: {len(tests)} tests, brief {sha(brief)}, folder {dest}')
    with open(manifest_path, 'w') as f:
        json.dump(manifest, f, indent=1)


def verdicts(folder, batch):
    """{check: {test: [verdict, ...]}} from one batch folder; lines that are not JSON are kept as errors."""
    out = {}
    for check in CHECKS:
        rows = out.setdefault(check, {})
        path = os.path.join(folder, 'review-out', f'{check}-{batch}.jsonl')
        if not os.path.exists(path):
            continue
        for line in open(path):
            if line.strip():
                try:
                    v = json.loads(line)
                    rows.setdefault(v.get('test', '?'), []).append(v)
                except ValueError:
                    rows.setdefault('?', []).append({'verdict': 'NOT_JSON'})
    return out


def run(args):
    work = os.path.realpath(args.workdir)
    manifest = json.load(open(os.path.join(work, 'manifest.json')))
    for batch in args.batches or sorted(manifest['batches']):
        info = manifest['batches'][batch]
        result = os.path.join(work, batch + '.result.json')
        if os.path.exists(result):
            print(f'{batch}: already run, skipped')
            continue
        cmd = ['claude', '-p', '--safe-mode', '--model', 'claude-opus-5-5', '--effort', 'xhigh',
               '--permission-mode', 'acceptEdits', '--tools', 'Read,Glob,Grep,Write',
               '--output-format', 'json', '--max-budget-usd', str(args.budget)]
        if args.dry_run:
            print(f'{batch}: cd {info["folder"]} && env -i <minimal env> {" ".join(cmd)} < {batch}.brief.txt')
            continue
        t0 = time.time()
        with open(os.path.join(work, batch + '.brief.txt')) as brief, open(result, 'w') as out, \
                open(os.path.join(work, batch + '.stderr.log'), 'w') as err:
            try:
                rc = subprocess.run(cmd, stdin=brief, stdout=out, stderr=err, cwd=info['folder'],
                                    env=clean_env(), timeout=SESSION_TIMEOUT).returncode
            except subprocess.TimeoutExpired:
                rc = 'timeout'
        try:
            r = json.load(open(result))
        except ValueError:
            r = {}
        got = verdicts(info['folder'], batch)
        counts = {c: f'{sum(len(v) for v in got[c].values())}/{len(info["tests"])}' for c in CHECKS}
        print(f'{batch}: exit {rc}, {round(time.time() - t0)} s, ${r.get("total_cost_usd", "?")}, '
              f'verdicts {counts}', flush=True)


def score(args):
    work = os.path.realpath(args.workdir)
    manifest = json.load(open(os.path.join(work, 'manifest.json')))
    block = re.search(r'```csv\n(.*?)```', open(args.key).read(), re.S) or die('no csv block in the key')
    key = {row['test']: row for row in csv.DictReader(io.StringIO(block.group(1)))}
    got = {c: {} for c in CHECKS}
    for batch, info in manifest['batches'].items():
        for c, rows in verdicts(info['folder'], batch).items():
            for test, vs in rows.items():
                got[c].setdefault(test, []).extend(vs)
    family = {'CHK-1': 'E', 'CHK-3A': 'L', 'CHK-5': 'H'}
    ok = True
    for c in CHECKS:
        misses, kinds = [], []
        for test, row in key.items():
            vs = got[c].get(test, [])
            want = row[c]
            if len(vs) != 1:
                misses.append(f'{test}: {len(vs)} verdicts, want {want}')
            elif vs[0].get('verdict') != want:
                misses.append(f'{test}: {vs[0].get("verdict")}, want {want}')
            elif want == 'FLAG' and row['defect'].startswith(family[c]) and vs[0].get('kind') != row['defect']:
                kinds.append(f'{test}: kind {vs[0].get("kind")}, key {row["defect"]}')
        extra = sorted(set(got[c]) - set(key))
        ok = ok and not misses
        print(f'{c}: {"PASS" if not misses else "FAIL"} — {len(key) - len(misses)}/{len(key)} as expected')
        for line in misses:
            print(f'  miss  {line}')
        for line in kinds:
            print(f'  kind  {line}')
        for test in extra:
            print(f'  extra {test}')
    return 0 if ok else 1


def main():
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = p.add_subparsers(dest='cmd', required=True)
    pp = sub.add_parser('prepare')
    pp.add_argument('workdir')
    src = pp.add_mutually_exclusive_group(required=True)
    src.add_argument('--suite', nargs=2, metavar=('REPO', 'REF'))
    src.add_argument('--sample')
    pp.add_argument('batches', nargs='+')
    pr = sub.add_parser('run')
    pr.add_argument('workdir')
    pr.add_argument('batches', nargs='*')
    pr.add_argument('--budget', type=float, default=10.0, help='cap per session, USD (default 10)')
    pr.add_argument('--dry-run', action='store_true')
    ps = sub.add_parser('score')
    ps.add_argument('workdir')
    ps.add_argument('key')
    args = p.parse_args()
    return {'prepare': prepare, 'run': run, 'score': score}[args.cmd](args)


if __name__ == '__main__':
    sys.exit(main())
