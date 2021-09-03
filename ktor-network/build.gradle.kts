import org.jetbrains.kotlin.gradle.plugin.*
import org.jetbrains.kotlin.gradle.plugin.mpp.*

description = "Ktor network utilities"

val ideaActive: Boolean by project.extra
val nativeCompilations: List<KotlinNativeCompilation> by project.extra
val mockk_version: String by project.extra

kotlin {
    nativeCompilations.forEach {
        it.cinterops {
            val network by creating {
                defFile = projectDir.resolve("posix/interop/network.def")
            }
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(project(":ktor-utils"))
            }
        }

        val jvmTest by getting {
            dependencies {
                implementation("io.mockk:mockk:$mockk_version")
            }
        }
    }
}
