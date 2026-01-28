package example

import org.jobrunr.jobs.annotations.Job

/**
 * Simple job service that throws large exceptions for testing.
 * This class has a no-arg constructor so JobRunr can instantiate it.
 */
class LargeExceptionJobService {

    @Job(retries = 0)
    fun throwLargeException() {
        val largeMessage = buildString {
            append("java.sql.BatchUpdateException: Batch entry 0 INSERT INTO some_table ")
            append("(col1, col2, col3) VALUES ")

            // Generate ~21MB of SQL-like content to exceed Jackson's 20MB default limit
            // Each iteration adds ~330 characters, so 65000 iterations ≈ 21.5MB
            repeat(65_000) {
                append("(")
                append("'${"x".repeat(100)}'::uuid, ")
                append("'${"y".repeat(100)}'::uuid, ")
                append("'${"z".repeat(100)}'::uuid")
                append("), ")
            }
            append("... (more rows)")
        }
        throw RuntimeException(largeMessage)
    }

    @Job(retries = 0)
    fun throwSmallException() {
        throw RuntimeException("Small error message for testing")
    }
}
