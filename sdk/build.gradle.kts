plugins {
    id("org.jetbrains.kotlin.jvm")
}

// A pure JVM library: it suits both an Android game and a desktop tool.
kotlin {
    jvmToolchain(17)
    sourceSets["main"].kotlin.srcDir("src/main/kotlin")
    sourceSets["test"].kotlin.srcDir("src/test/kotlin")
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
