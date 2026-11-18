// The AdFoc.us Gradle plugin (id us.adfoc.shorten): a task that makes an AdFoc.us short link for your built jar's
// download address after you publish it. Built and tested with Gradle TestKit; published with
// ./gradlew publishPlugins. Source: https://github.com/adfocusllc/shorten-gradle
plugins {
    `kotlin-dsl`
    id("com.gradle.plugin-publish") version "1.3.1"
}

group = "us.adfoc"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

// java.net.http needs Java 11; Minecraft builds run on 17 or 21
java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

gradlePlugin {
    // the public repository holds the source, and its README is the plugin's documentation
    website = "https://github.com/adfocusllc/shorten-gradle"
    vcsUrl = "https://github.com/adfocusllc/shorten-gradle"
    plugins {
        create("shorten") {
            id = "us.adfoc.shorten"
            implementationClass = "us.adfoc.gradle.AdfocusPlugin"
            displayName = "AdFoc.us short links"
            description = "Makes an AdFoc.us short link for your built jar's download address after you publish it to Modrinth, CurseForge or GitHub."
            tags = listOf("adfocus", "links", "release", "minecraft", "modding")
        }
    }
}

tasks.test {
    // TestKit starts its own Gradle for each test build: keep its files inside build/
    systemProperty("adfocus.testkit.dir", layout.buildDirectory.dir("testkit").get().asFile.absolutePath)
    maxParallelForks = 1
}
