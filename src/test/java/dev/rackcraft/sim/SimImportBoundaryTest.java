package dev.rackcraft.sim;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class SimImportBoundaryTest {
	@Test
	void simulationSourcesDoNotImportMinecraft() throws IOException {
		Path sourceRoot = Path.of("src/main/java/dev/rackcraft/sim");
		try (var paths = Files.walk(sourceRoot)) {
			assertFalse(paths.filter(path -> path.toString().endsWith(".java"))
					.anyMatch(SimImportBoundaryTest::importsMinecraft));
		}
	}

	private static boolean importsMinecraft(Path path) {
		try {
			return Files.readString(path).contains("net.minecraft");
		} catch (IOException exception) {
			throw new IllegalStateException(exception);
		}
	}
}