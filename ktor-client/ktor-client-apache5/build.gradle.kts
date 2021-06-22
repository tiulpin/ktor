/*
 * Copyright 2014-2021 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

kotlin.sourceSets {
    val jvmMain by getting {
        dependencies {
            implementation(project(":ktor-client:ktor-client-core"))
            implementation("org.apache.httpcomponents.client5:httpclient5:5.1")
        }
    }

    val jvmTest by getting {
        dependencies {
            implementation(project(":ktor-client:ktor-client-tests"))
        }
    }
}
