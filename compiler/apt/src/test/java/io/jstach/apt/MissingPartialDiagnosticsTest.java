package io.jstach.apt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

import org.junit.BeforeClass;
import org.junit.Test;

import io.jstach.jstachio.Renderer;

/**
 * End to end tests for the missing partial diagnostics
 * ({@code JStacheFlags.Flag#MISSING_PARTIAL_DIAGNOSTICS} /
 * {@code -Ajstache.missing_partial_diagnostics=true}).
 *
 * The real {@link GenerateRendererProcessor} is driven through
 * {@link javax.tools.JavaCompiler} with temporary folder fixtures so that the
 * compile error position, the inline partial lookup priority and the successful
 * rendering can be verified.
 */
public class MissingPartialDiagnosticsTest {

	private static final String FLAG_OPTION = "-Ajstache.missing_partial_diagnostics=true";

	private static final String MISSING_PARTIAL_MODEL = """
			package com.example;

			import io.jstach.jstache.JStache;
			import io.jstach.jstache.JStacheFlags;

			@JStache(template = "Hello {{>missing_partial}}!")
			@JStacheFlags(flags = JStacheFlags.Flag.MISSING_PARTIAL_DIAGNOSTICS)
			public record MissingPartialModel() {
			}
			""";

	private static final String INLINE_OVERRIDE_MODEL = """
			package com.example;

			import io.jstach.jstache.JStache;
			import io.jstach.jstache.JStacheFlags;
			import io.jstach.jstache.JStachePartial;
			import io.jstach.jstache.JStachePartials;

			@JStache(template = "{{>header}} world")
			@JStachePartials(@JStachePartial(name = "header", template = "INLINE"))
			@JStacheFlags(flags = JStacheFlags.Flag.MISSING_PARTIAL_DIAGNOSTICS)
			public record InlineOverrideModel() {
			}
			""";

	private static final String RESOURCE_MODEL = """
			package com.example;

			import io.jstach.jstache.JStache;
			import io.jstach.jstache.JStacheFlags;

			@JStache(template = "{{>header}} world")
			@JStacheFlags(flags = JStacheFlags.Flag.MISSING_PARTIAL_DIAGNOSTICS)
			public record ResourceModel() {
			}
			""";

	private static final String DEFAULT_MISSING_PARTIAL_MODEL = """
			package com.example;

			import io.jstach.jstache.JStache;

			@JStache(template = "Hello {{>missing_partial}}!")
			public record DefaultMissingPartialModel() {
			}
			""";

	private static final String DEFAULT_RESOURCE_MODEL = """
			package com.example;

			import io.jstach.jstache.JStache;

			@JStache(template = "{{>header}} world")
			public record DefaultResourceModel() {
			}
			""";

	private static Path workRoot;

	@BeforeClass
	public static void setup() throws IOException {
		workRoot = Path.of("target").toAbsolutePath().resolve(MissingPartialDiagnosticsTest.class.getSimpleName());
		Files.createDirectories(workRoot);
	}

	record CompileResult(Path directory, boolean success, String diagnostics) {

		Path classes() {
			return directory.resolve("classes");
		}

		Path generatedSources() {
			return directory.resolve("generated");
		}

		List<Path> generatedRenderers() throws IOException {
			try (Stream<Path> s = Files.walk(generatedSources())) {
				return s.filter(p -> p.getFileName().toString().endsWith("Renderer.java")).toList();
			}
		}

	}

