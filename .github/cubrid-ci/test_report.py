import json
import os
import tempfile
import unittest

import report

ENV = {'image': 'img', 'engine': '11.4.6.1963', 'digest': 'sha256:aaa', 'driver': '11.3.2.0053',
       'driver_source': 'maven', 'runner': 'ubuntu24 1', 'jdk': 'openjdk 25', 'commit': 'c2'}
A = 'core\torg.example.ATest\ta()'
B = 'core\torg.example.BTest\tb()'
C = 'core\torg.example.CTest\tc()'
S = 'envers\torg.example.STest\ts()'
RUN = {'id': '2', 'url': 'https://example.org/run/2', 'date': '2026-11-10',
       'repo_url': 'https://example.org/repo'}


def result(version='11.4', failures=None, skips=None, passes=None, modules=None, env=None, status='ok'):
    return {'version': version, 'status': status, 'run_id': '2', 'env': dict(ENV, **(env or {})),
            'modules': modules or {'core': {'tests': 10, 'passed': 8, 'skipped': 1, 'failed': 1}},
            'failures': {k: {'n': n, 'message': 'boom ' + k.split('\t')[-1]} for k, n in (failures or {}).items()},
            'skips': skips or {}, 'passes': passes or {}}


def prev(version_result, since=None):
    entry = {k: version_result[k] for k in ('env', 'modules', 'skips')}
    entry['failures'] = {k: dict(f, since=(since or {}).get(k)) for k, f in version_result['failures'].items()}
    return entry


def state(versions):
    return {'run_id': '1', 'date': '2026-11-09', 'versions': versions}


class CompareTest(unittest.TestCase):
    def test_more_failures_of_a_test_count_as_new(self):
        out = report.compare_version(result(failures={A: 2, B: 1}), prev(result(failures={A: 1})))
        self.assertEqual([(A, 1), (B, 1)], [(i['key'], i['n']) for i in out['new']])
        self.assertEqual([(A, 1)], [(i['key'], i['n']) for i in out['still']])

    def test_fixed_tests_say_what_they_are_now(self):
        out = report.compare_version(result(skips={A: 1}, passes={B: 1}),
                                     prev(result(failures={A: 1, B: 1, C: 1})))
        self.assertEqual({A: 'skipped', B: 'passed', C: 'removed'}, {i['key']: i['now'] for i in out['fixed']})

    def test_skip_changes_are_counted_per_module(self):
        out = report.compare_version(result(skips={S: 3}), prev(result(skips={A: 2})))
        self.assertEqual({'core': (0, 2), 'envers': (3, 0)}, out['skips'])

    def test_missing_module_and_test_count(self):
        before = result(modules={'core': {'tests': 10, 'passed': 10, 'skipped': 0, 'failed': 0},
                                 'envers': {'tests': 5, 'passed': 5, 'skipped': 0, 'failed': 0}})
        now = result(modules={'core': {'tests': 12, 'passed': 12, 'skipped': 0, 'failed': 0}})
        out = report.compare_version(now, prev(before))
        self.assertEqual(['envers'], out['missing_modules'])
        self.assertEqual(-3, out['tests'])

    def test_environment_changes_ignore_unknown_values(self):
        out = report.compare_version(result(env={'driver': '11.4.0.0100', 'engine': 'unknown'}), prev(result()))
        self.assertEqual({'driver': ('11.3.2.0053', '11.4.0.0100')}, out['env'])

    def test_without_a_previous_state_every_failure_is_still_failing(self):
        out = report.compare_version(result(failures={A: 1}), None)
        self.assertEqual([], out['new'])
        self.assertEqual([(A, None)], [(i['key'], i['since']) for i in out['still']])


class TitleTest(unittest.TestCase):
    def test_titles(self):
        t = report.make_title
        self.assertEqual('CUBRID CI 2026-11-10: 1 new failure, 11 fixed', t('2026-11-10', True, [], 1, 11, 69))
        self.assertEqual('CUBRID CI 2026-11-10: 3 new failures', t('2026-11-10', True, [], 3, 0, 69))
        self.assertEqual('CUBRID CI 2026-11-10: no new failures, 69 still failing',
                         t('2026-11-10', True, [], 0, 0, 69))
        self.assertEqual('CUBRID CI 2026-11-10: all passed, 2 fixed', t('2026-11-10', True, [], 0, 2, 0))
        self.assertEqual('CUBRID CI 2026-11-10: 69 failing (no baseline)', t('2026-11-10', False, [], 0, 0, 69))
        self.assertEqual('CUBRID CI 2026-11-10: all passed (no baseline)', t('2026-11-10', False, [], 0, 0, 0))
        self.assertEqual('CUBRID CI 2026-11-10: run incomplete (11.4, Nightly), all passed',
                         t('2026-11-10', True, ['11.4', 'Nightly'], 0, 0, 0))


