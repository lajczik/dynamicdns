plugins {
    id("java")
    id("com.gradleup.shadow")
}

dependencies {
    implementation("org.bstats:bstats-base:3.2.1")
    implementation("com.github.alexdlaird:java8-ngrok:1.4.20") {
        exclude(module = "gson")
        exclude(module = "snakeyaml")
    }

    //compileOnly("io.netty:netty-all:4.2.12.Final")
}

tasks {
    shadowJar {
        minimize()
    }
}