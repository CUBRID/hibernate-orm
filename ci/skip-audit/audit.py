"""Measure every CUBRID @SkipForDialect one at a time.

A skip only tells us what someone believed when they wrote it. To learn what a
test actually does today, the annotation has to come off and the test has to
run. Doing that for all of them at once is useless: a single failure rebuilds
the SessionFactory and wipes the shared data, so the rest of the class fails for
a reason that has nothing to do with its own skip.

So the work is split into groups. A group never removes two skips from the same
class, which keeps every measurement free of that cascade, and never holds more
than MAX_CLASSES so the groups can run as a CI matrix instead of end to end.
"""

import collections
import glob
import json
import pathlib
import re
import sys
import xml.etree.ElementTree as ET

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parent.parent
MARKER = 'dialectClass = CUBRIDDialect.class'
MAX_CLASSES = 8


def groups():
    items = json.loads((HERE / 'skips.json').read_text())
    by_class = collections.defaultdict(list)
    for item in items:
        by_class[item['path']].append(item)
    for lst in by_class.values():
        lst.sort(key=lambda x: x['line'])

    out = []
    for round_index in range(max(len(l) for l in by_class.values())):
        targets = [l[round_index] for l in by_class.values() if round_index < len(l)]
        targets.sort(key=lambda t: t['path'])
        count = -(-len(targets) // MAX_CLASSES)
        # Round robin rather than slicing: the inventory is ordered by how many
        # skips a class has, so slicing would pile the heavy classes together.
        for i in range(count):
            out.append(targets[i::count])
    return out


def fqcn(path):
    return path.split('/src/test/java/')[1].removesuffix('.java').replace('/', '.')


def apply(group):
    targets = groups()[group]
    for target in targets:
        path = ROOT / target['path']
        lines = path.read_text().splitlines(keepends=True)
        line = lines[target['line'] - 1]
        if MARKER not in line:
            sys.exit(f"{target['path']}:{target['line']} is not a CUBRID skip: {line.strip()}")
        del lines[target['line'] - 1]
        path.write_text(''.join(lines))
    (HERE / f'group{group}-targets.json').write_text(json.dumps(targets, indent=1))
    print(' '.join(f'--tests {fqcn(t["path"])}*' for t in targets))


def collect(group):
    targets = json.loads((HERE / f'group{group}-targets.json').read_text())
    seen = {}
    rank = {'pass': 0, 'skip': 1, 'FAIL': 2}
    for report in glob.glob(str(ROOT / 'hibernate-core/target/test-results/test/*.xml')):
        try:
            root = ET.parse(report).getroot()
        except ET.ParseError:
            continue
        cls = root.get('name', '').split('.')[-1].split('$')[0]
        for case in root.iter('testcase'):
            method = case.get('name', '').split('(')[0]
            problem = case.find('failure')
            if problem is None:
                problem = case.find('error')
            if case.find('skipped') is not None:
                status = 'skip'
            elif problem is not None:
                status = 'FAIL'
            else:
                status = 'pass'
            message = ''
            if problem is not None:
                message = re.sub(r'\s+', ' ', f"{problem.get('message') or ''} {problem.text or ''}")[:400]
            key = (cls, method)
            if key not in seen or rank[status] > rank[seen[key][0]]:
                seen[key] = (status, message)

    results = []
    for target in targets:
        if target['method'] == '<CLASS>':
            found = [v for (c, _), v in seen.items() if c == target['cls']]
            statuses = [s for s, _ in found]
            if not statuses:
                status, message = 'NOTRUN', ''
            elif 'FAIL' in statuses:
                status = 'FAIL'
                message = next(m for s, m in found if s == 'FAIL')
            else:
                status, message = ('pass' if set(statuses) == {'pass'} else 'mixed'), ''
        else:
            status, message = seen.get((target['cls'], target['method']), ('NOTRUN', ''))
        results.append({**target, 'status': status, 'actual': message})

    (HERE / f'group{group}-result.json').write_text(json.dumps(results, indent=1))
    for r in results:
        print(f"  {r['status']:7} {r['cls']}.{r['method']}")
    missing = [r for r in results if r['status'] == 'NOTRUN']
    if missing:
        sys.exit(f'{len(missing)} of {len(results)} targets never ran')


if __name__ == '__main__':
    command = sys.argv[1]
    if command == 'plan':
        for i, g in enumerate(groups()):
            print(f'group {i}: {len(g)} classes  ' + ' '.join(t['cls'] for t in g))
        print(f'{len(groups())} groups, {sum(len(g) for g in groups())} skips')
    elif command == 'apply':
        apply(int(sys.argv[2]))
    elif command == 'collect':
        collect(int(sys.argv[2]))
    else:
        sys.exit(f'unknown command {command}')
