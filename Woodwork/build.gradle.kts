plugins {
    id("java-library")
    alias(libs.plugins.paperweight.userdev)
    alias(libs.plugins.shadow)
    alias(libs.plugins.run.paper)
}

repositories {
    mavenCentral()
}

// The main server's plugin folder, which is also where the compile-only plugin jars
// come from. Override with -PtestServerPluginFolder=<path> to use a different checkout.
val testServerPluginFolder: Provider<Directory> =
    providers.gradleProperty("testServerPluginFolder")
        .map { layout.projectDirectory.dir(it) }
        .orElse(layout.projectDirectory.dir("../server-terranova/servers/main/plugins"))

dependencies {
    paperweight.paperDevBundle(libs.versions.paper.api.get())

    // Nexo is optional at runtime (only for Nexo item drops). Its API comes from the
    // jar deployed alongside us in the test server.
    compileOnly(files(testServerPluginFolder.map { folder ->
        folder.asFileTree.matching { include("nexo-*.jar") }
    }))
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

tasks {
    build {
        dependsOn(shadowJar)
        finalizedBy("deployToMainServer")
    }

    // The shadow jar is the one that ships, so it takes the plain name and the
    // thin jar steps aside.
    jar {
        archiveClassifier.set("plain")
    }

    shadowJar {
        archiveClassifier.set("")
    }

    register<Copy>("deployToMainServer") {
        group = "distribution"
        description = "Copies the plugin jar into the main server's plugin folder."
        from(shadowJar)
        into(testServerPluginFolder)

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
