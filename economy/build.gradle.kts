plugins {
    java
}

group = "dev.stsv"
version = "3.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.237-stable")
    compileOnly("dev.stsv:stsv-core")

    testImplementation("io.papermc.paper:paper-api:26.3.build.237-stable")
    testImplementation("dev.stsv:stsv-core")
    testImplementation("org.xerial:sqlite-jdbc:3.47.1.0")
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    systemProperty("stsv.content", file("content").absolutePath)
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
    options.compilerArgs.add("-Xlint:deprecation")
}

// The economy content pack (items, recipes, menus, shop/job/request data) is installed into
// plugins/STSV/contents/ by the core on start.
tasks.processResources {
    from("content") { into("default-content") }
    filesMatching("plugin.yml") { expand("version" to project.version) }
}
