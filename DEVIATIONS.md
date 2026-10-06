# Deviations

## Gradle runtime requires Java 25

The official Fabric example-mod 1.20.1 branch pins Loom with `1.18-SNAPSHOT`. During this build it resolved to Fabric Loom 1.18.2, which rejects a Java 17 Gradle runtime and requires Java 25 or newer. To retain the specified Loom and Gradle wrapper versions while keeping the mod compatible with Java 17, run Gradle on Java 25 and compile with the configured Java 17 toolchain and `options.release = 17`.

Observed failure with Java 17: `Dependency requires at least JVM runtime version 25. This build uses a Java 17 JVM.`