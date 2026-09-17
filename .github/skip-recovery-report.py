"""Compare a baseline suite run against one with the CUBRID skips stripped out.

The baseline reports every CUBRID-blocked test as skipped, which says nothing about
whether the defect behind it is still there. The stripped run actually executes them,
so the difference between the two is the answer: of the tests CUBRID cannot currently
run, how many would pass now.
"""
import sys, glob, os, xml.etree.ElementTree as ET


def read(root):
    tests = {}
    for path in glob.glob(os.path.join(root, '**', '*.xml'), recursive=True):
        try:
            suites = ET.parse(path).getroot()
        except ET.ParseError:
            continue
        for suite in ([suites] if suites.tag == 'testsuite' else suites.iter('testsuite')):
            for case in suite.iter('testcase'):
                name = f"{case.get('classname')}.{case.get('name')}"
                if case.find('skipped') is not None:
                    state = 'skipped'
                elif case.find('failure') is not None or case.find('error') is not None:
                    state = 'failed'
                else:
                    state = 'passed'
                # A test can appear once per module; the worst state is the honest one.
                if tests.get(name) != 'failed':
                    tests[name] = state
    return tests


def main(results):
    base = read(os.path.join(results, 'skip-recovery-baseline'))
    strip = read(os.path.join(results, 'skip-recovery-stripped'))
    if not base or not strip:
        print('One of the two runs produced no results, so there is nothing to compare.')
        return 1

    unskipped = {n for n, s in base.items() if s == 'skipped' and strip.get(n, 'skipped') != 'skipped'}
    recovered = sorted(n for n in unskipped if strip[n] == 'passed')
    still = sorted(n for n in unskipped if strip[n] == 'failed')
    # A test that fails only once the skips are gone elsewhere is a side effect, not a skip.
    regressed = sorted(n for n, s in strip.items() if s == 'failed' and base.get(n) == 'passed')

    print('## CUBRID skip recovery')
    print()
    print('| | count |')
    print('|---|---:|')
    print(f'| Skipped in the baseline | {sum(1 for s in base.values() if s == "skipped")} |')
    print(f'| Of those, actually run once stripped | {len(unskipped)} |')
    print(f'| **Now passing** | **{len(recovered)}** |')
    print(f'| Still failing, the skip is still justified | {len(still)} |')
    if regressed:
        print(f'| Newly failing though never skipped | {len(regressed)} |')
    print()

    if recovered:
        print(f'### {len(recovered)} skips can be removed')
        print()
        for n in recovered:
            print(f'- `{n}`')
        print()
    else:
        print('No skipped test passes yet. Every exclusion is still justified.')
        print()

    if regressed:
        print('### Newly failing, unrelated to the skips')
        print()
        print('These passed in the baseline and failed here, so removing the annotations')
        print('changed something around them. Worth a look before trusting the number above.')
        print()
        for n in regressed:
            print(f'- `{n}`')
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1]))
