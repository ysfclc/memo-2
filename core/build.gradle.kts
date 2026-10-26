plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("org.json:json:20240303")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test:2.4.21")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.4.21")
}
