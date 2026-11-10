"""Summarize one CUBRID CI job for the daily report: test outcomes per module, the failing,
skipped and passing tests, and the environment the tests ran in.

usage: python3 collect.py --version 11.4 --root . --out cubrid-results-11.4.json
         [--run-id ID] [--image I] [--engine E] [--digest D] [--driver V]
         [--driver-source maven|image] [--runner R] [--jdk J] [--commit SHA]"""
import argparse
import glob
import json
import os
import re
import xml.etree.ElementTree as ET

# Lambda class names and identity hashes in parameterized test names change on every run
NOISE = re.compile(r'\$\$Lambda/0x[0-9a-f]+@[0-9a-f]+|@[0-9a-f]{5,8}\b')
ENV_FIELDS = ('image', 'engine', 'digest', 'driver', 'driver_source', 'runner', 'jdk', 'commit')


def test_key(module, classname, name):
    name = NOISE.sub('', re.sub(r'\s+', ' ', name or '')).strip()
    return f"{module}\t{classname or ''}\t{name}"


def first_line(text, limit=160):
    return (text or '').strip().split('\n', 1)[0][:limit]


def collect(root):
    modules, failures, skips, passes = {}, {}, {}, {}
    pattern = os.path.join(root, '**', 'target', 'test-results', '**', '*.xml')
    files = sorted(glob.glob(pattern, recursive=True))
    for path in files:
        module = os.path.relpath(path, root).replace(os.sep, '/').split('/target/', 1)[0]
        counts = modules.setdefault(module, {'tests': 0, 'passed': 0, 'skipped': 0, 'failed': 0})
        for _, el in ET.iterparse(path, events=('end',)):
            if el.tag != 'testcase':
                continue
            key = test_key(module, el.get('classname'), el.get('name'))
            bad = el.find('failure')
            if bad is None:
                bad = el.find('error')
            counts['tests'] += 1
            if bad is not None:
                counts['failed'] += 1
                message = first_line(bad.get('message') or bad.text or bad.get('type'))
                failures.setdefault(key, {'n': 0, 'message': message})['n'] += 1
            elif el.find('skipped') is not None:
                counts['skipped'] += 1
                skips[key] = skips.get(key, 0) + 1
            else:
                counts['passed'] += 1
                passes[key] = passes.get(key, 0) + 1
            el.clear()
    return {'status': 'ok' if files else 'no-results', 'modules': modules,
            'failures': failures, 'skips': skips, 'passes': passes}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split('\n', 1)[0])
    parser.add_argument('--version', required=True)
    parser.add_argument('--root', default='.')
    parser.add_argument('--out', required=True)
    parser.add_argument('--run-id', default='')
    for field in ENV_FIELDS:
        parser.add_argument('--' + field.replace('_', '-'), default='unknown')
    args = parser.parse_args(argv)
    result = collect(args.root)
    result['version'] = args.version
    result['run_id'] = args.run_id
    result['env'] = {field: getattr(args, field) or 'unknown' for field in ENV_FIELDS}
    with open(args.out, 'w', encoding='utf-8') as out:
        json.dump(result, out, ensure_ascii=False, sort_keys=True)
    tests = sum(m['tests'] for m in result['modules'].values())
    failed = sum(f['n'] for f in result['failures'].values())
    print(f"{args.version}: {result['status']}, {tests} tests, {failed} failed")


if __name__ == '__main__':
    main()
