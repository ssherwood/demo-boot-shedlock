# Demo Spring Boot with Shedlock

This is a simple Spring Boot demo application using Shedlock to explore some subtle differences in transaction
isolation between Yugabyte (YSQL) and a vanilla JDBC datasource (in this example, Postgres).

## UPDATE

Per the [issue](https://github.com/lukas-krecan/ShedLock/issues/207) that I opened with Shedlock, the 4.27.0 release
has a customizable isolation level.  As long as the lockProvider is configured with SERIALIZABLE, all the errors
documented below go away.

---

## Project Setup

This project is configured to connect to either a Yugabyte or Postgres instance at the default localhost and port
configuration.  Use the [schema.sql](/src/main/resources/schema.sql) file to create the Shedlock table prior to running
any tests.

To run the application from the command line, type:

```shell
./gradlew :bootRun --args='--spring.profiles.active=yugabyte'
```

For Postgres use the `postgres` profile instead.  Note: Yugabyte is the default profile, so the `--args` is
technically not required for those scenarios.

To fully experience the concurrency behavior of Shedlock, you will need to run more than one instance of the app 
(usually 3 - 5 are enough).  Open multiple console tabs or use a terminal multiplexer to run each and review the logs.
The underlying Spring Boot config is set up to start on a random port between 8000 - 9000, so there will rarely be a 
port conflict when starting the app.

## Observations

Yugabyte currently supports `Snapshot` and `Serializable` [transaction isolation levels](
https://docs.yugabyte.com/latest/explore/transactions/isolation-levels/).

In YSQL, Yugabyte maps `Snapshot` to `Read Uncommitted`, `Read Committed`, and `Repeatable Read`.  For any
existing applications / frameworks already implemented against other isolation levels, sometimes unanticipated
errors may occur.  Specifically, the Postgres default of `Read Committed` will allow `Nonrepeatable Reads` and
`Phantom Reads`, but `Snapshot` isolation (the Yugabyte default) will not.

In this specific scenario, the [Shedlock](https://github.com/lukas-krecan/ShedLock) library is designed to take
advantage of transaction locking semantics to prevent concurrent instances of an application from executing a specific
piece of guarded logic (e.g. a "batch" operation).  To mediate this, Shedlock uses a single row in a specific table to
coordinate between instances.

On a periodic basis all running instances will attempt to update the Shedlock row, but only one is
expected to succeed (by design, all others receive a transaction error).  In the latter case, the application will log
a message: `Not executing 'TaskScheduler_scheduledTask'. It's locked.`.  This implementation helps solve for a class of
problems in scheduling a single execution of work in a cloud-native fashion.

In most cases this works just fine and on the database side an error will be logged: `ERROR:  Query error: Restart read required at: ...` -
this is expected to be received by all nodes that did not "win" the update lock and are programmed to quietly suppress
the error and go back to sleep.  However, in some iterations, the application may log the following error to the
console:

```text
2021-08-09 10:39:00.015 ERROR 31166 --- [   scheduling-1] n.j.s.p.j.JdbcTemplateStorageAccessor    : Unexpected exception

JDBC commit failed; nested exception is org.postgresql.util.PSQLException: ERROR: Operation expired: Transaction f391d0e4-8921-4ded-b71e-f44ba6da2bfa expired or aborted by a conflict: 40001
...
```

This happens because the underlying database, while in `Snapshot` isolation, sometimes generates a different error than
Shedlock was coded to expected.  In these cases, the database will log a different error:

```text
I0809 14:32:00.018165 31316 tablet_rpc.cc:434] Operation failed. Try again. (yb/docdb/conflict_resolution.cc:72): Failed Write(tablet: 426b613ab0514c4d8de772a0b5031176, num_ops: 1, num_attempts: 1, txn: 1aa64929-e5f5-4438-9bb1-8a1a466be30f) to tablet 426b613ab0514c4d8de772a0b5031176 on tablet server { uuid: d6e6da18996a4d02b77124e0e0729f04 private: [host: "127.0.0.1" port: 9100] cloud_info: placement_cloud: "cloud1" placement_region: "datacenter1" placement_zone: "rack1" after 1 attempt(s): 1aa64929-e5f5-4438-9bb1-8a1a466be30f Conflicts with higher priority transaction: adde4811-084b-425e-9f47-0378b02265c7 (transaction error 3)
```

This ultimately gets mapped in Spring Boot to a `TransactionSystemException` instead of the more specific
`CannotSerializeTransactionException`.  The logic in Shedlock was not implemented to handle the former gracefully and
instead logs an error to the console (which can be seen [here](https://github.com/lukas-krecan/ShedLock/blob/46f6fe4bb31ee9b18c117443659f8be4d167f7c8/providers/jdbc/shedlock-provider-jdbc-template/src/main/java/net/javacrumbs/shedlock/provider/jdbctemplate/JdbcTemplateStorageAccessor.java#L67)).

This message appears to be ignorable as it is just indicative that a specific instance was not successful in updating
the Shedlock row and thus will just go back to sleep to wait for the next scheduled attempt.  Or, to put it more
precisely, in every iteration tested, one instance of the application is always successful and these errors do not
appear to indicate any specific kind of Shedlock failure.

## Experiments

Here are a few experiments and outcomes that have been tried so far:

### 1. Alter the default isolation level

Spring Boot currently uses Hikari as it's database connection pool implementation.  It is possible to override the
default isolation level this way:

```yaml
spring:
  datasource:
    hikari:
      transaction-isolation: 8
```

Isolation levels are mapped using this [class](https://github.com/brettwooldridge/HikariCP/blob/dev/src/main/java/com/zaxxer/hikari/util/IsolationLevel.java).

If the isolation level is adjusted to `Serializable` (8 in the case of Hikari), then the unexpected `Operation expired`
error goes away and the client application does not log any error messages.

The impact of this change however is that it is global to the applications specific datasource configuration.  It may
not be desirable to have the entire application default to this high a level of isolation as it may degrade other OLTP
performance.

NOTE: It may be possible to work around this by creating a separate Datasource configuration just for Shedlock and wire it up
manually (see [How to Configure Multiple Data Sources in a Spring Boot Application](https://springframework.guru/how-to-configure-multiple-data-sources-in-a-spring-boot-application/))
and customize the LockProvider Datasource.

### 2. Use Spring @Transactional Annotation on the Scheduled Task

Transaction isolation levels can be altered using Spring's `@Transactional` annotation on logic wrapping database
operations.  This will allow for code to be isolated uniquely without having to change the global/default isolation
level.

#### Results

The current attempt that is presented in this repo does not really work.  Specifically, the Shedlock locking behavior is
invoked prior to the execution of the scheduleTask method, so the annotations affect does not have an impact until after
the Shedlock error occurs.

The Shedlock JDBC implementation does look somewhat customizable, so it may be possible to create an extension that
allows for transaction isolation setup in the locking code itself (specifically, look at [JdbcTemplateStorageAccessor](https://github.com/lukas-krecan/ShedLock/blob/master/providers/jdbc/shedlock-provider-jdbc-template/src/main/java/net/javacrumbs/shedlock/provider/jdbctemplate/JdbcTemplateStorageAccessor.java)).

### 3. Create the Shedlock table with 1 tablet

The working hypothesis was that the transaction isolation semantics
are a bit different due to the additional number of tablets that are created
for a 1 row table.  By reducing it to 1 tablet, the `ERROR:  Operation expired: Transaction 9441a34b-18b8-4353-a525-1a3016651066 expired or aborted by a conflict: 40001`
might be avoided.

```sql
CREATE TABLE shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
) SPLIT INTO 1 TABLETS;
```

#### Results

This does not appear to change any database error behavior.  Most instances appear to get the `Operation failed. Try again.`
but in several iterations the `Operation expired` error was still observable.  However, since the Shedlock table is
designed to be a single row, using 1 tablet is a reasonable optimization.
