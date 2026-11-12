"""Compare a CUBRID CI run with the previous scheduled run and write the daily report: the
run summary, the issue title and body, and the state the next run compares with.

usage: python3 report.py --results-dir results --out-dir out --expect 10.2,11.4
         --date 2026-11-10 --run-id ID --run-url URL --repo-url URL
         [--baseline cubrid-report-state.json] [--compare compare.json]"""
import argparse
import glob
import json
import os

ORDER = ['10.2', '11.0', '11.2', '11.3', '11.4', 'Nightly']
ENV_FIELDS = [('engine', 'Engine build'), ('digest', 'Engine image'), ('driver', 'JDBC driver'),
              ('runner', 'Runner'), ('jdk', 'JDK')]
NOW = {'passed': 'now passes', 'skipped': 'now skipped', 'removed': 'test removed'}


def load(path):
    """Read a JSON file. A missing or unreadable file reads as None."""
    try:
        with open(path, encoding='utf-8') as f:
            return json.load(f)
    except (OSError, TypeError, ValueError):
        return None


def ordered(versions):
    return sorted(versions, key=lambda v: (ORDER.index(v) if v in ORDER else len(ORDER), v))


def module_of(key):
    return key.split('\t', 1)[0]


def compare_version(curr, prev):
    """Compare one version's results with that version's previous state (None: no baseline)."""
    failures = curr['failures']
    out = {'new': [], 'fixed': [], 'still': [], 'skips': {}, 'tests': 0, 'missing_modules': [], 'env': {}}
    if prev is None:
        out['still'] = [{'key': k, 'n': f['n'], 'message': f['message'], 'since': None}
                        for k, f in sorted(failures.items())]
        return out
    before = prev['failures']
    for key, now in sorted(failures.items()):
        was = before.get(key)
        was_n = was['n'] if was else 0
        if now['n'] > was_n:
            out['new'].append({'key': key, 'n': now['n'] - was_n, 'message': now['message']})
        if was_n:
            out['still'].append({'key': key, 'n': min(now['n'], was_n), 'message': now['message'],
                                 'since': was.get('since')})
    for key, was in sorted(before.items()):
        now_n = failures.get(key, {}).get('n', 0)
        if was['n'] > now_n:
            now = ('skipped' if key in curr['skips'] else
                   'passed' if key in curr.get('passes', {}) else 'removed')
            out['fixed'].append({'key': key, 'n': was['n'] - now_n, 'now': now})
    for key in set(curr['skips']) | set(prev['skips']):
        delta = curr['skips'].get(key, 0) - prev['skips'].get(key, 0)
        if delta:
            up, down = out['skips'].get(module_of(key), (0, 0))
            out['skips'][module_of(key)] = (up + max(delta, 0), down + max(-delta, 0))
    out['missing_modules'] = sorted(set(prev['modules']) - set(curr['modules']))
    out['tests'] = (sum(m['tests'] for m in curr['modules'].values())
                    - sum(m['tests'] for m in prev['modules'].values()))
    for field, _ in ENV_FIELDS:
        old, new = prev['env'].get(field, 'unknown'), curr['env'].get(field, 'unknown')
        if old != new and 'unknown' not in (old, new):
            out['env'][field] = (old, new)
    return out


def make_title(date, has_baseline, incomplete, new_count, fixed_count, failing):
    parts = []
    if incomplete:
        parts.append('run incomplete (' + ', '.join(incomplete) + ')')
    if not has_baseline:
        parts.append(f'{failing:,} failing (no baseline)' if failing else 'all passed (no baseline)')
    else:
        if new_count:
            parts.append(f"{new_count:,} new failure{'' if new_count == 1 else 's'}")
        elif failing:
            parts.append(f'no new failures, {failing:,} still failing')
        else:
            parts.append('all passed')
        if fixed_count:
            parts.append(f'{fixed_count:,} fixed')
    return f'CUBRID CI {date}: ' + ', '.join(parts)


