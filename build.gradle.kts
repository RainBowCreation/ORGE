// versions
// https://parchmentmc.org/docs/getting-started
val parchmentVersion = "2023.09.03"
// https://fabricmc.net/develop/
val minecraftVersion = "1.20.1"
val loaderVersion = "0.16.14"
val fapiVersion = "0.92.5+1.20.1"

// in-house dependencies
val ponderVersion = "1.0.78"
val registrateVersion = "1.3.79-MC1.20.1"
val portLibVersion = "2.3.8+1.20.1"
val portLibModules = listOf(
    "base", "client_events"
)

// external dependencies
val configApiVersion = "8.0.0"
// https://maven.jamieswhiteshirt.com/libs-release/com/jamieswhiteshirt/reach-entity-attributes/
val reaVersion = "2.4.0"

// compat
// https://modrinth.com/mod/jei/versions
val jeiVersion = "15.20.0.106"
// https://modrinth.com/mod/rei/versions
val reiVersion = "12.1.785"
// https://modrinth.com/mod/emi/versions
val emiVersion = "1.1.22+1.20.1"
// https://modrinth.com/mod/modmenu/versions
val modmenuVersion = "7.2.2"
// https://modrinth.com/mod/sodium
val sodiumVersion = "mc1.20.1-0.5.13-fabric"
// https://modrinth.com/mod/indium
val indiumVersion = "1.0.36+mc1.20.1"
// https://modrinth.com/mod/journeymap
val jmVersion = "1.20.1-5.10.3-fabric"
val jmApiVersion = "1.20-1.9-SNAPSHOT"

// dev stuff
val recipeViewer = "emi" // jei, rei, or emi

plugins {
    id("fabric-loom") version "1.10.+"
}

val buildNum = providers.environmentVariable("GITHUB_RUN_NUMBER")
    .filter(String::isNotEmpty)
    .map { "-build.$it" }
    .orElse("-local")
    .getOrElse("")

version = "1.0.0.0+$minecraftVersion$buildNum"

group = "net.rainbowcreation.orge"
base.archivesName = "orge"

repositories {
    mavenCentral()
    maven("https://maven.parchmentmc.org") // Parchment
    maven("https://maven.fabricmc.net") // FAPI, Loader
    maven("https://maven.createmod.net") // Ponder
    maven("https://mvn.devos.one/snapshots") // Registrate, Forge
    maven("https://mvn.devos.one/releases") // Porting Lib
    maven("https://raw.githubusercontent.com/Fuzss/modresources/main/maven") // Forge Config API Port
    maven("https://maven.blamejared.com") // JEI
    maven("https://maven.shedaniel.me") // REI
    maven("https://api.modrinth.com/maven") { // Sodium
        content { includeGroupAndSubgroups("maven.modrinth") }
    }
    maven("https://maven.terraformersmc.com") // Mod Menu
    maven("https://maven.jamieswhiteshirt.com/libs-release") { // Reach Entity Attributes
        content { includeGroup("com.jamieswhiteshirt") }
    }
    maven("https://maven.ftb.dev/releases") // FTB
    maven("https://jm.gserv.me/repository/maven-public/") // Journey map
}

val ponder = file("Ponder")

