plugins {
    id("java")
    id("com.gradleup.shadow")
}

dependencies {
    compileOnly(project(":shared"))
    compileOnly(files("${rootProject.rootDir}/libs/bungeecord.jar"))

    implementation("org.bstats:bstats-bungeecord:3.2.1")
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

tasks {
    shadowJar {
        minimize()
    }

    processResources {
        filesMatching("**/bungee.yml") {
            expand(rootProject.project.properties)
        }

        outputs.upToDateWhen { false }
    }
}