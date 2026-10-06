plugins {
    alias(libs.plugins.fabric.loom)
}

dependencies {
    minecraft(libs.minecraft)
    implementation(libs.fabric.loader)
    implementation(libs.fabric.api)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

tasks.test {
    useJUnitPlatform()
}

// Pictures of the mod at work: ./gradlew runClientGameTest starts the game, plants
// trees from the creative server's archive and takes screenshots of them coloured.
fabricApi {
    configureTests {
        createSourceSet = true
        modId = "colorfulleaves-pictures"
        enableGameTests = false
        enableClientGameTests = true
        eula = true
    }
}

val archive = file("../../server-terranova/servers/build/plugins/TreeArchive/archive").absolutePath
val pictures = providers.gradleProperty("pictures").orElse("")
tasks.matching { it.name == "runClientGameTest" }.configureEach {
    (this as JavaExec).systemProperty("colorfulleaves.archive", archive)
    systemProperty("colorfulleaves.pictures", pictures.get())
}

tasks.processResources {
    val version = project.version
    inputs.property("version", version)
    filesMatching("fabric.mod.json") {
        expand("version" to version)
    }
}
