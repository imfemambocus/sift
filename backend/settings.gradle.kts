plugins {
	// provisions the Java 25 toolchain on demand, so the build does not depend on
	// whichever JDK a given machine happens to have installed
	id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "sift"
