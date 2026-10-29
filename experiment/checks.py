#!/usr/bin/env python3
"""Script checks of a suite (experiment/control/CHECKS.md): CHK-3S, CHK-2, CHK-6 and the CHK-4 analysis.

  experiment/checks.py tags <out> (--suite <repo> <ref> | --sample <dir>) [--expect N]
  experiment/checks.py stability <out> <repo> <ref> [--analyze]
  experiment/checks.py order <out> <repo> <ref> [--analyze]
  experiment/checks.py faults <out> <matrix dir> <MUTANTS.md>
  experiment/checks.py audit <out> <agent workdir> [--seed N] [--force]
  experiment/checks.py assemble <out> <agent workdir>

tags       CHK-3S. Every @Test and @ParameterizedTest method with its class- and method-level @Tag values and
           its class's @Order -> <out>/tagmap.csv; sub-checks S1-S5 -> <out>/chk3s/findings.jsonl; S6, the tags
           against the TEST-PLAN.md checklist (for the human only, never for agents) -> <out>/chk3s/plan-tags.csv.
           --expect stops when the parser finds another number of tests (a line-based parse finds 132 of 136).
stability  CHK-2. Ten runs of the suite exported from <ref>, each in a new copy: runs 1-5 each on a fresh stand
           (bugs.sh off --fresh), runs 6-10 one after another on the stand that run 5 left. The service clock is
           read before every run. A run where more than 10 % of the executions differ from their majority outcome
           is disturbed and repeated once: an aged run straight away on the aged stand, a fresh run on a fresh
           stand. A repeat that is disturbed too is kept beside the first, otherwise it replaces it.
           -> <out>/chk2/stability.csv (per execution), tests.csv (per test), findings.jsonl, info.jsonl.
order      CHK-6 (needs CHK-2). On a fresh stand, every class alone in the suite's class order, then on a fresh
           stand the whole suite once in reverse class order, InvariantTest last. A variant in which a test
           differs from its CHK-2 class is repeated once, from a fresh stand, as a whole: the classes alone run
           on one stand, so each one starts from the state the classes before it left.
           -> <out>/chk6/order.csv, findings.jsonl.
faults     CHK-4 over matrix.py's matrix.csv and failures.csv (needs CHK-2). An execution detects a faulty version
           when it is STABLE_GREEN in CHK-2 and red there, or STABLE_RED with one normalized first failure line in
           every CHK-2 run and red there with another line (D4 with its clarification). A test detects when one of
           its executions does: worst-of per test (D7) for every test that is not both green and red on the base
           (such a test is reported as MIXED_ON_BASE).
           -> <out>/chk4/faults.csv, green_own.csv, findings.jsonl, info.jsonl.
audit      §5 (needs the agent checks and every script check): contradictions between agent verdicts and script
           results, every CHK-3A L3, every UNCLEAR, and a seeded random 10 % of the other FLAGs and 5 % of the
           other PASSes per agent check (at least 5 each) -> <out>/audit/AUDIT.md, one item per verdict with a
           "Human:" line for the mark.
assemble   §6 after the audit: the FLAGs that the audit did not mark wrong (only the audited ones of a check
           whose audited verdicts are more than 10 % wrong), the script findings, each test-level finding with
           its CHK-2 verdict, CHK-4 pairs and CHK-5 kind, and the protected list -> <out>/findings.json.

Executions map to tests through tagmap.csv: Surefire names an execution by the display name, followed by the
invocation name for a parameterized test. --analyze recomputes the verdicts from the stored runs. The stand
goes back to the base with off --fresh after stability and order. Run Maven with the JDK of the generating agent.
"""
import argparse
import collections
import csv
import glob
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.request

sys.dont_write_bytecode = True  # no __pycache__ in experiment/
sys.path.insert(0, os.path.dirname(os.path.realpath(__file__)))
import matrix  # noqa: E402  the stand switch, suite export and Surefire parsing of the matrix

ROOT = os.path.dirname(os.path.dirname(os.path.realpath(__file__)))
BUGS, CHECK = matrix.BUGS, matrix.CHECK
BASE = 'sut-v2'
API = 'http://localhost:8080'
SPEC_ID = re.compile(r'^\*\*([A-Z]{3}-\d{2})\.\s*(.*?)\*\*', re.M)
REQ = re.compile(r'([A-Z]{3})-(\d{2})(?:…(\d{2}))?')
WORST = ('UNSTABLE', 'STABLE_RED', 'STABLE_SKIP', 'STABLE_GREEN')
DISTURBED = 0.10


def die(msg):
    sys.exit(f'error: {msg}')


def spec_ids():
    """{ID: title} of the requirements, from the specification the agents read (the base's docs/spec)."""
    text = subprocess.run(['git', '-C', ROOT, 'show', f'{BASE}:docs/spec/SPEC.md'], capture_output=True,
                          text=True, check=True).stdout
    ids = dict(SPEC_ID.findall(text))
    if len(ids) != 69:
        die(f'SPEC.md has {len(ids)} requirement IDs, not 69')
    return ids


# --- Java sources ---------------------------------------------------------------------------------------------

def mask(src):
    """src with comments and the contents of string, text-block and char literals blanked (same length, newlines
    kept), so annotations, declarations and braces can be found by pattern."""
    out, i, n = list(src), 0, len(src)

    def blank(a, b):
        for k in range(a, b):
            if out[k] != '\n':
                out[k] = ' '

    while i < n:
        if src.startswith('//', i):
            j = src.find('\n', i)
            j = n if j < 0 else j
            blank(i, j)
            i = j
        elif src.startswith('/*', i):
            j = src.find('*/', i + 2)
            j = n if j < 0 else j + 2
            blank(i, j)
            i = j
        elif src.startswith('"""', i):
            j = src.find('"""', i + 3)
            j = n if j < 0 else j
            blank(i + 3, j)
            i = j + 3
        elif src[i] in '"\'':
            j = i + 1
            while j < n and src[j] != src[i] and src[j] != '\n':
                j += 2 if src[j] == '\\' else 1
            blank(i + 1, j)
            i = j + 1
        else:
            i += 1
    return ''.join(out)


TOKEN = re.compile(r'@\s*([\w.]+)|\b(?:class|interface|enum|record)\s+(\w+)|\bvoid\s+(\w+)\s*\(|[{};]')
STRING = re.compile(r'"((?:[^"\\\n]|\\.)*)"')


def unescape(s):
    return re.sub(r'\\(u[0-9a-fA-F]{4}|.)', lambda m: chr(int(m.group(1)[1:], 16)) if len(m.group(1)) == 5
                  else {'n': '\n', 't': '\t', 'r': '\r'}.get(m.group(1), m.group(1)), s)


def close_paren(m, k):
    depth = 0
    for j in range(k, len(m)):
        depth += {'(': 1, ')': -1}.get(m[j], 0)
        if depth == 0:
            return j
    die('unbalanced parentheses')


