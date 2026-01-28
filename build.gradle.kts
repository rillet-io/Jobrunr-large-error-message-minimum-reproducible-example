plugins {
    kotlin("jvm") version "1.9.22"
}

group = "example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    // JobRunr Pro repository - requires authentication
    maven {
        url = uri("https://repo.jobrunr.io/private-releases/")
        credentials {
            username = System.getenv("JOBRUNR_REPO_USER")
                ?: throw GradleException("JOBRUNR_REPO_USER environment variable must be set")
            password = System.getenv("JOBRUNR_REPO_PASS")
                ?: throw GradleException("JOBRUNR_REPO_PASS environment variable must be set")
        }
    }
}

dependencies {
    // JobRunr Pro - change to "org.jobrunr:jobrunr:7.0.0" for OSS version
    implementation("org.jobrunr:jobrunr-pro:8.3.1")

    // Jackson for JSON serialization
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.0")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.0")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.0")

    // PostgreSQL driver
    implementation("org.postgresql:postgresql:42.7.3")

    // SLF4J logging
    implementation("org.slf4j:slf4j-api:2.0.12")
    implementation("ch.qos.logback:logback-classic:1.5.3")

    // Test dependencies
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(platform("org.testcontainers:testcontainers-bom:2.0.3"))
    testImplementation("org.testcontainers:testcontainers:2.0.3")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
}

tasks.test {
    useJUnitPlatform()

    // Pass JobRunr Pro license to tests (required for JobRunr Pro)
    System.getenv("JOBRUNR_PRO_LICENSE")?.let { environment("JOBRUNR_PRO_LICENSE", it) }
}

kotlin {
    jvmToolchain(21)
}
