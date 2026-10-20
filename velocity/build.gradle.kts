plugins {
    id("java")
    id("com.gradleup.shadow")
    kotlin("kapt")
}

dependencies {
    compileOnly(project(":shared"))
    compileOnly("com.velocitypowered:velocity-api:4.2.1-SNAPSHOT")
    kapt("com.velocitypowered:velocity-api:4.2.1-SNAPSHOT")

    implementation("org.bstats:bstats-velocity:3.2.1")
    implementation("com.electronwill.night-config:toml:3.9.0")
    testImplementation(kotlin("test"))
}

tasks {
    shadowJar {
        minimize()
    }

    processResources {
        filesMatching("**/velocity-plugin.json") {
            expand(rootProject.project.properties)
        }

        outputs.upToDateWhen { false }
    }
}
repositories {
    mavenCentral()
}