def java_tests(path):
    """[{class, method, line, display_name, tags, class_order}] of the @Test and @ParameterizedTest methods of one
    source file. Annotations are read with their full argument lists, which may span lines (@CsvSource); class-level
    @Tag values apply to every method of the class."""
    src = open(path, encoding='utf-8').read()
    m = mask(src)
    tests, stack, pending, pending_class, depth, pos = [], [], [], None, 0, 0
    while True:
        t = TOKEN.search(m, pos)
        if not t:
            return tests
        pos = t.end()
        if t.group(1):
            args, k = None, pos
            while k < len(m) and m[k].isspace():
                k += 1
            if k < len(m) and m[k] == '(':
                end = close_paren(m, k)
                args, pos = src[k + 1:end], end + 1
            pending.append((t.group(1).rsplit('.', 1)[-1], args))
        elif t.group(2):
            pending_class, pending = (t.group(2), pending), []
        elif t.group(3):
            names = {a for a, _ in pending}
            if names & {'Test', 'ParameterizedTest'} and stack:
                cls, cls_ann, _ = stack[-1]
                tags = [unescape(s) for a, args in cls_ann + pending if a in ('Tag', 'Tags')
                        for s in STRING.findall(args or '')]
                shown = [unescape(s) for a, args in pending if a == 'DisplayName' for s in STRING.findall(args or '')]
                order = [args for a, args in cls_ann if a == 'Order']
                tests.append({'class': cls, 'method': t.group(3), 'line': src.count('\n', 0, t.start(3)) + 1,
                              'display_name': ''.join(shown[:1]), 'tags': tags,
                              'class_order': int(order[0]) if order and order[0].strip().isdigit() else ''})
            pending = []
        elif t.group(0) == '{':
            depth += 1
            if pending_class:
                stack.append((*pending_class, depth))
                pending_class = None
            pending = []
        elif t.group(0) == '}':
            if stack and stack[-1][2] == depth:
                stack.pop()
            depth -= 1
            pending = []
        else:
            pending = []


def suite_tests(suite_dir):
    tests = []
    for path in sorted(glob.glob(os.path.join(suite_dir, 'src', 'test', 'java', '**', '*.java'), recursive=True)):
        for t in java_tests(path):
            t['file'] = 'api-tests/' + os.path.relpath(path, suite_dir)
            tests.append(t)
    return tests


def read_tagmap(out):
    path = os.path.join(out, 'tagmap.csv')
    if not os.path.exists(path):
        die(f'{path} is missing: run the tags check (CHK-3S) first')
    rows = list(csv.DictReader(open(path)))
    for r in rows:
        r['tags'] = r['tags'].split()
        r['test'] = f'{r["class"]}#{r["method"]}'
    return rows


def test_of(tagmap):
    """A function (Surefire class, Surefire name) -> tagmap row: the test of the class whose display name is the
    longest that the execution's name equals or continues with a space (the invocation name)."""
    by_class = collections.defaultdict(list)
    for r in tagmap:
        by_class[r['class']].append(r)

    def find(cls, name):
        best = None
        for r in by_class.get(cls.rsplit('.', 1)[-1], []):
            dn = r['display_name']
            if dn and (name == dn or name.startswith(dn + ' ')) and (not best or len(dn) > len(best['display_name'])):
                best = r
        return best
    return find


# --- findings -------------------------------------------------------------------------------------------------

def finding(check, kind, severity, target, problem, evidence, expected, fix_scope, requirement=None, quote=None):
    """One finding in the common schema of CHECKS.md §6.1; the id is a hash of check, kind and target."""
    key = json.dumps([check, kind, target], sort_keys=True)
    return {'id': 'F-' + hashlib.sha256(key.encode()).hexdigest()[:10], 'check': check, 'kind': kind,
            'severity': severity, 'target': target, 'requirement': requirement, 'spec_quote': quote,
            'problem': problem, 'evidence': evidence, 'expected_after_fix': expected, 'fix_scope': fix_scope}


def test_target(r):
    return {'type': 'test', 'test': r['test'], 'display_name': r['display_name'], 'tags': r['tags'],
            'file': r['file'], 'line': int(r['line'])}


def write_jsonl(path, rows):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w') as f:
        for row in rows:
            f.write(json.dumps(row, ensure_ascii=False) + '\n')


# --- CHK-3S ---------------------------------------------------------------------------------------------------

def plan_reqs(plan_text):
    """{CL-ID: [requirement IDs]} from the Req column of the TEST-PLAN.md checklist (§4)."""
    section = re.search(r'^## 4\..*?(?=^## 5\.)', plan_text, re.S | re.M)
    out = {}
    for cl, req in re.findall(r'^\|\s*(CL-\d{3})\s*\|([^|]*)\|', section.group(0) if section else '', re.M):
        ids = []
        for prefix, lo, hi in REQ.findall(req):
            ids += [f'{prefix}-{n:02d}' for n in range(int(lo), int(hi or lo) + 1)]
        out[cl] = ids
    return out


def tags(args):
    out = os.path.realpath(args.out)
    ids = spec_ids()
    with tempfile.TemporaryDirectory() as tmp:
        if args.suite:
            suite = matrix.export_suite(os.path.realpath(args.suite[0]), args.suite[1], tmp)
        else:
            suite = os.path.realpath(args.sample)
        tests = suite_tests(suite)
        plan_path = os.path.join(suite, 'TEST-PLAN.md')
        plan = plan_reqs(open(plan_path).read()) if os.path.exists(plan_path) else None
    if args.expect is not None and len(tests) != args.expect:
        die(f'the parser found {len(tests)} tests, not {args.expect}')

    os.makedirs(os.path.join(out, 'chk3s'), exist_ok=True)
    with open(os.path.join(out, 'tagmap.csv'), 'w', newline='') as f:
        w = csv.writer(f)
        w.writerow(['class', 'method', 'line', 'display_name', 'tags', 'class_order', 'file'])
        for t in tests:
            w.writerow([t['class'], t['method'], t['line'], t['display_name'], ' '.join(t['tags']), t['class_order'],
                        t['file']])
    rows = read_tagmap(out)

    findings, counts = [], collections.Counter()
    names = collections.Counter((r['class'], r['display_name']) for r in rows)
    for r in rows:
        bad = [x for x in r['tags'] if x not in ids]
        if bad:  # S1
            findings.append(finding('CHK-3S', 'L1', 'low', test_target(r),
                                    f'Tag {", ".join(bad)} is not a requirement ID of the specification.',
                                    f'Tags of the test: {", ".join(r["tags"])}.',
                                    'Every tag of the test is a requirement ID of the specification.', 'tag'))
        if not r['tags']:  # S2; a test whose only tags are wrong is S1's (SAMPLE-KEY.md §7: S1 1, S2 0)
            findings.append(finding('CHK-3S', 'UNTAGGED', 'low', test_target(r),
                                    'The test has no requirement tag.', 'The test has no @Tag.',
                                    'The test is tagged with the requirements it exercises.', 'tag'))
        if not re.match(r'CL-\d{3} ', r['display_name']):  # S3
            findings.append(finding('CHK-3S', 'NAME_FORMAT', 'low', test_target(r),
                                    'The display name does not start with a checklist ID (CL-nnn).',
                                    f'Display name: "{r["display_name"]}".',
                                    'The display name starts with "CL-nnn ".', 'tag'))
        if names[(r['class'], r['display_name'])] > 1:  # S4
            findings.append(finding('CHK-3S', 'DUPLICATE_NAME', 'low', test_target(r),
                                    'Another test of the class has the same display name.',
                                    f'Display name: "{r["display_name"]}".',
                                    'Every (class, display name) is unique.', 'tag'))
    tagged = {x for r in rows for x in r['tags']}
    for rid, title in ids.items():
        if rid not in tagged:  # S5
            findings.append(finding('CHK-3S', 'UNCOVERED', 'high', {'type': 'requirement', 'requirement': rid},
                                    f'No test is tagged {rid}.', 'No test of the suite has this tag.',
                                    f'At least one test is tagged {rid} and exercises it.', 'new-test',
                                    requirement=rid, quote=f'{rid}: "{title}"'))
    for f_ in findings:
        counts[f_['kind']] += 1
    write_jsonl(os.path.join(out, 'chk3s', 'findings.jsonl'), findings)

    if plan is not None:  # S6
        agree = 0
        with open(os.path.join(out, 'chk3s', 'plan-tags.csv'), 'w', newline='') as f:
            w = csv.writer(f)
            w.writerow(['cl', 'test', 'tags', 'plan_req', 'agree'])
            for r in rows:
                cl = r['display_name'][:6]
                want = plan.get(cl)
                same = want is not None and sorted(want) == sorted(r['tags'])
                agree += same
                w.writerow([cl, r['test'], ' '.join(r['tags']), ' '.join(want) if want is not None else '-',
                            'yes' if same else ('no plan row' if want is None else 'no')])
        s6 = f'{agree}/{len(rows)} agree'
    else:
        s6 = 'no TEST-PLAN.md'
    print(f'tests {len(rows)}, spec IDs {len(ids)}, tagged IDs {len(tagged & set(ids))}; '
          f'S1 L1 {counts["L1"]}, S2 UNTAGGED {counts["UNTAGGED"]}, S3 NAME_FORMAT {counts["NAME_FORMAT"]}, '
          f'S4 DUPLICATE_NAME {counts["DUPLICATE_NAME"]}, S5 UNCOVERED {counts["UNCOVERED"]}; S6 {s6}')


