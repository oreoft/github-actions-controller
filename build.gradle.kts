plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.serialization") version "2.3.21"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "com.oreoft"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    intellijPlatform {
        // 直接用本机安装的 IDEA，保证沙箱和日常用的版本完全一致
        local("/Applications/IntelliJ IDEA.app")
        bundledPlugin("Git4Idea")
        bundledPlugin("org.jetbrains.plugins.github")
    }
}

kotlin {
    jvmToolchain(17)
}

intellijPlatform {
    publishing {
        token.set(System.getenv("JB_PUBLISH_TOKEN"))
    }
}
