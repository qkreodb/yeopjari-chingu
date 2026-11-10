plugins {
    kotlin("jvm")
    application
}

application {
    mainClass.set("com.yeopjari.core.sim.TranscriptKt")
}

tasks.withType<JavaExec>().configureEach {
    defaultCharacterEncoding = "UTF-8"
}

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