# --- stand runs -----------------------------------------------------------------------------------------------

def clock():
    try:
        with urllib.request.urlopen(API + '/api/test/clock', timeout=5) as r:
            return json.load(r).get('offset')
    except (OSError, ValueError):
        return 'unavailable'


def fresh_stand(log):
    t0 = time.time()
    if matrix.sh([BUGS, 'off', '--fresh'], log) != 0:
        die(f'stand recreate failed, see {log}')
    return {'recreate_s': round(time.time() - t0), 'stand_check': matrix.sh([CHECK], log) == 0}


def suite_run(rdir, repo, ref, fresh, mvn_args=(), edit=None):
    """One run of the suite in a new copy of <ref>, optionally after a fresh stand; outcomes and run facts go to
    rdir. edit(suite_dir) may change the copy before the run (CHK-6 reverse order)."""
    if os.path.exists(rdir):
        shutil.rmtree(rdir)
    os.makedirs(rdir)
    log = os.path.join(rdir, 'run.log')
    info = {'run': os.path.basename(rdir), 'fresh': fresh, 'started': time.strftime('%Y-%m-%dT%H:%M:%S%z'),
            'java_home': os.environ.get('JAVA_HOME', '')}
    if fresh:
        info.update(fresh_stand(log))
    info['clock'] = clock()
    work = os.path.join(rdir, 'work')
    suite = matrix.export_suite(repo, ref, work)
    if edit:
        edit(suite)
    t0 = time.time()
    info['mvn_exit'] = matrix.sh(['mvn', '-B', 'test', *mvn_args], log, cwd=suite, timeout=matrix.MVN_TIMEOUT)
    info['mvn_s'] = round(time.time() - t0)
    info['classes_run'] = re.findall(r'^\[INFO\] Running (.+)$', open(log).read(), re.M)
    reports = os.path.join(rdir, 'reports')
    os.makedirs(reports)
    for path in glob.glob(os.path.join(suite, '**', 'TEST-*.xml'), recursive=True):
        shutil.copy(path, reports)
    res = matrix.outcomes(reports)
    info['executions'] = len(res)
    for kind in ('pass', 'FAIL', 'ERROR', 'skip'):
        info[kind] = sum(1 for v, *_ in res.values() if v == kind)
    with open(os.path.join(rdir, 'outcomes.json'), 'w') as f:
        json.dump([[c, n, *v] for (c, n), v in sorted(res.items())], f, indent=0)
    with open(os.path.join(rdir, 'info.json'), 'w') as f:
        json.dump(info, f, indent=1)
    shutil.rmtree(work)
    print(json.dumps(info), flush=True)
    return info


def load_run(rdir):
    return {(r[0], r[1]): (r[2], r[3]) for r in json.load(open(os.path.join(rdir, 'outcomes.json')))}


def outcome_class(value):
    return {'pass': 'green', 'FAIL': 'red', 'ERROR': 'red', 'skip': 'skip'}.get(value, value)


def restore(out):
    rc = matrix.sh([BUGS, 'off', '--fresh'], os.path.join(out, 'restore.log'))
    print(f'stand back to the base: {"ok" if rc == 0 else "FAILED"}', flush=True)


# --- CHK-2 ----------------------------------------------------------------------------------------------------

def disturbed(runs, labels):
    """{label: share of executions whose outcome class differs from their majority over the runs in labels}."""
    keys = {k for lab in labels for k in runs[lab]}
    major = {k: collections.Counter(outcome_class(runs[lab][k][0]) for lab in labels if k in runs[lab])
             .most_common(1)[0][0] for k in keys}
    return {lab: sum(1 for k, v in runs[lab].items() if outcome_class(v[0]) != major.get(k)) / max(len(runs[lab]), 1)
            for lab in runs}


def stability(args):
    out = os.path.realpath(args.out)
    tagmap = read_tagmap(out)
    cdir = os.path.join(out, 'chk2')
    repo = os.path.realpath(args.repo)
    plan = [(f'run-{n:02d}', n <= 5) for n in range(1, 11)]
    if not args.analyze:
        os.makedirs(cdir, exist_ok=True)
        for label, fresh in plan:
            suite_run(os.path.join(cdir, label), repo, args.ref, fresh)
        runs = {lab: load_run(os.path.join(cdir, lab)) for lab, _ in plan}
        share = disturbed(runs, [lab for lab, _ in plan])
        bad = [(lab, fresh) for lab, fresh in plan if share[lab] > DISTURBED]
        for label, fresh in sorted(bad, key=lambda x: x[1]):  # aged ones first, while the stand is still aged
            suite_run(os.path.join(cdir, label + '-repeat'), repo, args.ref, fresh)
        restore(cdir)
    analyze_stability(out, tagmap, cdir, plan, repo, args.ref)