def build_report(results, baseline, compare, expect, run):
    """Compare every version, then decide the title, whether the issue stays open, and the next state."""
    prev_versions = baseline['versions'] if baseline else {}
    versions = ordered(set(expect) | set(results))
    ran = [v for v in versions if v in results and results[v].get('status') == 'ok']
    not_run = [v for v in versions if v not in ran]
    per_version = {v: compare_version(results[v], prev_versions.get(v) if baseline else None) for v in ran}
    incomplete = [v for v in versions if v in not_run or per_version[v]['missing_modules']]
    new_keys = sorted({i['key'] for c in per_version.values() for i in c['new']})
    fixed_keys = sorted({i['key'] for c in per_version.values() for i in c['fixed']})
    still_keys = sorted({i['key'] for c in per_version.values() for i in c['still']})
    failing = max([sum(f['n'] for f in results[v]['failures'].values()) for v in ran] or [0])
    title = make_title(run['date'], baseline is not None, incomplete, len(new_keys), len(fixed_keys), failing)
    stamp = {'run_id': run['id'], 'date': run['date']}
    state = {'run_id': run['id'], 'date': run['date'], 'versions': {}}
    for v in ran:
        since = {i['key']: i['since'] for i in per_version[v]['still']}
        new = {i['key'] for i in per_version[v]['new']}
        state['versions'][v] = {
            'env': results[v]['env'], 'modules': results[v]['modules'], 'skips': results[v]['skips'],
            'failures': {k: {'n': f['n'], 'message': f['message'],
                             'since': since[k] if k in since else (stamp if k in new else None)}
                         for k, f in results[v]['failures'].items()}}
    for v, entry in prev_versions.items():
        state['versions'].setdefault(v, entry)
    commit = next((results[v]['env'].get('commit', 'unknown') for v in ran), 'unknown')
    return {'title': title, 'run': run, 'baseline': baseline, 'compare': compare or {}, 'commit': commit,
            'versions': versions, 'ran': ran, 'not_run': not_run, 'results': results,
            'per_version': per_version, 'incomplete': incomplete, 'new_keys': new_keys,
            'fixed_keys': fixed_keys, 'still_keys': still_keys,
            'keep_open': bool(incomplete) or bool(new_keys), 'state': state}


def plural(n, word):
    return f"{n:,} {word}{'' if n == 1 else 's'}"


def code(text):
    return '`' + text.replace('`', "'") + '`'


def label(key, table=False):
    module, cls, name = key.split('\t')
    text = code(f"{cls.rsplit('.', 1)[-1]}.{name}")
    return (text.replace('|', '\\|') if table else text), module


def where(versions, ran):
    return 'all versions' if list(versions) == list(ran) and len(ran) > 1 else ', '.join(versions)


def shown(field, value):
    return value[:19] if field == 'digest' else value


def grouped(report, field, keyfunc):
    """Group the per-version items of one field: keyfunc(item) -> (first item, [versions])."""
    groups = {}
    for v in report['ran']:
        for item in report['per_version'][v][field]:
            groups.setdefault(keyfunc(item), (item, []))[1].append(v)
    return groups


def env_lines(report):
    ran = report['ran']
    if report['baseline'] is None:
        lines = []
        for field, title in ENV_FIELDS:
            groups = {}
            for v in ran:
                groups.setdefault(shown(field, report['results'][v]['env'].get(field, 'unknown')), []).append(v)
            if len(groups) == 1:
                text = next(iter(groups))
            else:
                text = '; '.join(f"{value} ({', '.join(vs)})" for value, vs in groups.items()) or 'unknown'
            lines.append(f'- {title}: {text}')
        return lines
    compare = report['compare']
    if compare.get('commits'):
        keys = compare.get('keys', [])
        listed = ', '.join(keys[:8]) + (f' and {len(keys) - 8} more' if len(keys) > 8 else '')
        lines = [f"- Hibernate: {plural(compare['commits'], 'commit')}"
                 + (f' ({listed})' if listed else '') + f" ([compare]({compare['url']}))"]
    elif compare.get('commits') == 0:
        lines = ['- Hibernate: no change']
    else:
        lines = ['- Hibernate: unknown']
    for field, title in ENV_FIELDS:
        changes = {}
        for v in ran:
            change = report['per_version'][v]['env'].get(field)
            if change:
                changes.setdefault(change, []).append(v)
        if changes:
            lines.append(f'- {title}: ' + '; '.join(
                f"{', '.join(vs)}: {shown(field, old)} → {shown(field, new)}" for (old, new), vs in changes.items()))
        else:
            lines.append(f'- {title}: no change')
    return lines


def coverage_lines(report):
    ran, lines, groups, tests = report['ran'], [], {}, {}
    for v in ran:
        for module, (up, down) in report['per_version'][v]['skips'].items():
            groups.setdefault((module, up, down), []).append(v)
        tests.setdefault(report['per_version'][v]['tests'], []).append(v)
    for (module, up, down), vs in sorted(groups.items()):
        parts = ([f'{up:,} newly skipped'] if up else []) + ([f'{down:,} no longer skipped'] if down else [])
        lines.append(f"- {module}: {', '.join(parts)} ({where(vs, ran)})")
    if not groups:
        lines.append('- Skipped tests: no change')
    changed = [(delta, vs) for delta, vs in sorted(tests.items()) if delta]
    for delta, vs in changed:
        lines.append(f'- Tests: {delta:+,} ({where(vs, ran)})')
    if not changed:
        lines.append('- Tests: no change')
    missing = [f"{v}: {', '.join(report['per_version'][v]['missing_modules'])}"
               for v in ran if report['per_version'][v]['missing_modules']]
    lines.append('- Missing modules: ' + ('; '.join(missing) if missing else 'none'))
    return lines


