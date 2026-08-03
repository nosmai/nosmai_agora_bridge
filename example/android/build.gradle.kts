allprojects {
    repositories {
        google()
        mavenCentral()
        // The pub.dev nosmai_camera_sdk declares the native AAR as
        // compileOnly(name: 'nosmai-release') and deliberately does NOT bundle
        // it, so every host app must supply it. Without this flatDir the build
        // fails with "Could not find :nosmai-release:".
        flatDir { dirs(rootProject.projectDir.resolve("app/libs")) }
    }
}

val newBuildDir: Directory =
    rootProject.layout.buildDirectory
        .dir("../../build")
        .get()
rootProject.layout.buildDirectory.value(newBuildDir)

subprojects {
    val newSubprojectBuildDir: Directory = newBuildDir.dir(project.name)
    project.layout.buildDirectory.value(newSubprojectBuildDir)
}
subprojects {
    project.evaluationDependsOn(":app")
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