def analyze_stability(out, tagmap, cdir, plan, repo, ref):
    runs = {lab: load_run(os.path.join(cdir, lab)) for lab, _ in plan}
    fresh_of = dict(plan)
    share = disturbed(runs, list(runs))
    info, used = [], []
    for label, fresh in plan:
        rep = os.path.join(cdir, label + '-repeat')
        if share[label] <= DISTURBED:
            used.append(label)
            continue
        if not os.path.exists(os.path.join(rep, 'outcomes.json')):
            used.append(label)
            info.append({'kind': 'DISTURBED_RUN', 'run': label, 'share': round(share[label], 3), 'repeat': None})
            continue
        runs[label + '-repeat'] = load_run(rep)
        fresh_of[label + '-repeat'] = fresh
        rshare = disturbed(runs, [lab for lab, _ in plan])[label + '-repeat']
        used += [label, label + '-repeat'] if rshare > DISTURBED else [label + '-repeat']
        info.append({'kind': 'DISTURBED_RUN', 'run': label, 'share': round(share[label], 3),
                     'repeat_share': round(rshare, 3), 'kept': 'both' if rshare > DISTURBED else 'repeat'})

    find = test_of(tagmap)
    report = test_report(repo, ref)
    keys = sorted({k for lab in used for k in runs[lab]})
    rows, per_test, findings = [], collections.defaultdict(list), []
    for k in keys:
        r = find(*k)
        if r is None:
            die(f'execution {k} does not map to a test of tagmap.csv')
        vals = [(lab, runs[lab][k]) for lab in used if k in runs[lab]]
        c = collections.Counter(v[0] for _, v in vals)
        classes = {outcome_class(v[0]) for _, v in vals}
        verdict = 'UNSTABLE' if len(classes) > 1 else 'STABLE_' + {'green': 'GREEN', 'red': 'RED', 'skip': 'SKIP'}[
            classes.pop()]
        fresh_cls = {outcome_class(v[0]) for lab, v in vals if fresh_of[lab]}
        if verdict != 'UNSTABLE':
            when = ''
        else:
            when = 'aged only' if len(fresh_cls) == 1 else 'fresh'
        messages = sorted({v[1] for _, v in vals if v[1]})
        invocation = k[1][len(r['display_name']):].strip()
        rows.append([r['class'], r['display_name'], invocation, len(vals), c['pass'], c['FAIL'], c['ERROR'], c['skip'],
                     verdict, when, ' || '.join(messages)])
        per_test[r['test']].append((verdict, invocation, c, when, messages))
        if verdict == 'STABLE_RED' and (len(messages) > 1 or (c['FAIL'] and c['ERROR'])):
            info.append({'kind': 'RED_VARIES', 'test': r['test'], 'invocation': invocation, 'messages': messages,
                         'fail': c['FAIL'], 'error': c['ERROR']})
    with open(os.path.join(cdir, 'stability.csv'), 'w', newline='') as f:
        w = csv.writer(f)
        w.writerow(['class', 'display_name', 'invocation', 'runs', 'pass', 'fail', 'error', 'skip', 'verdict',
                    'fresh_vs_aged', 'messages'])
        w.writerows(rows)

    by_test = {r['test']: r for r in tagmap}
    totals = collections.Counter()
    with open(os.path.join(cdir, 'tests.csv'), 'w', newline='') as f:
        w = csv.writer(f)
        w.writerow(['test', 'display_name', 'verdict', 'executions', 'report'])
        for r in tagmap:
            ex = per_test.get(r['test'], [])
            verdict = min((e[0] for e in ex), key=WORST.index) if ex else 'NOT_RUN'
            totals[verdict] += 1
            w.writerow([r['test'], r['display_name'], verdict, len(ex), report.get(r['test'], '')])
            expected = {'PASS': 'STABLE_GREEN', 'FAIL': 'STABLE_RED'}.get(report.get(r['test']))
            if expected and verdict != expected:
                info.append({'kind': 'REPORT_MISMATCH', 'test': r['test'], 'report': report[r['test']],
                             'verdict': verdict})
            if verdict == 'UNSTABLE':
                flips = [e for e in ex if e[0] == 'UNSTABLE']
                aged = all(e[3] == 'aged only' for e in flips)
                detail = '; '.join(f'{e[1] or "single"}: pass {e[2]["pass"]}, FAIL {e[2]["FAIL"]}, '
                                   f'ERROR {e[2]["ERROR"]}, skip {e[2]["skip"]}' +
                                   (f'; red lines: {" || ".join(e[4])}' if e[4] else '') for e in flips)
                findings.append(finding(
                    'CHK-2', 'UNSTABLE', 'high', test_target(by_test[r['test']]),
                    'The outcome differs between runs of the same system' +
                    (', only on runs of an aged system.' if aged else '.'),
                    f'{len(used)} runs ({sum(1 for lab in used if fresh_of[lab])} fresh): {detail}.',
                    f'The test has one outcome in every run of CHK-2, fresh and aged.', 'wait | isolation'))
    write_jsonl(os.path.join(cdir, 'findings.jsonl'), findings)
    write_jsonl(os.path.join(cdir, 'info.jsonl'), info)
    with open(os.path.join(cdir, 'summary.json'), 'w') as f:
        json.dump({'runs_used': used, 'fresh': sum(1 for lab in used if fresh_of[lab]), 'tests': dict(totals)}, f,
                  indent=1)
    kinds = collections.Counter(i['kind'] for i in info)
    print(f'runs used {len(used)} ({", ".join(used)}); executions {len(keys)}; tests ' +
          ', '.join(f'{v} {totals[v]}' for v in WORST + ('NOT_RUN',) if totals[v]) +
          f'; findings UNSTABLE {len(findings)}; info ' + (', '.join(f'{k} {v}' for k, v in kinds.items()) or 'none'))


def test_report(repo, ref):
    """{Class#method: PASS | FAIL} from the coverage matrix of TEST-REPORT.md §1 (info only)."""
    text = subprocess.run(['git', '-C', repo, 'show', f'{ref}:api-tests/TEST-REPORT.md'], capture_output=True,
                          text=True).stdout
    section = re.search(r'^## 1\..*?(?=^## 2\.)', text, re.S | re.M)
    out = {}
    for test, result in re.findall(r'`(\w+#\w+)`\s*\|[^|]*\|\s*\**(PASS|FAIL)\**', section.group(0) if section else ''):
        out[test] = 'FAIL' if out.get(test) == 'FAIL' else result
    return out


# --- CHK-6 ----------------------------------------------------------------------------------------------------

REVERSE_ORDER = '''package com.arcade.qa;

import java.util.Comparator;
import org.junit.jupiter.api.ClassDescriptor;
import org.junit.jupiter.api.ClassOrderer;
import org.junit.jupiter.api.ClassOrdererContext;
import org.junit.jupiter.api.Order;

/** CHK-6 order run: the classes in reverse {@code @Order}, InvariantTest still last. */
public class ReverseClassOrder implements ClassOrderer {
    @Override
    public void orderClasses(ClassOrdererContext context) {
        context.getClassDescriptors().sort(Comparator.comparingInt((ClassDescriptor d) ->
                d.getTestClass().getSimpleName().equals("InvariantTest") ? Integer.MAX_VALUE
                        : -d.findAnnotation(Order.class).map(Order::value).orElse(Order.DEFAULT)));
    }
}
'''


def reverse_classes(suite):
    """Switch the copy's suite entry point to the reverse class order."""
    path = os.path.join(suite, 'src', 'test', 'java', 'com', 'arcade', 'qa', 'RegressionSuite.java')
    text = open(path).read()
    new = text.replace('org.junit.jupiter.api.ClassOrderer$OrderAnnotation', 'com.arcade.qa.ReverseClassOrder')
    if new == text:
        die('RegressionSuite.java does not set the OrderAnnotation class orderer')
    open(path, 'w').write(new)
    open(os.path.join(os.path.dirname(path), 'ReverseClassOrder.java'), 'w').write(REVERSE_ORDER)


def class_sequence(tagmap):
    order = {r['class']: int(r['class_order']) if r['class_order'] else 1 << 30 for r in tagmap}
    return sorted(order, key=lambda c: (order[c], c))


def alone_runs(adir, repo, ref, classes):
    """Every class alone, in the suite order, on one fresh stand: {(class, name): (outcome, line)}."""
    res = {}
    for n, cls in enumerate(classes):
        rdir = os.path.join(adir, f'{n + 1:02d}-{cls}')
        suite_run(rdir, repo, ref, fresh=(n == 0),
                  mvn_args=(f'-Dtest={cls}', '-Dsurefire.failIfNoSpecifiedTests=false'))
        res.update(load_run(rdir))
    return res


