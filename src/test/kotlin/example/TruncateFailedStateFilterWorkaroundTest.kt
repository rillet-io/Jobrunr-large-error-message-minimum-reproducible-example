package example

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.jobrunr.configuration.JobRunrPro
import org.jobrunr.jobs.mappers.JobMapper
import org.jobrunr.jobs.states.FailedState
import org.jobrunr.jobs.states.StateName
import org.jobrunr.server.BackgroundJobServerConfiguration
import org.jobrunr.storage.sql.postgres.PostgresStorageProvider
import org.jobrunr.utils.mapper.jackson.JacksonJsonMapper
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.lang.reflect.Field
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * This test demonstrates the WORKAROUND for the large error message issue.
 *
 * By using ElectStateFilter.onStateElection() (NOT ApplyStateFilter.onStateApplied()),
 * we can truncate the exception message BEFORE the job is saved to the database.
 *
 * The key insight is that:
 * - ElectStateFilter.onStateElection() runs BEFORE the job is saved
 * - ApplyStateFilter.onStateApplied() runs AFTER the job is saved (too late!)
 *
 * Run with: ./gradlew test --tests "TruncateFailedStateFilterWorkaroundTest"
 */
@Testcontainers
class TruncateFailedStateFilterWorkaroundTest {

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("jobrunr_test")
            .withUsername("test")
            .withPassword("test")
    }

    private lateinit var storageProvider: PostgresStorageProvider
    private val truncateFilter = TruncateFailedStateFilter()
    private val jobService = LargeExceptionJobService()

    @BeforeEach
    fun setup() {
        // Reset diagnostic flags
        TruncateFailedStateFilter.reset()

        // Create Jackson mapper
        val objectMapper = ObjectMapper()
            .registerKotlinModule()
            .registerModule(JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

        val jsonMapper = JacksonJsonMapper(objectMapper)

        // Create storage provider
        val dataSource = org.postgresql.ds.PGSimpleDataSource().apply {
            setURL(postgres.jdbcUrl)
            user = postgres.username
            password = postgres.password
        }

        storageProvider = PostgresStorageProvider(dataSource)
        storageProvider.setJobMapper(JobMapper(jsonMapper))

        // Configure JobRunr Pro WITH our truncation filter
        val serverConfig = BackgroundJobServerConfiguration
            .usingStandardBackgroundJobServerConfiguration()
            .andPollIntervalInSeconds(5)

        JobRunrPro.configure()
            .useStorageProvider(storageProvider)
            .withJobFilter(truncateFilter)  // <-- The workaround filter
            .useBackgroundJobServer(serverConfig)
            .initialize()
    }

    @Test
    fun `workaround - large error messages are truncated and can be deserialized`() {
        // Enqueue a job that will fail with a large error message
        val jobId = org.jobrunr.scheduling.BackgroundJob.enqueue(jobService::throwLargeException)

        // Wait for the job to fail
        var attempts = 0
        while (attempts < 60) {
            Thread.sleep(1000)
            try {
                val job = storageProvider.getJobById(jobId)
                if (job.state == StateName.FAILED) {
                    break
                }
            } catch (e: Exception) {
                // Job might not be saved yet
            }
            attempts++
        }

        // Verify our filter was called
        assertTrue(TruncateFailedStateFilter.truncationAttempted, "Filter should have attempted truncation")
        assertTrue(TruncateFailedStateFilter.truncationSucceeded, "Filter should have successfully truncated")

        // Original message was large
        assertTrue(
            TruncateFailedStateFilter.lastExceptionMessageLength!! > 100_000,
            "Original message should have been large"
        )

        // Now read the job from the database - this should work because message was truncated
        val failedJob = storageProvider.getJobById(jobId)

        assertEquals(StateName.FAILED, failedJob.state)

        // Get the FailedState and verify truncation
        val failedState = failedJob.jobStates.last() as FailedState
        val exceptionMessage = getExceptionMessage(failedState)

        // Message should be truncated to ~8000 chars
        assertTrue(
            exceptionMessage.length <= 8020, // 8000 + "... (truncated)" + buffer
            "Exception message should be truncated, but was ${exceptionMessage.length} chars"
        )

        // Should contain the truncation marker
        assertTrue(
            exceptionMessage.contains("truncated"),
            "Exception message should contain truncation marker"
        )

        // Should still contain the beginning of the original message
        assertTrue(
            exceptionMessage.contains("BatchUpdateException"),
            "Exception message should contain original error type"
        )

        // Verify stack trace is also truncated
        val stackTrace = getStackTrace(failedState)
        assertTrue(
            stackTrace.length <= 8020,
            "Stack trace should be truncated, but was ${stackTrace.length} chars"
        )

        println("SUCCESS: Large error message was truncated and deserialized correctly!")
        println("Original length: ${TruncateFailedStateFilter.lastExceptionMessageLength}")
        println("Truncated length: ${exceptionMessage.length}")
    }

    @Test
    fun `workaround - small error messages are not modified`() {
        // Reset diagnostic flags
        TruncateFailedStateFilter.reset()

        // Enqueue a job that will fail with a small error message
        val jobId = org.jobrunr.scheduling.BackgroundJob.enqueue(jobService::throwSmallException)

        // Wait for the job to fail
        var attempts = 0
        while (attempts < 60) {
            Thread.sleep(1000)
            try {
                val job = storageProvider.getJobById(jobId)
                if (job.state == StateName.FAILED) {
                    break
                }
            } catch (e: Exception) {
                // Job might not be saved yet
            }
            attempts++
        }

        // Read the job from the database
        val failedJob = storageProvider.getJobById(jobId)

        assertEquals(StateName.FAILED, failedJob.state)

        // Get the FailedState
        val failedState = failedJob.jobStates.last() as FailedState
        val exceptionMessage = getExceptionMessage(failedState)

        // Message should NOT be truncated (it's small)
        assertTrue(
            exceptionMessage.contains("Small error message"),
            "Small exception message should not be modified"
        )
        assertTrue(
            !exceptionMessage.contains("truncated"),
            "Small exception message should not have truncation marker"
        )

        println("SUCCESS: Small error message was not modified!")
    }

    private fun getExceptionMessage(failedState: FailedState): String {
        val field = getField(FailedState::class.java, "exceptionMessage")
        field.isAccessible = true
        return field.get(failedState) as String
    }

    private fun getStackTrace(failedState: FailedState): String {
        val field = getField(FailedState::class.java, "stackTrace")
        field.isAccessible = true
        return field.get(failedState) as String
    }

    private fun getField(clazz: Class<*>, fieldName: String): Field {
        return try {
            clazz.getDeclaredField(fieldName)
        } catch (e: NoSuchFieldException) {
            clazz.superclass?.let { getField(it, fieldName) } ?: throw e
        }
    }
}
