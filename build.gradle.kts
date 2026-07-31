plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.serialization") version "2.3.21"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "com.oreoft"
version = "0.1.0"

val useLocalIde = providers.gradleProperty("useLocalIde")
    .map(String::toBoolean)
    .getOrElse(true)

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    intellijPlatform {
        if (useLocalIde) {
            local("/Applications/IntelliJ IDEA.app")
        } else {
            intellijIdea("2026.1.4")
        }
        bundledPlugin("Git4Idea")
        bundledPlugin("org.jetbrains.plugins.github")
    }
}

kotlin {
    jvmToolchain(21)
}

intellijPlatform {
    publishing {
        token.set(System.getenv("JB_PUBLISH_TOKEN"))
    }
}