def order(args):
    out = os.path.realpath(args.out)
    tagmap = read_tagmap(out)
    repo = os.path.realpath(args.repo)
    cdir = os.path.join(out, 'chk6')
    chk2 = os.path.join(out, 'chk2', 'stability.csv')
    if not os.path.exists(chk2):
        die(f'{chk2} is missing: run stability (CHK-2) first')
    base = {}
    for row in csv.DictReader(open(chk2)):
        name = f'{row["display_name"]} {row["invocation"]}'.strip()
        base[(row['class'], name)] = row['verdict']
    find = test_of(tagmap)
    classes = class_sequence(tagmap)

    def differing(res):
        """Tests whose outcome class differs from their stable CHK-2 class in this variant."""
        diff = set()
        for (cls, name), (value, _) in res.items():
            r = find(cls, name)
            if r is None:
                die(f'execution {(cls, name)} does not map to a test of tagmap.csv')
            want = base.get((r['class'], name))
            if want in ('STABLE_GREEN', 'STABLE_RED', 'STABLE_SKIP') and \
                    outcome_class(value) != want.split('_')[1].lower():
                diff.add(r['test'])
        return diff

    variants = {'alone': lambda d: alone_runs(d, repo, args.ref, classes),
                'reversed': lambda d: (suite_run(d, repo, args.ref, fresh=True, edit=reverse_classes), load_run(d))[1]}
    if not args.analyze:
        os.makedirs(cdir, exist_ok=True)
        for name, run in variants.items():
            if differing(run(os.path.join(cdir, name))):
                run(os.path.join(cdir, name + '-repeat'))
        restore(cdir)
    analyze_order(cdir, tagmap, base, find, classes, differing)


def analyze_order(cdir, tagmap, base, find, classes, differing):
    def load(name):
        d = os.path.join(cdir, name)
        if not os.path.isdir(d):
            return None
        if name.startswith('alone'):
            res = {}
            for n, cls in enumerate(classes):
                res.update(load_run(os.path.join(d, f'{n + 1:02d}-{cls}')))
            return res
        return load_run(d)

    attempts = {v: [r for r in (load(v), load(v + '-repeat')) if r is not None] for v in ('alone', 'reversed')}
    diffs = {v: [differing(r) for r in rs] for v, rs in attempts.items()}

    def shown(test, res):
        vals = sorted({outcome_class(v[0]) for k, v in res.items() if (find(*k) or {}).get('test') == test})
        return '+'.join(vals) or '-'

    per_test = collections.defaultdict(set)
    for (cls, name), verdict in base.items():
        r = find(cls, name)
        per_test[r['test']].add(verdict)
    findings, n = [], collections.Counter()
    by_test = {r['test']: r for r in tagmap}
    with open(os.path.join(cdir, 'order.csv'), 'w', newline='') as f:
        w = csv.writer(f)
        w.writerow(['test', 'chk2_verdict', 'alone', 'reversed', 'verdict'])
        for r in tagmap:
            t = r['test']
            v2 = min(per_test.get(t, {'NOT_RUN'}), key=lambda x: WORST.index(x) if x in WORST else len(WORST))
            hit = [v for v in ('alone', 'reversed')
                   if len(diffs[v]) == 2 and all(t in d for d in diffs[v])]
            if v2 == 'UNSTABLE' or v2 == 'NOT_RUN':
                verdict = 'EXCLUDED'
            else:
                verdict = 'ORDER_DEPENDENT' if hit else 'OK'
            n[verdict] += 1
            w.writerow([t, v2, '/'.join(shown(t, res) for res in attempts['alone']),
                        '/'.join(shown(t, res) for res in attempts['reversed']), verdict])
            if verdict == 'ORDER_DEPENDENT':
                findings.append(finding(
                    'CHK-6', 'ORDER_DEPENDENT', 'medium', test_target(by_test[t]),
                    f'The outcome depends on which classes ran before: {" and ".join(hit)} differs from the suite '
                    f'order in both attempts.',
                    f'CHK-2: {v2}; alone: {"/".join(shown(t, x) for x in attempts["alone"])}; reversed class order: '
                    f'{"/".join(shown(t, x) for x in attempts["reversed"])}.',
                    'The test has the same outcome alone, in reverse class order and in the suite order.',
                    'isolation'))
    write_jsonl(os.path.join(cdir, 'findings.jsonl'), findings)
    print(f'attempts: alone {len(attempts["alone"])}, reversed {len(attempts["reversed"])}; differing in the first '
          f'attempt: alone {len(diffs["alone"][0]) if diffs["alone"] else "-"}, reversed '
          f'{len(diffs["reversed"][0]) if diffs["reversed"] else "-"}; tests ' +
          ', '.join(f'{k} {v}' for k, v in sorted(n.items())))


# --- CHK-4 ----------------------------------------------------------------------------------------------------

def mutants(path):
    """[{id, requirement, part_broken, how_to_observe}] from table 1 of MUTANTS.md. "What changed" is left out and
    code references are removed: the fixer never gets them (D8)."""
    text = open(path).read()
    section = re.search(r'^## 1\..*?(?=^## 2\.)', text, re.S | re.M) or die(f'no table 1 in {path}')
    out = []
    for line in section.group(0).splitlines():
        if re.match(r'\|\s*M\d+\s*\|', line):
            cells = [c.strip() for c in line.strip().strip('|').split(' | ')]
            if len(cells) != 7:
                die(f'{path}: a row has {len(cells)} cells, not 7: {line[:80]}')
            out.append({'id': cells[0], 'requirement': cells[1], 'part_broken': no_code(cells[2]),
                        'how_to_observe': no_code(cells[5])})
    return out


def no_code(text):
    """The text without references to the service code (`path/File.java:12-34`): the fixer gets spec-level
    behavior only (D8 clarification)."""
    return re.sub(r':?\s*`[^`]*\.(?:java|yml|yaml|sql|properties)(?::[\d-]+)?`', '', text)


