#!/usr/bin/env python3
"""Runs one generated test suite against the stand: on the base and with each patch, every run
from a fresh stand, and records the outcome of every test in every run.

  experiment/matrix.py <suite repo> <ref> <out dir> [--bugs-dir DIR] [--keep-stand] [--keep-work] COLUMN...

  <suite repo>  git repository that holds the suite in api-tests/ (a generation or control folder)
  <ref>         commit or tag of the suite (A, B, C1, ...)
  COLUMN        `base`, or a patch ID in the bugs directory (bugs.sh AQC_BUGS_DIR)

For each column: `bugs.sh off --fresh` (base) or `bugs.sh on <ID> --fresh`, so every run starts
from the same data, clock and stub; the suite exported from <ref> without its stored reports into
a new work folder; `mvn -B test` there; the Surefire XML collected. Fresh infrastructure also
resets the payment-provider stub. At the end the stand goes back to the base with `off --fresh`
unless --keep-stand. Maven runs with the caller's JAVA_HOME (recorded in summary.json): run every
suite on the JDK its generating agent used.

Output in <out dir>: <column>/ (reports, mvn.log, stand.log, summary.json) and matrix.csv with one
row per test (class, name) and one column per run: pass, FAIL, ERROR, skip, or - (not run).
"""
import argparse
import csv
import glob
import json
import os
import shutil
import subprocess
import sys
import tarfile
import io
import time
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.realpath(__file__)))
BUGS = os.path.join(ROOT, 'experiment', 'bugs.sh')
CHECK = os.path.join(ROOT, 'experiment', 'stand-check.sh')
MVN_TIMEOUT = 15 * 60


def sh(cmd, log, env=None, cwd=None, timeout=None):
    with open(log, 'a') as f:
        f.write(f'$ {" ".join(cmd)}\n')
        f.flush()
        try:
            return subprocess.run(cmd, stdout=f, stderr=subprocess.STDOUT, env=env, cwd=cwd, timeout=timeout).returncode
        except subprocess.TimeoutExpired:
            f.write(f'timeout after {timeout} s\n')
            return 'timeout'


def export_suite(repo, ref, dest):
    """api-tests/ of <ref> into dest, without the generating run's reports."""
    data = subprocess.run(['git', '-C', repo, 'archive', '--format=tar', ref, '--', 'api-tests'],
                          capture_output=True, check=True).stdout
    with tarfile.open(fileobj=io.BytesIO(data)) as tar:
        members = [m for m in tar.getmembers() if not m.name.startswith('api-tests/results')]
        tar.extractall(dest, members=members, filter='data')
    return os.path.join(dest, 'api-tests')


def outcomes(folder):
    """{(class, name): outcome} from every Surefire XML under folder."""
    out = {}
    for path in glob.glob(os.path.join(folder, '**', 'TEST-*.xml'), recursive=True):
        for case in ET.parse(path).getroot().iter('testcase'):
            key = (case.get('classname', ''), case.get('name', ''))
            if case.find('failure') is not None:
                res = 'FAIL'
            elif case.find('error') is not None:
                res = 'ERROR'
            elif case.find('skipped') is not None:
                res = 'skip'
            else:
                res = 'pass'
            n, k = 1, key
            while k in out:  # a name repeated within the suite: keep every execution
                n += 1
                k = (key[0], f'{key[1]} #{n}')
            out[k] = res
    return out


def run_column(args, column, out):
    cdir = os.path.join(out, column)
    if os.path.exists(cdir):
        shutil.rmtree(cdir)
    os.makedirs(cdir)
    stand_log, mvn_log = os.path.join(cdir, 'stand.log'), os.path.join(cdir, 'mvn.log')
    env = dict(os.environ)
    if args.bugs_dir:
        env['AQC_BUGS_DIR'] = os.path.realpath(args.bugs_dir)
    summary = {'column': column, 'suite': args.repo, 'ref': args.ref,
               'commit': subprocess.run(['git', '-C', args.repo, 'rev-parse', '--short', args.ref + '^{commit}'],
                                        capture_output=True, text=True).stdout.strip(),
               'java_home': os.environ.get('JAVA_HOME', ''),
               'started': time.strftime('%Y-%m-%dT%H:%M:%S%z')}

    switch = [BUGS, 'off', '--fresh'] if column == 'base' else [BUGS, 'on', column, '--fresh']
    t0 = time.time()
    rc = sh(switch, stand_log, env=env)
    summary['switch_s'] = round(time.time() - t0)
    if rc != 0:
        summary['error'] = f'stand switch failed ({rc})'
        return summary
    if column == 'base':
        summary['stand_check'] = sh([CHECK], stand_log) == 0
    status = subprocess.run([BUGS, 'status'], capture_output=True, text=True, env=env).stdout.splitlines()
    summary['stand'] = status[0] if status else ''

    work = os.path.join(cdir, 'work')
    suite = export_suite(args.repo, args.ref, work)
    t0 = time.time()
    rc = sh(['mvn', '-B', 'test'], mvn_log, cwd=suite, timeout=MVN_TIMEOUT)
    summary['mvn_s'] = round(time.time() - t0)
    summary['mvn_exit'] = rc

    reports = os.path.join(cdir, 'reports')
    os.makedirs(reports)
    for path in glob.glob(os.path.join(suite, '**', 'TEST-*.xml'), recursive=True):
        shutil.copy(path, reports)
    res = outcomes(reports)
    summary['tests'] = len(res)
    for kind in ('pass', 'FAIL', 'ERROR', 'skip'):
        summary[kind] = sum(1 for v in res.values() if v == kind)
    with open(os.path.join(cdir, 'outcomes.json'), 'w') as f:
        json.dump([[c, n, v] for (c, n), v in sorted(res.items())], f, indent=0)
    if not args.keep_work:
        shutil.rmtree(work)
    return summary


def write_matrix(out, columns):
    res = {}
    for col in columns:
        path = os.path.join(out, col, 'outcomes.json')
        if os.path.exists(path):
            res[col] = {(c, n): v for c, n, v in json.load(open(path))}
    keys = sorted({k for r in res.values() for k in r})
    with open(os.path.join(out, 'matrix.csv'), 'w', newline='') as f:
        w = csv.writer(f)
        w.writerow(['class', 'test'] + columns)
        for k in keys:
            w.writerow([k[0], k[1]] + [res.get(col, {}).get(k, '-') for col in columns])


def main():
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument('repo')
    p.add_argument('ref')
    p.add_argument('out')
    p.add_argument('columns', nargs='+')
    p.add_argument('--bugs-dir')
    p.add_argument('--keep-stand', action='store_true', help='leave the last column running')
    p.add_argument('--keep-work', action='store_true', help='keep the exported suite and its build')
    args = p.parse_args()
    args.repo = os.path.realpath(args.repo)
    os.makedirs(args.out, exist_ok=True)

    for col in args.columns:
        s = run_column(args, col, args.out)
        with open(os.path.join(args.out, col, 'summary.json'), 'w') as f:
            json.dump(s, f, indent=1)
        print(json.dumps(s), flush=True)
        write_matrix(args.out, args.columns)

    if not args.keep_stand:
        rc = sh([BUGS, 'off', '--fresh'], os.path.join(args.out, 'restore.log'))
        print(f'stand back to the base: {"ok" if rc == 0 else "FAILED"}', flush=True)


if __name__ == '__main__':
    sys.exit(main())
