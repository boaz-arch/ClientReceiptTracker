plugins {
    kotlin("jvm") version "2.4.0"
    application
}

group = "com.company.supportvisit"
version = "1.0.0"

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("com.company.supportvisit.server.MainKt")
}

dependencies {
    implementation("io.ktor:ktor-server-netty-jvm:3.6.1")
    implementation("io.ktor:ktor-server-core-jvm:3.6.1")
    implementation("io.ktor:ktor-server-call-logging-jvm:3.6.1")
    implementation("io.ktor:ktor-server-status-pages-jvm:3.6.1")

    // Verifies Microsoft Entra ID access tokens.
    implementation("com.auth0:java-jwt:4.6.2")
    implementation("com.auth0:jwks-rsa:0.24.2")

    implementation("org.xerial:sqlite-jdbc:3.54.1.0")
    implementation("org.json:json:20240303")
    implementation("ch.qos.logback:logback-classic:1.5.18")

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host-jvm:3.6.1")
}

tasks.test {
    useJUnitPlatform()
}
