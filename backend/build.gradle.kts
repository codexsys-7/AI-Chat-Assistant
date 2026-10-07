plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

application {
    mainClass.set("dev.probe.backend.MainKt")
}

dependencies {
    implementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
}

kotlin {
    jvmToolchain(17)
}

tasks.withType<Test>().configureEach {
    useJUnit()
}