def faults(args):
    out = os.path.realpath(args.out)
    tagmap = read_tagmap(out)
    by_test = {r['test']: r for r in tagmap}
    find = test_of(tagmap)
    ids = spec_ids()
    chk2 = os.path.join(out, 'chk2', 'stability.csv')
    if not os.path.exists(chk2):
        die(f'{chk2} is missing: run stability (CHK-2) first')

    # executions on the base, from CHK-2: usable green, usable red (one normalized line in every run, D4), or not
    base = {}
    for row in csv.DictReader(open(chk2)):
        lines = [m for m in row['messages'].split(' || ') if m]
        steady = row['verdict'] == 'STABLE_RED' and len(lines) == 1 and not (int(row['fail']) and int(row['error']))
        base[(row['class'], f'{row["display_name"]} {row["invocation"]}'.strip())] = (
            row['verdict'], lines[0] if steady else None)

    values = list(csv.reader(open(os.path.join(args.matrix, 'matrix.csv'))))
    lines = list(csv.reader(open(os.path.join(args.matrix, 'failures.csv'))))
    columns = values[0][2:]
    if lines[0][2:] != columns or 'base' not in columns:
        die('matrix.csv and failures.csv do not have the same columns with base')
    info = []
    execs = []  # (test, chk2 verdict, base line, {column: (value, line)})
    for vrow, lrow in zip(values[1:], lines[1:]):
        r = find(vrow[0], vrow[1])
        if r is None:
            die(f'JOIN_ERROR: matrix row {vrow[:2]} does not map to a test of tagmap.csv')
        verdict, line = base.get((r['class'], vrow[1]), ('NOT_IN_CHK2', None))
        cells = dict(zip(columns, zip(vrow[2:], lrow[2:])))
        if verdict == 'STABLE_GREEN' and cells['base'][0] != 'pass':
            info.append({'kind': 'BASE_COLUMN_RED', 'test': r['test'], 'execution': vrow[1],
                         'matrix_base': cells['base'][0]})
            verdict = 'EXCLUDED'
        execs.append((r['test'], verdict, line, cells))

    def detects(verdict, line, cell):
        value, mline = cell
        if value not in ('FAIL', 'ERROR'):
            return False
        return verdict == 'STABLE_GREEN' or (verdict == 'STABLE_RED' and line is not None and mline != line)

    usable = collections.defaultdict(lambda: True)  # a test is usable when every execution is green or steady red
    green = collections.defaultdict(lambda: True)
    for test, verdict, line, _ in execs:
        usable[test] &= verdict == 'STABLE_GREEN' or (verdict == 'STABLE_RED' and line is not None)
        green[test] &= verdict == 'STABLE_GREEN'
    excluded = sorted(t for t in {e[0] for e in execs} if not usable[t])
    kinds = collections.defaultdict(set)
    for test, verdict, _, _ in execs:
        kinds[test].add(verdict)
    for test in sorted(t for t, v in kinds.items() if {'STABLE_GREEN', 'STABLE_RED'} <= v):
        info.append({'kind': 'MIXED_ON_BASE', 'test': test})

    faults_rows, pairs, findings, status_n = [], [], [], collections.Counter()
    for m in mutants(args.mutants):
        col, req = m['id'], m['requirement']
        if col not in columns:
            die(f'{col} is not a column of the matrix')
        vals = [e[3][col][0] for e in execs]
        invalid = 'pass' not in vals or vals.count('ERROR') >= 0.9 * len(vals)
        own = sorted(r['test'] for r in tagmap if req in r['tags'])
        hit = collections.defaultdict(list)  # test -> values of its detecting executions
        for test, verdict, line, cells in execs:
            if usable[test] and detects(verdict, line, cells[col]):
                hit[test].append(cells[col][0])
        d_own = sorted(t for t in hit if t in own)
        d_other = sorted(t for t in hit if t not in own)
        error_only = sorted(t for t, v in hit.items() if set(v) == {'ERROR'})
        if invalid:
            status = 'NOT_EVALUATED'
        elif d_own:
            status = 'CAUGHT'
        elif d_other:
            status = 'OWN_TESTS_MISS'
        elif any(usable[t] for t in own):
            status = 'UNCAUGHT'
        else:
            status = 'NOT_EVALUABLE'
        status_n[status] += 1
        faults_rows.append([col, req, m['part_broken'], status, ' '.join(own), ' '.join(d_own), ' '.join(d_other),
                            ' '.join(error_only)])
        primary = []
        for t in own:
            if not green[t] or t in hit or invalid:
                continue
            fv = sorted({e[3][col][0] for e in execs if e[0] == t})
            pair = 'primary' if status in ('UNCAUGHT', 'OWN_TESTS_MISS') else 'secondary'
            pairs.append([t, col, 'pass', '+'.join(fv), pair, 'yes' if 'skip' in fv else ''])
            if pair == 'primary':
                primary.append(t)
        if status in ('UNCAUGHT', 'OWN_TESTS_MISS'):
            findings.append(finding(
                'CHK-4', status, 'high' if status == 'UNCAUGHT' else 'medium',
                {'type': 'requirement', 'requirement': req, 'fault': col},
                f'No test tagged {req} fails when this part is broken: {m["part_broken"]}. '
                f'How to observe: {m["how_to_observe"]}',
                (f'Own tests that stay green on {col}: {", ".join(primary)}.' if primary else
                 f'No own test of {req} is green on the base.') +
                (f' Caught only by tests of other requirements: {", ".join(d_other)}.' if d_other else ''),
                f'At least one test tagged {req} fails on {col} and passes on base.', 'assertion | new-test',
                requirement=req, quote=f'{req}: "{ids.get(req, "")}"'))
            findings[-1]['candidate_tests'] = primary
        if d_other:
            info.append({'kind': 'COLLATERAL', 'fault': col, 'requirement': req, 'tests': d_other})
        if error_only:
            info.append({'kind': 'ERROR_ONLY', 'fault': col, 'tests': error_only})
    mutated = {m['requirement'] for m in mutants(args.mutants)}
    for rid in ids:
        if rid not in mutated:
            info.append({'kind': 'NOT_MUTATED', 'requirement': rid})
    if excluded:
        info.append({'kind': 'EXCLUDED_TESTS', 'tests': excluded,
                     'why': 'unstable in CHK-2, red with a varying line, or red in the matrix base column'})

    cdir = os.path.join(out, 'chk4')
    os.makedirs(cdir, exist_ok=True)
    with open(os.path.join(cdir, 'faults.csv'), 'w', newline='') as f:
        w = csv.writer(f)
        w.writerow(['id', 'requirement', 'part_broken', 'status', 'own_tests', 'D_own', 'D_other', 'error_only'])
        w.writerows(faults_rows)
    with open(os.path.join(cdir, 'green_own.csv'), 'w', newline='') as f:
        w = csv.writer(f)
        w.writerow(['test', 'fault', 'base', 'fault_value', 'pair', 'skipped'])
        w.writerows(pairs)
    write_jsonl(os.path.join(cdir, 'findings.jsonl'), findings)
    write_jsonl(os.path.join(cdir, 'info.jsonl'), info)
    n_pairs = collections.Counter(p[4] for p in pairs)
    print(f'faults {len(faults_rows)}: ' + ', '.join(f'{k} {v}' for k, v in sorted(status_n.items())) +
          f'; pairs primary {n_pairs["primary"]}, secondary {n_pairs["secondary"]}; excluded tests {len(excluded)}; '
          f'not mutated {len(ids) - len(mutated & set(ids))}; findings {len(findings)}')


# --- audit and assembly ---------------------------------------------------------------------------------------

KIND = {  # severity, fix scope, what is wrong (CHECKS.md §3 and §6.2)
    'E1': ('high', 'assertion', 'Subject not asserted: the action under test is performed, but no assertion '
                                'examines its outcome.'),
    'E2': ('high', 'assertion', 'No independent expected value: the assertion compares system output with output '
                                'the violation would change in the same way.'),
    'E3': ('medium', 'assertion', 'Over-permissive assertion: the point is asserted, but the assertion admits a '
                                  'value the claim excludes.'),
    'E4': ('medium', 'assertion', 'Claim point not observed: a point the claim names has no assertion, or only a '
                                  'negative check that is too short.'),
    'E5': ('high', 'assertion', 'Neutralized assertion: an assertion that cannot fail the test.'),
    'L1': ('low', 'tag', 'A tag is not a requirement ID of the specification.'),
    'L2': ('low', 'tag', 'A tag the test does not exercise, or the requirement governing the claim is not tagged.'),
    'L3': ('high', 'expectation', 'Contradiction: the specification prescribes a different outcome; a service '
                                  'that follows it fails the test.'),
    'L4': ('medium', 'expectation', 'Unspecified expectation: the assertion requires something the specification '
                                    'leaves open.'),
    'H1': ('medium', 'wait', 'Premature observation: an asynchronous or deadline-driven outcome is asserted before '
                             'the specification guarantees it is observable.'),
    'H2': ('high', 'isolation', 'Environment not restored: the test changes global state other tests rely on and '
                                'does not restore it on every path.'),
    'H3': ('medium', 'isolation', 'State the test does not create: the outcome depends on data or state the test '
                                  'did not create in this run.'),
    'H4': ('medium', 'wait', 'Unenforced ordering or timing: the outcome depends on a race or on a real-time '
                             'duration the test assumes but does not enforce.'),
}
AGENT_CHECKS = ('CHK-1', 'CHK-3A', 'CHK-5')
HUMAN = re.compile(r'^### (A-\d+) .*?^- \*\*Human:\*\*[ \t]*([^\n]*)', re.S | re.M)


