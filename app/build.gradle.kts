plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidMultiplatformLibrary) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.googleServices) apply false
}

// Dependency locking: every resolved dependency (incl. transitive) is pinned in
// each module's gradle.lockfile, so builds are reproducible and Trivy (via
// dependency scanners such as Trivy) can check transitive dependencies for CVEs.
//
// After adding, removing or upgrading a dependency, regenerate and commit:
//     ./gradlew resolveAndLockAll --write-locks
// A build whose dependencies differ from the lockfiles fails until you do.
allprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
    // AGP's private tooling classpaths (e.g. the unified test platform that
    // drives emulators) run on the build machine, never ship in the app, and
    // change with every AGP upgrade — not project dependencies, so not locked.
    configurations.matching { it.name.startsWith("_internal") }.configureEach {
        resolutionStrategy.deactivateDependencyLocking()
    }

    tasks.register("resolveAndLockAll") {
        description = "Resolves every configuration; with --write-locks, rewrites gradle.lockfile."
        notCompatibleWithConfigurationCache("Resolves configurations at execution time")
        doFirst {
            require(gradle.startParameter.isWriteDependencyLocks) {
                "Run with --write-locks: ./gradlew resolveAndLockAll --write-locks"
            }
        }
        doLast {
            // Resolve the dependency graph only (what locking records). Selecting
            // files would need the artifact types AGP requests and fails for
            // Android classpaths. Anything still unresolvable stays unlocked,
            // which the default lock mode allows; it is listed below.
            val skipped = mutableListOf<String>()
            configurations.filter { it.isCanBeResolved }.forEach { conf ->
                try {
                    conf.incoming.resolutionResult.allComponents
                } catch (e: Exception) {
                    skipped += conf.name
                }
            }
            if (skipped.isNotEmpty()) {
                logger.lifecycle("${project.path}: not locked (not resolvable): ${skipped.joinToString()}")
            }
        }
    }
}
