plugins {
    id("java")
    id("com.gradleup.shadow")
}

dependencies {
    compileOnly(project(":shared"))
    compileOnly("org.spongepowered:spongeapi:8.0.0")

    implementation("org.bstats:bstats-sponge:3.2.1")
}

tasks {
    shadowJar {
        minimize()
    }

    processResources {
        filesMatching("**/sponge_plugins.json") {
            expand(rootProject.project.properties)
        }

        outputs.upToDateWhen { false }
    }
}
