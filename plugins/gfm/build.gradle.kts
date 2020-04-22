publishing {
    publications {
        register<MavenPublication>("gfmPlugin") {
            artifactId = "gfm-plugin"
            from(components["java"])
        }
    }
}

dependencies {
    compileOnly(project(":plugins:base"))
}