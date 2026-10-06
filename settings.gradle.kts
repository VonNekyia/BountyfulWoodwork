pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        gradlePluginPortal()
    }
}

rootProject.name = "BountyfulWoodwork"

// ColorfulLeaves: the client mod that shows the leaf colours.
// TreeArchive: the creative server's plugin for tree blueprints.
// Woodwork: the main server's plugin for felling trees, shipped as BountyfulWoodwork.
include("ColorfulLeaves", "TreeArchive", "Woodwork")