def render(report, full):
    run, ran, base = report['run'], report['ran'], report['baseline']
    lines = [f"## {report['title']}", ''] if full else []
    intro = f"Run [{run['id']}]({run['url']}) tested {code(report['commit'][:10])}"
    if base:
        intro += (f", compared with run [{base['run_id']}]({run['repo_url']}/actions/runs/{base['run_id']})"
                  f" of {base['date']}.")
    else:
        intro += '. There is no previous run to compare with.'
    lines += [intro, '']
    if report['incomplete']:
        reasons = [f'{v} (no results)' if v in report['not_run']
                   else f"{v} (missing {', '.join(report['per_version'][v]['missing_modules'])})"
                   for v in report['incomplete']]
        lines += ['Incomplete run: ' + '; '.join(reasons) + '.', '']
    lines += ['| Version | Result | Tests | Skipped | Failed | New | Fixed |',
              '|---|---|---:|---:|---:|---:|---:|']
    for v in report['versions']:
        if v not in ran:
            lines.append(f'| {v} | not run | | | | | |')
            continue
        mods = report['results'][v]['modules'].values()
        tests, skipped, failed = (sum(m[f] for m in mods) for f in ('tests', 'skipped', 'failed'))
        cmp = report['per_version'][v]
        new, fixed = (len(cmp['new']), len(cmp['fixed'])) if base else ('', '')
        lines.append(f"| {v} | {'passed' if failed == 0 else 'failed'} | {tests:,} | {skipped:,} | "
                     f"{failed:,} | {new} | {fixed} |")
    lines += ['', '### What changed' if base else '### Environment', ''] + env_lines(report)
    if base:
        lines += ['', '### New failures', '']
        groups = grouped(report, 'new', lambda i: i['key'])
        for key, (item, vs) in groups.items():
            name, module = label(key)
            lines += [f'- {name} ({module}): {where(vs, ran)}', f"  {code(item['message'])}"]
        if not groups:
            lines.append('None.')
        lines += ['', '### Fixed', '']
        groups = grouped(report, 'fixed', lambda i: (i['key'], i['now']))
        for (key, now), (item, vs) in groups.items():
            name, module = label(key)
            lines.append(f'- {name} ({module}): {NOW[now]}, {where(vs, ran)}')
        if not groups:
            lines.append('None.')
        lines += ['', '### Coverage', ''] + coverage_lines(report)
    still = report['still_keys']
    lines += ['', '### Still failing', '']
    # some tests run more than once, so count the failures too and match the table
    count = (plural(max(sum(i['n'] for i in report['per_version'][v]['still']) for v in ran), 'failure')
             + ' in ' + plural(len(still), 'test')) if still else ''
    if not still:
        lines.append('None.')
    elif not full:
        lines.append(f"{count}. Full list in the [run summary]({run['url']}).")
    else:
        lines += [f'<details><summary>{count}</summary>', '',
                  '| Test | Module | Versions | Failing since |', '|---|---|---|---|']
        for key, (item, vs) in grouped(report, 'still', lambda i: i['key']).items():
            name, module = label(key, table=True)
            since = item['since']
            when = (f"[{since['date']}]({run['repo_url']}/actions/runs/{since['run_id']})"
                    if since else 'before tracking')
            lines.append(f'| {name} | {module} | {where(vs, ran)} | {when} |')
        lines += ['', '</details>']
    return '\n'.join(lines) + '\n'


def write(out_dir, name, text):
    with open(os.path.join(out_dir, name), 'w', encoding='utf-8') as f:
        f.write(text)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split('\n', 1)[0])
    for name in ('--results-dir', '--out-dir', '--expect', '--date', '--run-id', '--run-url', '--repo-url'):
        parser.add_argument(name, required=True)
    parser.add_argument('--baseline')
    parser.add_argument('--compare')
    args = parser.parse_args(argv)
    results = {}
    for path in sorted(glob.glob(os.path.join(args.results_dir, 'cubrid-results-*.json'))):
        data = load(path)
        if data:
            results[data['version']] = data
    run = {'id': args.run_id, 'url': args.run_url, 'date': args.date, 'repo_url': args.repo_url}
    expect = [v for v in args.expect.split(',') if v]
    rep = build_report(results, load(args.baseline), load(args.compare) or {}, expect, run)
    os.makedirs(args.out_dir, exist_ok=True)
    write(args.out_dir, 'title.txt', rep['title'])
    write(args.out_dir, 'summary.md', render(rep, full=True))
    write(args.out_dir, 'issue.md', render(rep, full=False))
    write(args.out_dir, 'keep-open.txt', 'true' if rep['keep_open'] else 'false')
    write(args.out_dir, 'incomplete.txt', 'true' if rep['incomplete'] else 'false')
    write(args.out_dir, 'cubrid-report-state.json', json.dumps(rep['state'], ensure_ascii=False, sort_keys=True))
    print(rep['title'])


if __name__ == '__main__':
    main()