def agent_verdicts(workdir):
    """{(check, test): verdict with its batch and folder} of every session of an agent-check workdir."""
    import review
    work = os.path.realpath(workdir)
    manifest = json.load(open(os.path.join(work, 'manifest.json')))
    out = {}
    for batch, info in sorted(manifest['batches'].items()):
        got = review.verdicts(info['folder'], batch)
        for check in AGENT_CHECKS:
            for test in info['tests']:
                vs = got[check].get(test, [])
                if len(vs) != 1:
                    die(f'{batch} {check} {test}: {len(vs)} verdicts, one expected')
                out[(check, test)] = dict(vs[0], batch=batch)
    return out, manifest


def script_results(out):
    """What the script checks say per test: CHK-2 verdict, CHK-6 verdict, CHK-3S L1, CHK-4 pairs and catches."""
    res = collections.defaultdict(lambda: {'chk2': None, 'chk6': None, 'l1': False, 'pairs': [], 'catches': []})
    for row in csv.DictReader(open(os.path.join(out, 'chk2', 'tests.csv'))):
        res[row['test']]['chk2'] = row['verdict']
    path = os.path.join(out, 'chk6', 'order.csv')
    for row in csv.DictReader(open(path)) if os.path.exists(path) else []:
        res[row['test']]['chk6'] = row['verdict']
    for line in open(os.path.join(out, 'chk3s', 'findings.jsonl')):
        f = json.loads(line)
        if f['kind'] == 'L1':
            res[f['target']['test']]['l1'] = True
    for row in csv.DictReader(open(os.path.join(out, 'chk4', 'green_own.csv'))):
        res[row['test']]['pairs'].append(f'{row["fault"]} ({row["pair"]})')
    for row in csv.DictReader(open(os.path.join(out, 'chk4', 'faults.csv'))):
        for t in row['D_own'].split():
            res[t]['catches'].append(row['id'])
    return res


