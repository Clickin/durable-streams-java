
plugins {
    id("com.github.node-gradle.node") version "7.1.0"
}

node {
    version.set("20.11.1")
    download.set(true)
}

allprojects {
    group = "io.durablestreams"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

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
}

apply(plugin = "jacoco")

configure<JacocoPluginExtension> {
    toolVersion = "0.8.14"
}

tasks.register<JacocoReport>("jacocoRootReport") {
    description = "Generates an aggregate Jacoco report for all subprojects"
    group = "Reporting"

    val excludeProjects = setOf(
        "durable-streams-benchmarks",
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

