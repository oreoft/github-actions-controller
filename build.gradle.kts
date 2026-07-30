plugins {
    kotlin("jvm") version "2.3.21"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // 直接用本机安装的 IDEA，保证沙箱和日常用的版本完全一致
        local("/Applications/IntelliJ IDEA.app")
        bundledPlugin("Git4Idea")
    }
}

kotlin {
    jvmToolchain(17)
}
