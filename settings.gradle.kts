rootProject.name = "secman"

include("shared", "cli", "backendng")

// Module paths
project(":shared").projectDir = file("src/shared")
project(":cli").projectDir = file("src/cli")
project(":backendng").projectDir = file("src/backendng")
