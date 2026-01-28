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
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * This test demonstrates the issue where JobRunr fails to deserialize jobs
 * that have very large exception messages stored in FailedState.
 *
 * The test will:
 * 1. Enqueue a job that fails with a ~240KB error message
 * 2. Wait for the job to fail
 * 3. Attempt to read the job back from the database
 * 4. FAIL because the exceptionMessage field is too large for Jackson to deserialize
 *
 * Note: This test is expected to FAIL to demonstrate the issue.
 * Run with: ./gradlew test --tests "LargeErrorMessageIssueTest"
 */
@Testcontainers
class LargeErrorMessageIssueTest {

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("jobrunr_test")
            .withUsername("test")
            .withPassword("test")
    }

    private lateinit var storageProvider: PostgresStorageProvider
    private val jobService = LargeExceptionJobService()

    @BeforeEach
    fun setup() {
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

        // Configure JobRunr Pro (or OSS) without any filters
        val serverConfig = BackgroundJobServerConfiguration
            .usingStandardBackgroundJobServerConfiguration()
            .andPollIntervalInSeconds(5)

        JobRunrPro.configure()
            .useStorageProvider(storageProvider)
            .useBackgroundJobServer(serverConfig)
            .initialize()
    }

    @Test
    fun `demonstrates the issue - large error messages cause deserialization failure`() {
        // Enqueue a job that will fail with a large error message
        val jobId = org.jobrunr.scheduling.BackgroundJob.enqueue(jobService::throwLargeException)

        // Wait for the job to fail (with timeout)
        var attempts = 0
        while (attempts < 120) {
            Thread.sleep(1000)
            try {
                val job = storageProvider.getJobById(jobId)
                println("Job state after $attempts seconds: ${job.state}")
                if (job.state == StateName.FAILED) {
                    println("Job failed after $attempts seconds")
                    break
                }
            } catch (e: Exception) {
                println("Attempt $attempts: ${e.message}")
            }
            attempts++
        }

        // Now try to read the job from the database
        // This is where the issue manifests - Jackson will fail to deserialize
        // the large exceptionMessage string (>20MB exceeds Jackson's default limit)
        try {
            val failedJob = storageProvider.getJobById(jobId)
            println("Final job state: ${failedJob.state}")
            println("Number of job states: ${failedJob.jobStates.size}")

            // If we get here without exception, the test should fail
            // because we expect Jackson to throw StreamConstraintsException
            val failedState = failedJob.jobStates.last() as FailedState
            val exceptionMessage = getExceptionMessage(failedState)

            println("Exception message length: ${exceptionMessage.length}")
            fail("Expected StreamConstraintsException but job was read successfully. Message length: ${exceptionMessage.length}")

        } catch (e: Exception) {
            // This is the expected outcome - Jackson should fail to deserialize the 21MB message
            println("EXPECTED FAILURE: ${e.message}")

            // Check the full exception chain for StreamConstraints error
            var cause: Throwable? = e
            var foundStreamConstraints = false
            while (cause != null) {
                if (cause.message?.contains("StreamConstraints") == true ||
                    cause.message?.contains("exceeds the maximum") == true ||
                    cause.javaClass.simpleName.contains("StreamConstraints")) {
                    foundStreamConstraints = true
                    break
                }
                cause = cause.cause
            }

            assertTrue(
                foundStreamConstraints,
                "Expected a StreamConstraints exception for 21MB+ message, got: ${e.javaClass.name}: ${e.message}"
            )
            println("SUCCESS: Demonstrated that large error messages cause deserialization failure!")
        }
    }

    private fun getExceptionMessage(failedState: FailedState): String {
        val field = getField(FailedState::class.java, "exceptionMessage")
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
