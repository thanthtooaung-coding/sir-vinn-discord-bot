plugins {
    id("java")
    application
    id("com.gradleup.shadow") version "8.3.6"
}

group = "org.sirvinn"
version = "1.0-SNAPSHOT"


val jdaVersion = "5.6.1";

repositories {
    mavenCentral()
}

application {
    mainClass.set("org.sirvinn.Bot")
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    implementation("net.dv8tion:JDA:${jdaVersion}")
    implementation("io.github.cdimascio:java-dotenv:5.2.2")
}

tasks.test {
    useJUnitPlatform()
}