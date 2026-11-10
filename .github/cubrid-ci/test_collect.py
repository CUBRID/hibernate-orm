import json
import os
import tempfile
import unittest

import collect

XML = """<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="org.example.ArrayTest" tests="4" skipped="1" failures="1" errors="1">
  <testcase name="[1] &quot;intArray&quot;, org.example.ArrayTest$$Lambda/0x0000000056044b60@6a0d0284" classname="org.example.ArrayTest" time="0.1">
    <failure message="java.lang.AssertionError: expected 1&#10;second line" type="java.lang.AssertionError">trace</failure>
  </testcase>
  <testcase name="[1] &quot;intArray&quot;, org.example.ArrayTest$$Lambda/0x0000000099999999@1234abcd" classname="org.example.ArrayTest" time="0.1">
    <error message="" type="java.lang.IllegalStateException">java.lang.IllegalStateException: boom
	at x</error>
  </testcase>
  <testcase name="skipped()" classname="org.example.ArrayTest" time="0"><skipped/></testcase>
  <testcase name="passes()" classname="org.example.ArrayTest" time="0.2"/>
</testsuite>
"""
KEY = 'tooling/hibernate-ant\torg.example.ArrayTest\t'


class CollectTest(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.mkdtemp()
        results = os.path.join(self.root, 'tooling', 'hibernate-ant', 'target', 'test-results', 'test')
        os.makedirs(results)
        with open(os.path.join(results, 'TEST-org.example.ArrayTest.xml'), 'w', encoding='utf-8') as f:
            f.write(XML)

    def test_counts_outcomes_per_module(self):
        result = collect.collect(self.root)
        self.assertEqual('ok', result['status'])
        self.assertEqual({'tests': 4, 'passed': 1, 'skipped': 1, 'failed': 2},
                         result['modules']['tooling/hibernate-ant'])

    def test_lambda_addresses_do_not_split_a_test(self):
        failures = collect.collect(self.root)['failures']
        entry = failures[KEY + '[1] "intArray", org.example.ArrayTest']
        self.assertEqual(2, entry['n'])
        self.assertEqual('java.lang.AssertionError: expected 1', entry['message'])

    def test_skips_and_passes_are_counted_by_key(self):
        result = collect.collect(self.root)
        self.assertEqual({KEY + 'skipped()': 1}, result['skips'])
        self.assertEqual({KEY + 'passes()': 1}, result['passes'])

    def test_no_xml_means_no_results(self):
        self.assertEqual('no-results', collect.collect(tempfile.mkdtemp())['status'])

    def test_main_writes_version_run_and_environment(self):
        out = os.path.join(self.root, 'out.json')
        collect.main(['--version', '11.4', '--root', self.root, '--out', out,
                      '--engine', '11.4.6.1963-0e7d3c1', '--run-id', '7'])
        with open(out, encoding='utf-8') as f:
            data = json.load(f)
        self.assertEqual('11.4', data['version'])
        self.assertEqual('7', data['run_id'])
        self.assertEqual('11.4.6.1963-0e7d3c1', data['env']['engine'])
        self.assertEqual('unknown', data['env']['driver'])


if __name__ == '__main__':
    unittest.main()
