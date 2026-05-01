import org.gradle.kotlin.dsl.maven

plugins {
    id("java")
    id("com.gradleup.shadow") version "9.4.1"
}

group = "xyz.lychee.dynamicdns"
version = "1.2"

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":bukkit"))
    implementation(project(":bungee"))
    implementation(project(":velocity"))
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
    }

    shadowJar {
        archiveBaseName.set("DynamicDNS")
        archiveClassifier.set("")

        relocate("dev.dejvokep.boostedyaml", "xyz.lychee.dynamicdns.libs.yaml")
        relocate("org.bstats", "xyz.lychee.dynamicdns.libs.metrics")
        relocate("com.github.alexdlaird.ngrok", "xyz.lychee.dynamicdns.libs.ngrok")
    }
}

allprojects {
    group = "xyz.lychee";

    apply(plugin = "java")
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

        compileOnly("org.jetbrains:annotations:26.1.0")
        compileOnly("org.projectlombok:lombok:1.18.44")
        annotationProcessor("org.projectlombok:lombok:1.18.44")
    }

    tasks {
        compileJava {
            options.encoding = "UTF-8"
        }

        processResources {
            filesMatching("**/plugin.yml") {
                expand(rootProject.project.properties)
            }
            filesMatching("**/bungee.yml") {
                expand(rootProject.project.properties)
            }
            filesMatching("**/velocity-plugin.json") {
                expand(rootProject.project.properties)
            }

            outputs.upToDateWhen { false }
        }
    }
}