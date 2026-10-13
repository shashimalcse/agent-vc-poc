plugins {
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.serialization") version "2.4.21"
    application
}

group = "dev.a2wp"
version = "0.1.0"

repositories {
    mavenCentral()
}

// Exact pins. Ktor is aligned to one version for server and client.
val ktorVersion = "3.6.0"
val mcpVersion = "0.15.0"
val vciVersion = "0.14.1"
val vpVersion = "0.16.2"
val sdJwtVersion = "0.20.2"
val nimbusVersion = "10.10"
val serializationVersion = "1.11.0"
val coroutinesVersion = "1.11.0"
val logbackVersion = "1.5.38"

dependencies {
    implementation("io.modelcontextprotocol:kotlin-sdk-server:$mcpVersion")

    implementation("eu.europa.ec.eudi:eudi-lib-jvm-openid4vci-kt:$vciVersion")
    implementation("eu.europa.ec.eudi:eudi-lib-jvm-openid4vp-kt:$vpVersion")
    implementation("eu.europa.ec.eudi:eudi-lib-jvm-sdjwt-kt:$sdJwtVersion")
    implementation("com.nimbusds:nimbus-jose-jwt:$nimbusVersion")

    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-sse:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-okhttp:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$serializationVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
    implementation("ch.qos.logback:logback-classic:$logbackVersion")

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation("io.modelcontextprotocol:kotlin-sdk-client:$mcpVersion")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:$coroutinesVersion")
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("dev.a2wp.wallet.MainKt")
    applicationName = "a2wp-kt"
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.named<JavaExec>("run") {
    workingDir = projectDir
}

// Second launcher in the distribution: bin/a2wpctl (store admin: list, reset).
val ctlStartScripts = tasks.register<CreateStartScripts>("ctlStartScripts") {
    mainClass.set("dev.a2wp.wallet.CtlKt")
    applicationName = "a2wpctl"
    outputDir = layout.buildDirectory.dir("ctl-scripts").get().asFile
    classpath = files(tasks.jar) + configurations.runtimeClasspath.get()
}

distributions {
    main {
        contents {
            from(ctlStartScripts) { into("bin") }
        }
    }
}
