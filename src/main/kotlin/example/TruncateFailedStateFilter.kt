package example

import org.jobrunr.jobs.Job
import org.jobrunr.jobs.filters.ElectStateFilter
import org.jobrunr.jobs.states.FailedState
import org.jobrunr.jobs.states.JobState
import org.slf4j.LoggerFactory
import java.lang.reflect.Field

/**
 * Workaround filter that truncates large exception messages in FailedState objects
 * BEFORE the job is saved to the database.
 *
 * ## Why ElectStateFilter instead of ApplyStateFilter?
 *
 * - `ElectStateFilter.onStateElection()` is called BEFORE the job is saved to the database
 * - `ApplyStateFilter.onStateApplied()` is called AFTER the job is saved
 *
 * We need to truncate BEFORE save for the truncation to be persisted. Using ApplyStateFilter
 * would result in the truncation happening in memory but the full message being saved to the DB.
 *
 * ## Why reflection?
 *
 * JobRunr's FailedState class doesn't provide public setters for exceptionMessage or stackTrace.
 * We use reflection to modify these private fields.
 *
 * ## Why set exception to null?
 *
 * The FailedState also stores the original Exception object. If we don't null it out,
 * JobRunr may re-extract the full message from it during serialization.
 */
class TruncateFailedStateFilter(
    private val maxExceptionLength: Int = 8_000,
    private val maxStackTraceLength: Int = 8_000
) : ElectStateFilter {

    private val logger = LoggerFactory.getLogger(TruncateFailedStateFilter::class.java)

    companion object {
        // Diagnostic flags for testing
        @Volatile var truncationAttempted = false
        @Volatile var truncationSucceeded = false
        @Volatile var lastExceptionMessageLength: Int? = null

        fun reset() {
            truncationAttempted = false
            truncationSucceeded = false
            lastExceptionMessageLength = null
        }
    }

    override fun onStateElection(job: Job, newState: JobState) {
        if (newState is FailedState) {
            truncateFailedState(newState)
        }
    }

    private fun truncateFailedState(failedState: FailedState) {
        truncationAttempted = true
        try {
            val exceptionField = getField(FailedState::class.java, "exception")
            val exceptionMessageField = getField(FailedState::class.java, "exceptionMessage")
            val stackTraceField = getField(FailedState::class.java, "stackTrace")

            if (exceptionMessageField == null) {
                logger.error("Could not find exceptionMessage field in FailedState")
                return
            }

            val exceptionMessage = exceptionMessageField.get(failedState) as? String
            lastExceptionMessageLength = exceptionMessage?.length
            val stackTrace = stackTraceField?.get(failedState) as? String

            val needsTruncation = (exceptionMessage?.length ?: 0) > maxExceptionLength ||
                (stackTrace?.length ?: 0) > maxStackTraceLength

            if (needsTruncation) {
                logger.info(
                    "Truncating large FailedState: exceptionMessage={} chars, stackTrace={} chars",
                    exceptionMessage?.length, stackTrace?.length
                )

                // IMPORTANT: Set exception to null to prevent re-extraction during serialization
                exceptionField?.set(failedState, null)

                // Truncate exception message
                if (exceptionMessage != null && exceptionMessage.length > maxExceptionLength) {
                    val truncated = exceptionMessage.take(maxExceptionLength) + "\n... (truncated)"
                    exceptionMessageField.set(failedState, truncated)
                    truncationSucceeded = true
                }

                // Truncate stack trace
                if (stackTraceField != null && stackTrace != null && stackTrace.length > maxStackTraceLength) {
                    val truncated = stackTrace.take(maxStackTraceLength) + "\n... (truncated)"
                    stackTraceField.set(failedState, truncated)
                }
            }
        } catch (e: Exception) {
            logger.warn("Failed to truncate FailedState fields: {}", e.message, e)
        }
    }

    private fun getField(clazz: Class<*>, fieldName: String): Field? {
        return try {
            val field = clazz.getDeclaredField(fieldName)
            field.isAccessible = true
            field
        } catch (e: NoSuchFieldException) {
            clazz.superclass?.let { getField(it, fieldName) }
        }
    }
}
