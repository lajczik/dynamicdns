plugins {
    id("java")
    id("com.gradleup.shadow")
}

dependencies {
    compileOnly(project(":shared"))
    compileOnly("com.velocitypowered:velocity-api:3.5.0-SNAPSHOT")
    annotationProcessor("com.velocitypowered:velocity-api:3.5.0-SNAPSHOT")

    implementation("org.bstats:bstats-velocity:3.2.1")
    implementation("com.electronwill.night-config:toml:3.9.0")
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