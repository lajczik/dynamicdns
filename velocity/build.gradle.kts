plugins {
    id("java")
    id("com.gradleup.shadow")
}

dependencies {
    compileOnly(project(":shared"))
    compileOnly(files("${rootProject.rootDir}/libs/velocity.jar"))
    annotationProcessor(files("${rootProject.rootDir}/libs/velocity.jar"))

    implementation("org.bstats:bstats-velocity:3.2.1")
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
        filesMatching("**/velocity-plugin.json") {
            expand(rootProject.project.properties)
        }

        outputs.upToDateWhen { false }
    }
}