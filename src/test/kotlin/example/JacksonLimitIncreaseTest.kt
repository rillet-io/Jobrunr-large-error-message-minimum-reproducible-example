package example

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.StreamReadConstraints
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
import kotlin.test.assertNotNull

/**
 * This test checks whether increasing Jackson's StreamReadConstraints maxStringLength
 * resolves the deserialization issue with large error messages in JobRunr.
 */
@Testcontainers
class JacksonLimitIncreaseTest {

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
        // Configure Jackson with increased string length limit
        val streamReadConstraints = StreamReadConstraints.builder()
            .maxStringLength(50_000_000) // 50MB limit instead of default 20MB
            .build()

        val jsonFactory = JsonFactory.builder()
            .streamReadConstraints(streamReadConstraints)
            .build()

        val objectMapper = ObjectMapper(jsonFactory)
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

        // Configure JobRunr Pro
        // IMPORTANT: useJsonMapper() must be called BEFORE useStorageProvider(),
        // because useStorageProvider() overwrites the storage provider's JobMapper
        // with the one from JobRunrConfiguration (which defaults to 20MB limit).
        val serverConfig = BackgroundJobServerConfiguration
            .usingStandardBackgroundJobServerConfiguration()
            .andPollIntervalInSeconds(5)

        JobRunrPro.configure()
            .useJsonMapper(jsonMapper)
            .useStorageProvider(storageProvider)
            .useBackgroundJobServer(serverConfig)
            .initialize()
    }

    @Test
    fun `test whether increasing Jackson string limit fixes deserialization`() {
        // Enqueue a job that will fail with a large error message
        val jobId = org.jobrunr.scheduling.BackgroundJob.enqueue(jobService::throwLargeException)

        // Wait for the job to fail
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

        // Try to read the job back - this should succeed if the limit increase works
        val failedJob = storageProvider.getJobById(jobId)
        assertEquals(StateName.FAILED, failedJob.state, "Job should be in FAILED state")

        val failedState = failedJob.jobStates.last() as FailedState
        val exceptionMessage = getExceptionMessage(failedState)
        assertNotNull(exceptionMessage, "Exception message should not be null")
        println("Successfully deserialized job with exception message length: ${exceptionMessage.length}")
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
