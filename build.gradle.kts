plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.20-Beta2"
    id("com.gradleup.shadow") version "9.6.1"
}

group = "xyz.lychee.dynamicdns"
version = "1.3"

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

dependencies {
    implementation(project(":shared", "shadow"))
    implementation(project(":bukkit", "shadow"))
    implementation(project(":bungee", "shadow"))
    implementation(project(":velocity", "shadow"))
}

tasks {
    shadowJar {
        archiveBaseName.set("DynamicDNS")
        archiveClassifier.set("")

        relocate("dev.dejvokep.boostedyaml", "xyz.lychee.dynamicdns.libs.yaml")
        relocate("org.bstats", "xyz.lychee.dynamicdns.libs.metrics")
        relocate("com.github.alexdlaird", "xyz.lychee.dynamicdns.libs.ngrok")
        relocate("com.electronwill.nightconfig.core", "xyz.lychee.dynamicdns.libs.toml")
    }
}

allprojects {
    group = "xyz.lychee"

    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "com.gradleup.shadow")

    repositories {
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://oss.sonatype.org/content/groups/public/")
        maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
        mavenLocal()
        mavenCentral()
    }

    dependencies {
        implementation("dev.dejvokep:boosted-yaml:1.3.7")
        implementation(kotlin("stdlib"))

        compileOnly("org.jetbrains:annotations:26.1.0")
    }

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(25))
        }
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    tasks {
        compileKotlin {
            compilerOptions {
                jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
            }
        }
    }

    configurations {
        compileClasspath {
            attributes {
                attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 25)
            }
        }
    }
}