def audit(args):
    """Selects the verdicts the human audits (CHECKS.md §5) into <out>/audit/AUDIT.md."""
    import random
    out = os.path.realpath(args.out)
    verdicts, _ = agent_verdicts(args.workdir)
    by_test = {r['test']: r for r in read_tagmap(out)}
    sr = script_results(out)
    why = collections.defaultdict(list)
    for (check, test), v in verdicts.items():
        s = sr[test]
        primary = [p for p in s['pairs'] if p.endswith('(primary)')]
        if check == 'CHK-1' and v['verdict'] == 'PASS' and primary:
            why[(check, test)].append(f'contradiction: PASS, but the test stays green on {", ".join(primary)}')
        if check == 'CHK-1' and v['verdict'] == 'FLAG' and s['catches']:
            why[(check, test)].append(f'contradiction: FLAG, but the test catches {", ".join(s["catches"])}')
        if check == 'CHK-5' and v['verdict'] == 'PASS' and (s['chk2'] == 'UNSTABLE' or s['chk6'] == 'ORDER_DEPENDENT'):
            why[(check, test)].append(f'contradiction: PASS, but CHK-2 {s["chk2"]}, CHK-6 {s["chk6"]}')
        if check == 'CHK-3A' and (v['verdict'] == 'FLAG' and v.get('kind') == 'L1') != s['l1']:
            why[(check, test)].append(f'contradiction: CHK-3A {v["verdict"]} {v.get("kind") or ""}, CHK-3S L1 '
                                      f'{"reported" if s["l1"] else "not reported"}')
        if check == 'CHK-3A' and v['verdict'] == 'FLAG' and v.get('kind') == 'L3':
            why[(check, test)].append('every L3 is audited')
        if v['verdict'] == 'UNCLEAR':
            why[(check, test)].append('every UNCLEAR goes to the human')
    rnd = random.Random(args.seed)
    for check in AGENT_CHECKS:
        for verdict, share in (('FLAG', 0.10), ('PASS', 0.05)):
            rest = sorted(t for (c, t), v in verdicts.items() if c == check and v['verdict'] == verdict
                          and (c, t) not in why)
            n = min(len(rest), max(5, -(-len(rest) * share // 1)))
            for test in rnd.sample(rest, int(n)):
                why[(check, test)].append(f'random sample of the other {verdict} verdicts')

    items = sorted(why, key=lambda k: (AGENT_CHECKS.index(k[0]), k[1]))
    adir = os.path.join(out, 'audit')
    os.makedirs(adir, exist_ok=True)
    path = os.path.join(adir, 'AUDIT.md')
    if os.path.exists(path) and not args.force:
        die(f'{path} exists (it may hold marks): pass --force to select again')
    lines = [f'# Audit of agent verdicts (CHECKS.md §5)', '',
             f'{len(items)} verdicts selected, random seed {args.seed}. For each one, after "Human:" write `agree` '
             'or `wrong: <why>`; for an UNCLEAR, write the reading you choose. The specification is the oracle.', '']
    for n, (check, test) in enumerate(items, 1):
        v, r, s = verdicts[(check, test)], by_test[test], sr[test]
        lines += [f'### A-{n:03d} · {check} · {v["verdict"]} {v.get("kind") or ""} · {test}', '',
                  f'- Why selected: {"; ".join(why[(check, test)])}',
                  f'- Claim: {r["display_name"]}',
                  f'- Tags: {" ".join(r["tags"])} · `{r["file"]}:{v.get("line") or r["line"]}` · batch {v["batch"]}',
                  f'- Evidence: {v.get("evidence", "")}',
                  f'- Spec: {v.get("spec", "")}',
                  f'- Fix hint: {v.get("fix_hint") or "—"}',
                  f'- Scripts: CHK-2 {s["chk2"]}; CHK-6 {s["chk6"]}; catches {", ".join(s["catches"]) or "—"}; '
                  f'green on own fault {", ".join(s["pairs"]) or "—"}',
                  '- **Human:** ', '']
    open(path, 'w').write('\n'.join(lines))
    json.dump([[f'A-{n:03d}', c, t] for n, (c, t) in enumerate(items, 1)], open(os.path.join(adir, 'items.json'), 'w'))
    counts = collections.Counter(c for c, _ in items)
    print(f'selected {len(items)}: ' + ', '.join(f'{c} {counts[c]}' for c in AGENT_CHECKS) + f' -> {path}')


def assemble(args):
    """Builds <out>/findings.json for the fixing agent from the audited verdicts and the script findings (§6)."""
    out = os.path.realpath(args.out)
    verdicts, manifest = agent_verdicts(args.workdir)
    tagmap = read_tagmap(out)
    by_test = {r['test']: r for r in tagmap}
    sr = script_results(out)
    adir = os.path.join(out, 'audit')
    items = {a: (c, t) for a, c, t in json.load(open(os.path.join(adir, 'items.json')))}
    marks = dict(HUMAN.findall(open(os.path.join(adir, 'AUDIT.md')).read()))
    missing = [a for a in items if not marks.get(a, '').strip()]
    if missing:
        die(f'{len(missing)} audit items have no mark: {", ".join(missing[:10])}')
    audited, wrong = {}, collections.Counter()
    for a, key in items.items():
        if verdicts[key]['verdict'] == 'UNCLEAR':
            continue
        mark = marks[a].strip().lower()
        if not (mark.startswith('agree') or mark.startswith('wrong')):
            die(f'{a}: mark "{marks[a]}" is neither agree nor wrong')
        audited[key] = 'agree' if mark.startswith('agree') else 'wrong'
        wrong[key[0]] += audited[key] == 'wrong'
    n_audited = collections.Counter(c for c, _ in audited)
    withheld = {c for c in AGENT_CHECKS if n_audited[c] and wrong[c] / n_audited[c] > 0.10}

    def attach(f, test):
        s, v5 = sr[test], verdicts.get(('CHK-5', test), {})
        f.update(base_outcome=s['chk2'], chk4={'green_on_own_fault': s['pairs']} if s['pairs'] else None,
                 chk5=v5.get('kind') if v5.get('verdict') == 'FLAG' else None)
        return f

    findings = []
    for (check, test), v in sorted(verdicts.items()):
        if v['verdict'] != 'FLAG' or audited.get((check, test)) == 'wrong':
            continue
        if check in withheld and (check, test) not in audited:
            continue
        kind = v.get('kind')
        severity, scope, problem = KIND.get(kind, ('medium', 'assertion', f'{kind}.'))
        target = dict(test_target(by_test[test]), line=v.get('line') or int(by_test[test]['line']))
        req = re.match(r'[A-Z]{3}-\d{2}', v.get('spec') or '')
        f = finding(check, kind, severity, target, problem, v.get('evidence', ''), v.get('fix_hint', ''), scope,
                    requirement=req.group(0) if req else None, quote=v.get('spec'))
        f['audit'] = audited.get((check, test), 'not sampled')
        findings.append(attach(f, test))
    for name in ('chk2', 'chk3s', 'chk4', 'chk6'):
        path = os.path.join(out, name, 'findings.jsonl')
        for line in open(path) if os.path.exists(path) else []:
            f = json.loads(line)
            f['audit'] = 'n/a'
            findings.append(attach(f, f['target']['test']) if f['target']['type'] == 'test' else f)
    findings.sort(key=lambda f: (f['target']['type'] != 'test', f['target'].get('file', ''),
                                 f['target'].get('line', 0), f['target'].get('requirement', ''), f['check']))
    def confirms_expectations(t):
        """CHK-3A PASS, or a FLAG on the tags only (L1, L2): neither questions an expectation (D18 clarification)."""
        v = verdicts.get(('CHK-3A', t), {})
        ok = v.get('verdict') == 'PASS' or (v.get('verdict') == 'FLAG' and v.get('kind') in ('L1', 'L2'))
        return ok and audited.get(('CHK-3A', t)) != 'wrong'
    protected = [{'test': t, 'display_name': by_test[t]['display_name'], 'base': 'STABLE_RED',
                  'reason': 'Fails on the current system, and CHK-3A confirms every expectation against '
                            f'{" ".join(by_test[t]["tags"])}: the failure is a system defect.',
                  'rule': 'Do not change its expected outcomes. Other findings on this test may still be fixed.'}
                 for t in sorted(by_test) if sr[t]['chk2'] == 'STABLE_RED' and confirms_expectations(t)]

    runs = len(json.load(open(os.path.join(out, 'chk2', 'summary.json')))['runs_used'])
    faults_rows = list(csv.DictReader(open(os.path.join(out, 'chk4', 'faults.csv'))))
    spec_commit = subprocess.run(['git', '-C', ROOT, 'rev-parse', '--short', BASE + '^{commit}'],
                                 capture_output=True, text=True).stdout.strip()

    def agent_state(c):
        state = f'calibrated 2/2; audit {wrong[c]}/{n_audited[c]} wrong'
        return state + ('; unaudited findings withheld, re-calibrate' if c in withheld else '')
    doc = {'meta': {'suite': f'api-tests @ {manifest["source"]["commit"]}', 'spec': f'docs/spec/SPEC.md @ {spec_commit}',
                    'generated': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()),
                    'checks': {'CHK-1': agent_state('CHK-1'), 'CHK-2': f'{runs} runs', 'CHK-3S': 'ok',
                               'CHK-3A': agent_state('CHK-3A'),
                               'CHK-4': f'{len(faults_rows) + 1} runs, '
                                        f'{sum(r["status"] == "NOT_EVALUATED" for r in faults_rows)} not evaluated',
                               'CHK-5': agent_state('CHK-5'),
                               'CHK-6': 'ok' if os.path.exists(os.path.join(out, 'chk6', 'order.csv')) else 'not run'},
                    'rules': 'The specification is the oracle. Cite the requirement ID for every change.'},
           'protected': protected, 'findings': findings}
    with open(os.path.join(out, 'findings.json'), 'w') as f:
        json.dump(doc, f, indent=1, ensure_ascii=False)
    kinds = collections.Counter(f['check'] for f in findings)
    print(f'findings {len(findings)} (' + ', '.join(f'{k} {v}' for k, v in sorted(kinds.items())) +
          f'); protected {len(protected)}; audit wrong ' +
          ', '.join(f'{c} {wrong[c]}/{n_audited[c]}' for c in AGENT_CHECKS) +
          (f'; withheld: {", ".join(sorted(withheld))}' if withheld else ''))


def main():
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = p.add_subparsers(dest='cmd', required=True)
    pt = sub.add_parser('tags')
    pt.add_argument('out')
    src = pt.add_mutually_exclusive_group(required=True)
    src.add_argument('--suite', nargs=2, metavar=('REPO', 'REF'))
    src.add_argument('--sample')
    pt.add_argument('--expect', type=int)
    for name in ('stability', 'order'):
        ps = sub.add_parser(name)
        ps.add_argument('out')
        ps.add_argument('repo')
        ps.add_argument('ref')
        ps.add_argument('--analyze', action='store_true', help='recompute the verdicts from the stored runs')
    pf = sub.add_parser('faults')
    pf.add_argument('out')
    pf.add_argument('matrix', help='folder with matrix.csv and failures.csv (matrix.py)')
    pf.add_argument('mutants', help='MUTANTS.md of the faulty versions')
    pa = sub.add_parser('audit')
    pa.add_argument('out')
    pa.add_argument('workdir', help='workdir of the agent checks (review.py)')
    pa.add_argument('--seed', type=int, default=20261023)
    pa.add_argument('--force', action='store_true', help='select again over an existing AUDIT.md')
    pm = sub.add_parser('assemble')
    pm.add_argument('out')
    pm.add_argument('workdir', help='workdir of the agent checks (review.py)')
    args = p.parse_args()
    return {'tags': tags, 'stability': stability, 'order': order, 'faults': faults, 'audit': audit,
            'assemble': assemble}[args.cmd](args)


if __name__ == '__main__':
    sys.exit(main())
