# CUBRID continuous testing for Hibernate ORM

[![CUBRID CI](https://github.com/CUBRID/hibernate-orm/actions/workflows/cubrid-ci.yml/badge.svg)](https://github.com/CUBRID/hibernate-orm/actions/workflows/cubrid-ci.yml)

This is a fork of [hibernate/hibernate-orm](https://github.com/hibernate/hibernate-orm) that
CUBRID Inc. uses to continuously test `CUBRIDDialect`.

`CUBRIDDialect` ships in `hibernate-community-dialects`. Hibernate's own CI does not run
community dialects, so a change in Hibernate that breaks CUBRID would otherwise go unnoticed
until a release. This fork closes that gap: it merges the latest Hibernate every day and runs
the full test suite against every CUBRID version we support.

## What runs

| | |
| --- | --- |
| Schedule | Every day at 18:30 UTC |
| CUBRID versions | 10.2, 11.0, 11.2, 11.3, 11.4 |
| Scope | `./gradlew ciCheck`, the same goal Hibernate uses for its own database jobs |
| Source | Latest `hibernate/main`, merged into the branch carrying our dialect work |

A failure means either that CUBRID cannot run something Hibernate now expects, or that the
merge conflicted. Either way we are notified the same day.

Results, including per-version test reports, are on the
[CUBRID CI](https://github.com/CUBRID/hibernate-orm/actions/workflows/cubrid-ci.yml) page.

## Tests that do not run

Where CUBRID genuinely cannot support a feature, the test is marked rather than left failing,
and every marker carries the reason. These fall into three groups: features the CUBRID engine
does not have, gaps in the CUBRID JDBC driver, and a small number of Hibernate issues we report
upstream separately.

## This is not a distribution

Nothing is published from this fork. Use the official artifacts:

```groovy
implementation 'org.hibernate.orm:hibernate-core'
implementation 'org.hibernate.orm:hibernate-community-dialects'
```

Dialect changes are contributed upstream through
[Hibernate's JIRA](https://hibernate.atlassian.net/browse/HHH) and pull requests, not kept here.

## Contact

Maintained by CUBRID Inc. For dialect issues, open an issue on
[hibernate.atlassian.net](https://hibernate.atlassian.net/browse/HHH) with the `CUBRID` label,
or reach us through [cubrid.org](https://www.cubrid.org/).