	private static CompileResult compile(String testName, String className, String source,
			Map<String, String> resources, String... options) throws IOException {
		Path directory = Files.createTempDirectory(workRoot, testName);
		Path sourceDirectory = Files.createDirectories(directory.resolve("src").resolve("com").resolve("example"));
		Path classes = Files.createDirectories(directory.resolve("classes"));
		Path generated = Files.createDirectories(directory.resolve("generated"));
		Path sourceFile = sourceDirectory.resolve(className + ".java");
		Files.writeString(sourceFile, source, StandardCharsets.UTF_8);
		for (var e : resources.entrySet()) {
			Path resource = classes.resolve(e.getKey());
			if (resource.getParent() != null) {
				Files.createDirectories(resource.getParent());
			}
			Files.writeString(resource, e.getValue(), StandardCharsets.UTF_8);
		}

		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector<>();
		boolean success;
		try (StandardJavaFileManager fm = compiler.getStandardFileManager(collector, Locale.ENGLISH,
				StandardCharsets.UTF_8)) {
			fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(classes.toFile()));
			fm.setLocation(StandardLocation.SOURCE_OUTPUT, List.of(generated.toFile()));
			List<String> opts = new ArrayList<>();
			opts.add("-classpath");
			opts.add(System.getProperty("java.class.path"));
			opts.add("-processor");
			opts.add(GenerateRendererProcessor.class.getName());
			opts.addAll(List.of(options));
			Iterable<? extends JavaFileObject> units = fm.getJavaFileObjects(sourceFile);
			success = compiler.getTask(null, fm, collector, opts, null, units).call();
		}
		StringBuilder log = new StringBuilder();
		for (Diagnostic<? extends JavaFileObject> d : collector.getDiagnostics()) {
			log.append(d.getKind()).append(": ").append(d.getMessage(Locale.ENGLISH)).append("\n");
		}
		return new CompileResult(directory, success, log.toString());
	}

	private static String render(Path classes, String className) throws Exception {
		try (URLClassLoader cl = new URLClassLoader(new URL[] { classes.toUri().toURL() },
				MissingPartialDiagnosticsTest.class.getClassLoader())) {
			Class<?> modelClass = Class.forName("com.example." + className, true, cl);
			Object model = modelClass.getDeclaredConstructor().newInstance();
			Class<?> rendererClass = Class.forName("com.example." + className + "Renderer", true, cl);
			@SuppressWarnings("unchecked")
			Renderer<Object> renderer = (Renderer<Object>) rendererClass.getDeclaredConstructor().newInstance();
			return renderer.execute(model);
		}
	}

	/**
	 * Micro template one: {@code Hello {{>missing_partial}}!} with the flag on.
	 * The partial has no inline definition and no resource so the compilation
	 * must fail with a diagnostic that points at the calling template and the
	 * tag position. The missing partial is not replaced with empty text and no
	 * half compiled renderer is left behind.
	 */
	@Test
	public void missingPartialFailsWithTemplateAndPosition() throws Exception {
		CompileResult result = compile("missing-flag-on", "MissingPartialModel", MISSING_PARTIAL_MODEL, Map.of(),
				FLAG_OPTION);

		assertFalse("compilation should fail for a missing partial", result.success());
		String diagnostics = result.diagnostics();
		System.out.println("=== missing partial (flag on) diagnostics ===\n" + diagnostics);

		/* the calling template and the tag position are reported */
		assertTrue(diagnostics, diagnostics.contains("Partial template not found."));
		assertTrue(diagnostics, diagnostics.contains("partial: 'missing_partial'"));
		assertTrue(diagnostics, diagnostics.contains("template: com.example.MissingPartialModel#template"));
		assertTrue(diagnostics, diagnostics.contains("com.example.MissingPartialModel#template:1: error:"));
		assertTrue(diagnostics,
				diagnostics.contains("position: 'com.example.MissingPartialModel#template[1:27]'"));
		/*
		 * the offending line and a caret pointing at the tag (the position is
		 * where the tag closes which follows the existing error reporting)
		 */
		String templateLine = "Hello {{>missing_partial}}!";
		String caret = " ".repeat(templateLine.length() - 1) + "^";
		assertTrue(diagnostics, diagnostics.contains(templateLine));
		assertTrue(diagnostics, diagnostics.contains(caret));

		/* no half compiled renderer is left behind */
		assertEquals("no renderer should be generated: " + result.generatedRenderers(), List.of(),
				result.generatedRenderers());
	}

	/**
	 * Micro template two: {@code {{>header}} world} where an inline partial of
	 * the same name as a file resource must win per the original lookup
	 * priority.
	 */
	@Test
	public void inlinePartialOverridesResource() throws Exception {
		CompileResult result = compile("inline-override", "InlineOverrideModel", INLINE_OVERRIDE_MODEL,
				Map.of("header", "FILE"), FLAG_OPTION);

		assertTrue("compilation should succeed: " + result.diagnostics(), result.success());
		String actual = render(result.classes(), "InlineOverrideModel");
		System.out.println("=== inline partial overrides resource ===\n" + actual);
		assertEquals("INLINE world", actual);
	}

	/**
	 * Micro template two again: without an inline partial the file resource is
	 * used and renders normally.
	 */
	@Test
	public void resourcePartialRenders() throws Exception {
		CompileResult result = compile("resource-flag-on", "ResourceModel", RESOURCE_MODEL, Map.of("header", "FILE"),
				FLAG_OPTION);

		assertTrue("compilation should succeed: " + result.diagnostics(), result.success());
		String actual = render(result.classes(), "ResourceModel");
		System.out.println("=== resource partial (flag on) ===\n" + actual);
		assertEquals("FILE world", actual);
	}

	/**
	 * Regression: without the flag the original interface and error handling
	 * are used. A missing partial still fails the compilation (it is not
	 * replaced with empty text) but with the original generic message.
	 */
	@Test
	public void missingPartialDefaultBehaviorUnchanged() throws Exception {
		CompileResult result = compile("missing-flag-off", "DefaultMissingPartialModel", DEFAULT_MISSING_PARTIAL_MODEL,
				Map.of());

		assertFalse("compilation should fail for a missing partial", result.success());
		String diagnostics = result.diagnostics();
		System.out.println("=== missing partial (flag off) diagnostics ===\n" + diagnostics);

		assertTrue(diagnostics, diagnostics.contains("error:"));
		assertTrue(diagnostics, diagnostics.contains("java.io.IOException"));
		assertFalse("the enriched diagnostic requires the flag", diagnostics.contains("Partial template not found."));
		assertEquals("no renderer should be generated: " + result.generatedRenderers(), List.of(),
				result.generatedRenderers());
	}

	/**
	 * Regression: without the flag a normal resource partial still compiles and
	 * renders through the original interface.
	 */
	@Test
	public void resourcePartialDefaultBehaviorUnchanged() throws Exception {
		CompileResult result = compile("resource-flag-off", "DefaultResourceModel", DEFAULT_RESOURCE_MODEL,
				Map.of("header", "FILE"));

		assertTrue("compilation should succeed: " + result.diagnostics(), result.success());
		String actual = render(result.classes(), "DefaultResourceModel");
		System.out.println("=== resource partial (flag off) ===\n" + actual);
		assertEquals("FILE world", actual);
	}

}
