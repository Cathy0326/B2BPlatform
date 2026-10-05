import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.apollo)
}

/**
 * Turns contracts/money-rules.json (the cases the backend's JUnit and the frontend's Vitest suites also check)
 * into Kotlin source, so the same cases run on every target, including iOS where tests cannot read files.
 */
val generateMoneyRuleCases = tasks.register("generateMoneyRuleCases") {
    val rules = rootProject.layout.projectDirectory.file("../contracts/money-rules.json")
    val outDir = layout.buildDirectory.dir("generated/contract/kotlin")
    inputs.file(rules)
    outputs.dir(outDir)
    doLast {
        @Suppress("UNCHECKED_CAST")
        val json = groovy.json.JsonSlurper().parse(rules.asFile) as Map<String, Map<String, Any>>
        val fees = json.getValue("platformFee")["cases"] as List<Map<String, Number>>
        val quote = json.getValue("rentalQuote")
        val rates = quote["rates"] as Map<String, Map<String, Number>>
        val cases = quote["cases"] as List<Map<String, Any>>
        val text = buildString {
            appendLine("// Generated from contracts/money-rules.json by :shared:generateMoneyRuleCases. Do not edit.")
            appendLine("package com.quipmarket.shared")
            appendLine()
            appendLine("internal data class FeeCase(val hammerCents: Long, val feeCents: Long)")
            appendLine("internal data class QuoteCase(val ratesName: String, val rates: RentalRates, val rentalDays: Int, val totalCents: Long, val months: Int, val weeks: Int, val days: Int)")
            appendLine()
            appendLine("internal val FEE_CASES = listOf(")
            fees.forEach { appendLine("    FeeCase(${it["hammerCents"]}L, ${it["feeCents"]}L),") }
            appendLine(")")
            appendLine()
            appendLine("internal val QUOTE_CASES = listOf(")
            cases.forEach { c ->
                val name = c["rates"] as String
                val r = rates.getValue(name)
                appendLine(
                    "    QuoteCase(\"$name\", RentalRates(${r["dailyCents"]}L, ${r["weeklyCents"]}L, ${r["monthlyCents"]}L), " +
                        "${c["rentalDays"]}, ${c["totalCents"]}L, ${c["months"]}, ${c["weeks"]}, ${c["days"]}),",
                )
            }
            appendLine(")")
        }
        val out = outDir.get().file("com/quipmarket/shared/MoneyRuleCases.kt").asFile
        out.parentFile.mkdirs()
        out.writeText(text)
    }
}

// Shared by Android and iOS: the money rules, rental pricing and a typed GraphQL client for the backend.
kotlin {
    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    // Plain JVM target: runs the shared tests quickly without an Android SDK or a Mac.
    jvm()

    val xcf = XCFramework("Shared")
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "Shared"
            isStatic = true
            xcf.add(this)
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.apollo.runtime)
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        // Test cases generated from contracts/money-rules.json (see generateMoneyRuleCases below).
        commonTest {
            kotlin.srcDir(generateMoneyRuleCases)
        }
    }
}

android {
    namespace = "com.quipmarket.shared"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.androidMinSdk.get().toInt()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Typed client generated from the backend's own schema file: if the backend renames or removes a field the
// app queries, this module stops compiling, in the same pull request.
apollo {
    service("quipmarket") {
        packageName.set("com.quipmarket.shared.graphql")
        schemaFiles.from(rootProject.file("../backend/src/main/resources/graphql/schema.graphqls"))
        srcDir("src/commonMain/graphql")
        mapScalarToKotlinLong("Long")
        mapScalarToKotlinString("Date")
        mapScalarToKotlinString("DateTime")
    }
}
