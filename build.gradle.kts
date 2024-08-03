
plugins {
    kotlin("multiplatform")
}

kotlin {

    jvm {
        testRuns["test"].executionTask.configure {
            useJUnitPlatform()
        }
    }

    sourceSets {
        val jvmMain by getting {

            dependencies {
                implementation(project(":server-ui-dsl:annotations"))

                implementation(libs.ksp.processor.api)
                implementation(libs.kotlinpoet.ksp)
                implementation(libs.kotlin.reflect)
            }

            kotlin.srcDir("src/main/kotlin")
            resources.srcDir("src/main/resources")
        }

        val jvmTest by getting {
            dependencies {
                implementation(libs.ksp.compile.testing)
                implementation(libs.junit)
                implementation(kotlin("test"))
                implementation(kotlin("test-common"))
                implementation(kotlin("test-annotations-common"))
            }
            kotlin.srcDir("src/test/kotlin")
        }

    }

    task("testClasses").doLast {
        println("testClasses defined due to bug https://slack-chats.kotlinlang.org/t/16429353/i-am-having-a-problem-building-a-kmm-project-in-android-stud")
    }
}

tasks.withType<Copy>() {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.withType<ProcessResources> {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}