# JobRunr Large Error Message Issue - Minimal Reproduction

This project demonstrates an issue where JobRunr fails to deserialize jobs that have very large exception messages stored in `FailedState`.

## The Problem

When a job fails with a very large error message (e.g., a `BatchUpdateException` containing thousands of SQL statements), JobRunr stores the exception message and stack trace as plain String fields in the `FailedState` object. When JobRunr tries to read these jobs back from the database, Jackson fails with:

```
String value length (20030999) exceeds the maximum allowed (20000000,
from `StreamReadConstraints.getMaxStringLength()`)
```

This causes JobRunr to be unable to process ANY scheduled jobs until the problematic job is manually removed from the database.

## Root Cause

The `FailedState` class stores exception data as plain String fields (`exceptionMessage` and `stackTrace`), not as `Throwable` objects. These strings can grow very large (20MB+) for certain exceptions like `BatchUpdateException` with many SQL statements.

## Prerequisites

Before running the tests, you need to set these environment variables:

```bash
export JOBRUNR_REPO_USER="your-jobrunr-pro-username"
export JOBRUNR_REPO_PASS="your-jobrunr-pro-password"
export JOBRUNR_PRO_LICENSE="your-jobrunr-pro-license-key"
```

You also need Docker running for TestContainers.

## Reproduction

Run the test:

```bash
./gradlew test --tests "LargeErrorMessageIssueTest"
```

This will:
1. Start a PostgreSQL container via TestContainers
2. Configure JobRunr with the test database
3. Enqueue a job that fails with a ~21MB error message
4. Wait for the job to fail and be stored in the database
5. Attempt to read the job back from the database
6. **FAIL** with a `StreamConstraintsException` because the message exceeds Jackson's 20MB default limit

The test passes by verifying that the expected `StreamConstraintsException` is thrown on read.

## The Workaround

We've implemented a workaround using `ElectStateFilter` to truncate the error message before the job is saved to the database. This is very ugly though as it uses reflection to modify private variables in the `FailedState` class.

The `ElectStateFilter` must be used instead of the `ApplyStateFilter` as it runs before the job is saved to the database as opposed to after.

We also set the `exception` field to null to prevent JobRunr from re-extracting the full message during serialization.

Run the workaround test:

```bash
./gradlew test --tests "TruncateFailedStateFilterWorkaroundTest"
```

## Run All Tests

```bash
./gradlew test
```

## Suggested Fix

JobRunr should:

1. **Truncate large exception messages internally** before storing them in `FailedState`
2. **Provide public setters** on `FailedState` for `exceptionMessage` and `stackTrace` so users can implement their own truncation
3. **Document that `ElectStateFilter` should be used** for any modifications to the state before persistence (the current documentation implies `ApplyStateFilter` would work)

## Environment

- JobRunr Pro 8.3.1 (also reproducible with OSS version)
- PostgreSQL 16
- Jackson 2.17.0
- Kotlin 1.9.22
- JDK 21
- TestContainers 2.0.3

## Files

- `src/main/kotlin/example/LargeExceptionJobService.kt` - Job service that throws large/small exceptions
- `src/main/kotlin/example/TruncateFailedStateFilter.kt` - The workaround filter implementation
- `src/test/kotlin/example/LargeErrorMessageIssueTest.kt` - Demonstrates the issue with large messages
- `src/test/kotlin/example/TruncateFailedStateFilterWorkaroundTest.kt` - Demonstrates the workaround