dependencies {
    // setup
    minecraft("com.mojang:minecraft:$minecraftVersion")
    mappings(loom.layered {
        officialMojangMappings { nameSyntheticMembers = false }
        parchment("org.parchmentmc.data:parchment-$minecraftVersion:$parchmentVersion@zip")
    })
    modImplementation("net.fabricmc:fabric-loader:$loaderVersion")

    // dependencies
    modImplementation("net.fabricmc.fabric-api:fabric-api:$fapiVersion")
    for (module in portLibModules) {
        modApi(include("io.github.fabricators_of_create.Porting-Lib:$module:$portLibVersion")!!)
    }
    modApi(include("com.tterrag.registrate_fabric:Registrate:$registrateVersion") {
        exclude(mapOf("group" to "io.github.fabricators_of_create")) // avoid duplicate Porting Lib
    })
    modApi(include("fuzs.forgeconfigapiport:forgeconfigapiport-fabric:$configApiVersion")!!)
    modApi(include("com.jamieswhiteshirt:reach-entity-attributes:$reaVersion")!!)

    if (ponder.exists()) {
        implementation("net.createmod.ponder:Ponder-Fabric-$minecraftVersion:$ponderVersion") { isTransitive = false }
        implementation("net.createmod.ponder:Ponder-Common-$minecraftVersion:$ponderVersion")
    } else {
        modApi(include("net.createmod.ponder:Ponder-Fabric-$minecraftVersion:$ponderVersion")!!)
    }

    // compat
    modCompileOnly("com.terraformersmc:modmenu:$modmenuVersion")
    modCompileOnly("maven.modrinth:sodium:$sodiumVersion")

    modCompileOnly("dev.ftb.mods:ftb-chunks-fabric:2001.3.1")
    modCompileOnly("dev.ftb.mods:ftb-teams-fabric:2001.3.0")
    modCompileOnly("dev.ftb.mods:ftb-library-fabric:2001.2.4")

    modCompileOnly("maven.modrinth:journeymap:$jmVersion")
    modCompileOnly("info.journeymap:journeymap-api:$jmApiVersion")

    // EMI
    modCompileOnly("dev.emi:emi-fabric:$emiVersion:api") { isTransitive = false }
    // JEI
    modCompileOnly("mezz.jei:jei-$minecraftVersion-fabric:$jeiVersion") { isTransitive = false }
    // REI
    modCompileOnly("me.shedaniel:RoughlyEnoughItems-api-fabric:$reiVersion")
    modCompileOnly("me.shedaniel:RoughlyEnoughItems-default-plugin-fabric:$reiVersion")

    when (recipeViewer) {
        "jei" -> modLocalRuntime("mezz.jei:jei-$minecraftVersion-fabric:$jeiVersion")
        "rei" -> modLocalRuntime("me.shedaniel:RoughlyEnoughItems-fabric:$reiVersion")
        "emi" -> modLocalRuntime("dev.emi:emi-fabric:$emiVersion")
    }

    // have deprecated modules present at runtime only
    modLocalRuntime("net.fabricmc.fabric-api:fabric-api-deprecated:$fapiVersion")
}

sourceSets.named("main") {
    resources {
        srcDir("src/generated/resources")
        exclude(".cache/")
    }
}

loom {
    accessWidenerPath = file("src/main/resources/orge.accesswidener")

    runs {
        register("datagen") {
            client()
            name("Data Generation")
            vmArg("-Dfabric-api.datagen")
            vmArg("-Dfabric-api.datagen.output-dir=${file("src/generated/resources")}")
            vmArg("-Dfabric-api.datagen.modid=create")
            vmArg("-Dporting_lib.datagen.existing_resources=${file("src/main/resources")}")
        }

        named("server") {
            runDir("run/server")
        }

        configureEach {
            vmArg("-XX:+AllowEnhancedClassRedefinition")
            vmArg("-XX:+IgnoreUnrecognizedVMOptions")
            property("mixin.debug.export", "true")
        }
    }
}

configurations {
    // this avoids remapping ponder when it's local
    named("runtimeClasspath") {
        attributes {
            attribute(Attribute.of("orge.marker", String::class.java), "h")
        }
    }
}

tasks.named<ProcessResources>("processResources") {
    exclude("**/*.bbmodel", "**/*.lnk")

    val properties: MutableMap<String, Any> = mutableMapOf(
        "version" to version,
        "minecraft_version" to minecraftVersion,
        "loader_version" to loaderVersion,
        "fabric_version" to fapiVersion,
        "forge_config_version" to configApiVersion,
        "reach_entity_attributes_version" to reaVersion
    )

    for (module in portLibModules) {
        properties["port_lib_${module}_version"] = portLibVersion
    }
    properties["port_lib_tags_version"] = "3.0" // the weird one

    inputs.properties(properties)

    filesMatching("fabric.mod.json") {
        expand(properties)
    }
}

java {
    withSourcesJar()
}

tasks.named<JavaCompile>("compileJava") {
    options.compilerArgs.add("-Xmaxerrs")
    options.compilerArgs.add("10000")
}