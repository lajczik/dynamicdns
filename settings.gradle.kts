pluginManagement {
    plugins {
        kotlin("kapt") version "2.5.0-Beta1"
    }
}
rootProject.name = "dynamicdns"

include("shared")
include("bukkit")
include("sponge")
include("bungee")
include("velocity")