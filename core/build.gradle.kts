plugins { `java-library` }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
dependencies {
    api("net.sf.biweekly:biweekly:0.6.8")
    api("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.12.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.12.2")
}
tasks.test {
    useJUnitPlatform()
    System.getenv("TMPDIR")?.let { systemProperty("java.io.tmpdir", it) }
}
