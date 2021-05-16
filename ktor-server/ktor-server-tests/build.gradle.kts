description = ""

kotlin.sourceSets {
    val commonMain by getting {
        dependencies {
            api(project(":ktor-server:ktor-server-core"))
            api(project(":ktor-server:ktor-server-test-host"))
            api(project(":ktor-test-dispatcher"))
        }
    }
    val jvmTest by getting {
        dependencies {
            api(project(":ktor-server:ktor-server-core"))
            api(project(":ktor-server:ktor-server-core", configuration = "testOutput"))
        }
    }
}