class BuildTest(unittest.TestCase):
    def test_new_failure_keeps_the_issue_open_and_stamps_since(self):
        base = state({'11.4': prev(result(failures={A: 1}), since={A: {'run_id': '0', 'date': '2026-11-01'}})})
        out = report.build_report({'11.4': result(failures={A: 1, B: 1})}, base, {}, ['11.4'], RUN)
        self.assertTrue(out['keep_open'])
        failures = out['state']['versions']['11.4']['failures']
        self.assertEqual({'run_id': '0', 'date': '2026-11-01'}, failures[A]['since'])
        self.assertEqual({'run_id': '2', 'date': '2026-11-10'}, failures[B]['since'])

    def test_unchanged_failures_close_the_issue(self):
        base = state({'11.4': prev(result(failures={A: 1}))})
        out = report.build_report({'11.4': result(failures={A: 1})}, base, {}, ['11.4'], RUN)
        self.assertFalse(out['keep_open'])
        self.assertEqual('CUBRID CI 2026-11-10: no new failures, 1 still failing', out['title'])

    def test_missing_version_is_incomplete_and_keeps_its_state(self):
        nightly = prev(result(version='Nightly'))
        base = state({'11.4': prev(result()), 'Nightly': nightly})
        out = report.build_report({'11.4': result()}, base, {}, ['11.4', 'Nightly'], RUN)
        self.assertEqual(['Nightly'], out['incomplete'])
        self.assertTrue(out['keep_open'])
        self.assertIs(nightly, out['state']['versions']['Nightly'])

    def test_no_results_counts_as_not_run(self):
        out = report.build_report({'11.4': result(status='no-results')}, None, {}, ['11.4'], RUN)
        self.assertEqual(['11.4'], out['incomplete'])

    def test_without_a_baseline_nothing_is_new(self):
        out = report.build_report({'11.4': result(failures={A: 2})}, None, {}, ['11.4'], RUN)
        self.assertEqual('CUBRID CI 2026-11-10: 2 failing (no baseline)', out['title'])
        self.assertFalse(out['keep_open'])
        self.assertIsNone(out['state']['versions']['11.4']['failures'][A]['since'])


class RenderTest(unittest.TestCase):
    def setUp(self):
        base = state({'11.4': prev(result(failures={A: 1, C: 1}, skips={S: 1})),
                      'Nightly': prev(result(version='Nightly', failures={A: 1}))})
        results = {'11.4': result(failures={A: 1, B: 1}, skips={S: 1, C: 1}, env={'driver': '11.4.0.0100'}),
                   'Nightly': result(version='Nightly', failures={A: 1, B: 1})}
        compare = {'commits': 3, 'keys': ['HHH-1', 'HHH-2'], 'url': 'https://example.org/compare'}
        self.report = report.build_report(results, base, compare, ['11.4', 'Nightly'], RUN)

    def test_summary(self):
        text = report.render(self.report, full=True)
        self.assertIn('## CUBRID CI 2026-11-10: 1 new failure, 1 fixed', text)
        self.assertIn('| 11.4 | failed | 10 | 1 | 1 | 1 | 1 |', text)
        self.assertIn('- Hibernate: 3 commits (HHH-1, HHH-2) ([compare](https://example.org/compare))', text)
        self.assertIn('- JDBC driver: 11.4: 11.3.2.0053 → 11.4.0.0100', text)
        self.assertIn('- `BTest.b()` (core): all versions', text)
        self.assertIn('- `CTest.c()` (core): now skipped, 11.4', text)
        self.assertIn('- core: 1 newly skipped (11.4)', text)
        self.assertIn('<details><summary>1 failure in 1 test</summary>', text)

    def test_issue_links_the_still_failing_list(self):
        text = report.render(self.report, full=False)
        self.assertNotIn('<details>', text)
        self.assertIn('1 failure in 1 test. Full list in the [run summary](https://example.org/run/2).', text)
        self.assertFalse(text.startswith('## '))


class EnvironmentTest(unittest.TestCase):
    def test_without_a_baseline_each_value_names_its_versions(self):
        results = {'11.4': result(env={'engine': '11.4.7.1994'}),
                   'Nightly': result(version='Nightly', env={'engine': '11.5.0.2856', 'driver': '11.4.0.0088'})}
        text = report.render(report.build_report(results, None, {}, ['11.4', 'Nightly'], RUN), full=False)
        self.assertIn('- Engine build: 11.4.7.1994 (11.4); 11.5.0.2856 (Nightly)', text)
        self.assertIn('- JDBC driver: 11.3.2.0053 (11.4); 11.4.0.0088 (Nightly)', text)
        self.assertIn('- JDK: openjdk 25\n', text)


class MainTest(unittest.TestCase):
    def test_main_writes_every_output(self):
        tmp = tempfile.mkdtemp()
        os.makedirs(os.path.join(tmp, 'results'))
        with open(os.path.join(tmp, 'results', 'cubrid-results-11.4.json'), 'w', encoding='utf-8') as f:
            json.dump(result(failures={A: 1}), f)
        out = os.path.join(tmp, 'out')
        missing = os.path.join(tmp, 'missing.json')
        report.main(['--results-dir', os.path.join(tmp, 'results'), '--baseline', missing, '--compare', missing,
                     '--expect', '11.4', '--date', '2026-11-10', '--run-id', '2', '--run-url', RUN['url'],
                     '--repo-url', RUN['repo_url'], '--out-dir', out])
        self.assertEqual(['cubrid-report-state.json', 'incomplete.txt', 'issue.md', 'keep-open.txt',
                          'summary.md', 'title.txt'], sorted(os.listdir(out)))
        with open(os.path.join(out, 'title.txt'), encoding='utf-8') as f:
            self.assertEqual('CUBRID CI 2026-11-10: 1 failing (no baseline)', f.read())


if __name__ == '__main__':
    unittest.main()
