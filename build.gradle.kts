
plugins {
    id("com.github.node-gradle.node") version "7.1.0"
    id("maven-publish")
    id("signing")
}

node {
    version.set("20.11.1")
    download.set(true)
}

fun detectRocksDbClassifier(): String {
    val osName = System.getProperty("os.name").lowercase()
    return when {
        osName.contains("win") -> "win64"
        osName.contains("mac") || osName.contains("darwin") -> "osx"
        else -> "linux64"
    }
}

val rocksdbVersion = libs.versions.rocksdb.get()
val rocksdbClassifier = providers.gradleProperty("rocksdbClassifier")
    .orElse(providers.environmentVariable("ROCKSDB_CLASSIFIER"))
    .orElse(provider { detectRocksDbClassifier() })
    .get()

allprojects {
    group = "io.github.clickin"
    version = "0.1.0"

    extra["rocksdbVersion"] = rocksdbVersion
    extra["rocksdbClassifier"] = rocksdbClassifier

    repositories {
        mavenCentral()
    }
}

// Define which projects should be published to Maven Central
val publishableProjects = setOf(
    "durable-streams-core",
    "durable-streams-client",
    "durable-streams-json-spi",
    "durable-streams-json-jackson",
    "durable-streams-server-spi",
    "durable-streams-server-core",
    "durable-streams-servlet",
    "durable-streams-spring-webflux",
    "durable-streams-micronaut",
    "durable-streams-quarkus",
    "durable-streams-ktor"
)

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "jacoco")

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        finalizedBy(tasks.withType<JacocoReport>())
    }

    configure<JacocoPluginExtension> {
        toolVersion = "0.8.14"
    }

    tasks.withType<JacocoReport>().configureEach {
        reports {
            xml.required.set(true)
            csv.required.set(true)
            html.required.set(true)
        }
    }

    // Configure Maven publishing only for publishable projects
    if (publishableProjects.contains(project.name)) {
        apply(plugin = "maven-publish")
        apply(plugin = "signing")

        configure<JavaPluginExtension> {
            withJavadocJar()
            withSourcesJar()
        }

        // Suppress javadoc warnings for cleaner build output
        tasks.withType<Javadoc>().configureEach {
            options {
                (this as StandardJavadocDocletOptions).apply {
                    addStringOption("Xdoclint:none", "-quiet")
                }
            }
        }

        configure<PublishingExtension> {
            publications {
                create<MavenPublication>("mavenJava") {
                    from(components["java"])
                    
                    pom {
                        name.set(project.name)
                        description.set("Java implementation of the Durable Streams protocol")
                        url.set("https://github.com/durable-streams/durable-streams-java")
                        
                        licenses {
                            license {
                                name.set("MIT License")
                                url.set("https://opensource.org/licenses/MIT")
                            }
                        }
                        
                        developers {
                            developer {
                                id.set("clickin")
                                name.set("Clickin")
                                email.set("your-email@example.com")
                            }
                        }
                        
                        scm {
                            connection.set("scm:git:git://github.com/durable-streams/durable-streams-java.git")
                            developerConnection.set("scm:git:ssh://github.com/durable-streams/durable-streams-java.git")
                            url.set("https://github.com/durable-streams/durable-streams-java")
                        }
                    }
                }
            }
            
            repositories {
                maven {
                    name = "OSSRH"
                    val releasesRepoUrl = uri("https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/")
                    val snapshotsRepoUrl = uri("https://s01.oss.sonatype.org/content/repositories/snapshots/")
                    url = if (version.toString().endsWith("SNAPSHOT")) snapshotsRepoUrl else releasesRepoUrl
                    credentials {
                        username = findProperty("ossrhUsername")?.toString() ?: System.getenv("OSSRH_USERNAME")
                        password = findProperty("ossrhPassword")?.toString() ?: System.getenv("OSSRH_PASSWORD")
                    }
                }
                // Local repository for dry-run testing
                maven {
                    name = "Local"
                    url = uri(layout.buildDirectory.dir("repo"))
                }
            }
        }

        configure<SigningExtension> {
            val signingKey = findProperty("signingKey")?.toString() ?: System.getenv("SIGNING_KEY")
            val signingPassword = findProperty("signingPassword")?.toString() ?: System.getenv("SIGNING_PASSWORD")
            
            if (signingKey != null && signingPassword != null) {
                useInMemoryPgpKeys(signingKey, signingPassword)
                sign(the<PublishingExtension>().publications["mavenJava"])
            }
        }
    }
}

apply(plugin = "jacoco")

configure<JacocoPluginExtension> {
    toolVersion = "0.8.14"
}

tasks.register<JacocoReport>("jacocoRootReport") {
    description = "Generates an aggregate Jacoco report for all subprojects"
    group = "Reporting"

    val excludeProjects = setOf(
        "durable-streams-conformance-runner",
        "example-micronaut",
        "example-quarkus",
        "example-spring-webflux",
        "example-spring-webmvc"
    )

    val validSubprojects = subprojects.filter { !excludeProjects.contains(it.name) }

    dependsOn(validSubprojects.map { it.tasks.withType<Test>() })
    dependsOn(validSubprojects.map { it.tasks.withType<JacocoReport>() })

    sourceDirectories.setFrom(files(validSubprojects.map { it.extensions.getByType<JavaPluginExtension>().sourceSets["main"].allSource.srcDirs }))
    classDirectories.setFrom(files(validSubprojects.map { it.extensions.getByType<JavaPluginExtension>().sourceSets["main"].output }))
    executionData.setFrom(files(validSubprojects.map { it.tasks.withType<Test>().map { test -> test.extensions.getByType<JacocoTaskExtension>().destinationFile } }))

    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(true)
    }
    

    doLast {
        println("Root Jacoco report generated: ${reports.html.outputLocation.get()}")
        
        // Simple CLI summary from CSV
        val csvFile = reports.csv.outputLocation.get().asFile
        if (csvFile.exists()) {
            var totalInstructions = 0
            var coveredInstructions = 0
            var totalBranches = 0
            var coveredBranches = 0
            
            csvFile.readLines().drop(1).forEach { line ->
                val parts = line.split(",")
                // Jacoco CSV format: GROUP,PACKAGE,CLASS,INSTRUCTION_MISSED,INSTRUCTION_COVERED,BRANCH_MISSED,BRANCH_COVERED,...
                if (parts.size >= 9) {
                    val instructionMissed = parts[3].toInt()
                    val instructionCovered = parts[4].toInt()
                    val branchMissed = parts[5].toInt()
                    val branchCovered = parts[6].toInt()
                    
                    totalInstructions += instructionMissed + instructionCovered
                    coveredInstructions += instructionCovered
                    totalBranches += branchMissed + branchCovered
                    coveredBranches += branchCovered
                }
            }
            
            val instructionCoverage = if (totalInstructions > 0) (coveredInstructions.toDouble() / totalInstructions * 100) else 0.0
            val branchCoverage = if (totalBranches > 0) (coveredBranches.toDouble() / totalBranches * 100) else 0.0
            
            println("-".repeat(50))
            println("Total Instruction Coverage: ${String.format("%.2f", instructionCoverage)}% ($coveredInstructions/$totalInstructions)")
            println("Total Branch Coverage:      ${String.format("%.2f", branchCoverage)}% ($coveredBranches/$totalBranches)")
            println("-".repeat(50))
        }
    }
}

