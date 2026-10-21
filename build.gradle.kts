plugins {
    id("org.jetbrains.kotlin.jvm") version "2.5.0-Beta1"
    id("com.gradleup.shadow") version "9.6.1"
}

group = "xyz.lychee.dynamicdns"
version = "2.1"

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

dependencies {
    implementation(project(":shared", "shadow"))
    implementation(project(":bukkit", "shadow"))
    implementation(project(":sponge", "shadow"))
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
        relocate("com.electronwill.nightconfig", "xyz.lychee.dynamicdns.libs.toml")
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
        maven("https://repo.spongepowered.org/repository/maven-public/")
        mavenLocal()
        mavenCentral()
    }

    dependencies {
        implementation("dev.dejvokep:boosted-yaml:1.3.7")
        implementation(kotlin("stdlib"))

        compileOnly("org.jetbrains:annotations:26.1.0")
        testImplementation("org.junit.jupiter:junit-jupiter:5.14.4")
        testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    }

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(25))
        }
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    tasks {
        withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
            compilerOptions {
                jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
            }
        }

        withType<Test>().configureEach {
            useJUnitPlatform()
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
