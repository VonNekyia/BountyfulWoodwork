plugins {
    id("java-library")
    alias(libs.plugins.paperweight.userdev)
    alias(libs.plugins.shadow)
    alias(libs.plugins.run.paper)
}

repositories {
    mavenCentral()
    maven("https://maven.enginehub.org/repo/")
}

// The build server's plugin folder, where every build is deployed - it is the creative
// server this plugin is for. Override with -PbuildServerPluginFolder=<path>.
val buildServerPluginFolder: Provider<Directory> =
    providers.gradleProperty("buildServerPluginFolder")
        .map { layout.projectDirectory.dir(it) }
        .orElse(layout.projectDirectory.dir("../../server-terranova/servers/build/plugins"))

dependencies {
    paperweight.paperDevBundle(libs.versions.paper.api.get())

    // Provided by the WorldEdit plugin at runtime. Its transitive libraries
    // clash with the dev bundle's and none of them are needed to compile against it.
    compileOnly(libs.worldedit.core) { isTransitive = false }
    compileOnly(libs.worldedit.bukkit) { isTransitive = false }
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

tasks {
    build {
        dependsOn(shadowJar)
        finalizedBy("deployToBuildServer")
    }

    // The shadow jar is the one that ships, so it takes the plain name and the
    // thin jar steps aside.
    jar {
        archiveClassifier.set("plain")
    }

    shadowJar {
        archiveClassifier.set("")
    }

    register<Copy>("deployToBuildServer") {
        group = "distribution"
        description = "Copies the plugin jar into the build server's plugin folder."
        from(shadowJar)
        into(buildServerPluginFolder)

        // A live server holds files open in that folder, and Gradle refuses to
        // fingerprint a destination it cannot fully read.
        doNotTrackState("the destination is a running server's plugin folder")
    }

    runServer {
        minecraftVersion(libs.versions.minecraft.get())
        jvmArgs("-Xms2G", "-Xmx2G")
    }

    processResources {
        val props = mapOf("version" to version)
        filesMatching("plugin.yml") {
            expand(props)
        }
    }
}
