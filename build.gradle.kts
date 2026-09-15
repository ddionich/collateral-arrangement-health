plugins { kotlin("jvm") version "2.1.20" }

repositories { mavenCentral() }

dependencies {
    testImplementation(kotlin("test-junit5"))
}

kotlin { jvmToolchain(21) }

tasks.test {
    useJUnitPlatform()
    testLogging { showStandardStreams = true; events("passed", "failed") }
}
