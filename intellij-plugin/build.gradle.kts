import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.2.21"
    id("org.jetbrains.intellij.platform") version "2.20.1"
}

group = "dev.cdb"
version = providers.gradleProperty("pluginVersion").get()

kotlin {
    jvmToolchain(21)
}

repositories {
    mavenLocal() // shared-protocol and browser-api from `mvn install` in the repository root
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

val cdbVersion = providers.gradleProperty("cdbVersion").get()

dependencies {
    implementation("dev.cdb:browser-api:$cdbVersion")
    implementation("dev.cdb:shared-protocol:$cdbVersion")

    intellijPlatform {
        intellijIdea(providers.gradleProperty("platformVersion"))
        testFramework(TestFrameworkType.Platform)
    }

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.opentest4j:opentest4j:1.3.0")
}

intellijPlatform {
    pluginConfiguration {
        id = "dev.cdb.chromium-dev-bridge"
        name = "Chromium Developer Bridge"
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = provider { null }
        }
    }
    pluginVerification {
        ides {
            recommended()
        }
    }
}

tasks {
    test {
        systemProperty("idea.force.use.core.classloader", "true")
    }
}
