# CUBRID continuous testing for Hibernate ORM

This is a fork of [hibernate/hibernate-orm](https://github.com/hibernate/hibernate-orm) that
CUBRID Inc. uses to test `CUBRIDDialect` every day.

`CUBRIDDialect` is part of `hibernate-community-dialects`. Hibernate's own CI does not run
community dialects, so a Hibernate change that breaks CUBRID could go unnoticed until a
release. This fork runs the full test suite against CUBRID every day to catch that early.

## What runs

| | |
| --- | --- |
| Schedule | Every day at 18:30 UTC |
| CUBRID versions | 10.2, 11.0, 11.2, 11.3, 11.4, and the latest development build |
| JDBC driver | The released driver from Maven Central. The development build uses the driver it ships with |
| Scope | `./gradlew ciCheck`, the same goal Hibernate uses for its own database jobs |
| Source | The `cubrid-daily` branch (dialect work not yet in Hibernate) with the latest `hibernate/main` merged in |

If the merge conflicts, the run tests `cubrid-daily` alone and shows a warning.

Results and test reports for each version are on the
[CUBRID CI](https://github.com/CUBRID/hibernate-orm/actions/workflows/cubrid-ci.yml) page.

## Reading the results

The run is red on purpose. Tests that fail because of a CUBRID JDBC driver defect or a
Hibernate issue are not skipped. They stay failing until the fix is released, and then pass
without any change here. So look at which tests failed: a failure that is not on the known
list is a new problem.

The known failures and excluded tests are tracked in
[TOOLS-4940](http://jira.cubrid.org/browse/TOOLS-4940) (in Korean).

## Tests that do not run

When CUBRID does not support a feature, the test is excluded with a `DialectFeatureCheck`,
the same way Hibernate handles other databases. A few tests are skipped by dialect name, each
with the reason.

## This is not a distribution

Nothing is published from this fork. Use the official artifacts:

```groovy
implementation 'org.hibernate.orm:hibernate-core'
implementation 'org.hibernate.orm:hibernate-community-dialects'
```

Dialect changes go upstream through [Hibernate's JIRA](https://hibernate.atlassian.net/browse/HHH)
and pull requests. They are not kept here.

## Contact

Maintained by CUBRID Inc. For dialect issues, open an issue on
[hibernate.atlassian.net](https://hibernate.atlassian.net/browse/HHH), or reach us through
[cubrid.org](https://www.cubrid.org/).
