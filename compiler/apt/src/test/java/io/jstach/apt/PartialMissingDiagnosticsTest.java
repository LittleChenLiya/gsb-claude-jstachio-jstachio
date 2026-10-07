package io.jstach.apt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.StringWriter;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;
import java.util.regex.Pattern;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import io.jstach.jstachio.Renderer;

/**
 * End to end tests for {@code JStacheFlags.Flag#PARTIAL_MISSING_DIAGNOSTICS}. The
 * tests run the real annotation processor through an in memory {@link JavaCompiler}
 * with tiny temporary folder fixtures.
 */
public class PartialMissingDiagnosticsTest {

	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	private static final String MISSING_PARTIAL_TEMPLATE = """
			Hello {{message}}!
			{{> missing.mustache }}
			The End""";

	private static final String MISSING_PARTIAL_MODEL = """
			package test;

			import io.jstach.jstache.JStache;
			import io.jstach.jstache.JStacheFlags;

			@JStache(path = "root.mustache")
			@JStacheFlags(flags = JStacheFlags.Flag.PARTIAL_MISSING_DIAGNOSTICS)
			public class MissingPartialModel {

				public String message() {
					return "Hello";
				}

			}
			""";

	private static final String MISSING_PARTIAL_NO_FLAG_MODEL = """
			package test;

			import io.jstach.jstache.JStache;

			@JStache(path = "root.mustache")
			public class MissingPartialNoFlagModel {

				public String message() {
					return "Hello";
				}

			}
			""";

	private static final String PAGE_MODEL = """
			package test;

			import io.jstach.jstache.JStache;
			import io.jstach.jstache.JStacheFlags;
			import io.jstach.jstache.JStachePartial;
			import io.jstach.jstache.JStachePartials;

			@JStache(path = "page.mustache")
			@JStacheFlags(flags = JStacheFlags.Flag.PARTIAL_MISSING_DIAGNOSTICS)
			@JStachePartials(@JStachePartial(name = "greeting.mustache", template = "INLINE[{{message}}]"))
			public class PageModel {

				public String message() {
					return "world";
				}

			}
			""";

	private static final String PAGE_MODEL_NO_FLAG = """
			package test;

			import io.jstach.jstache.JStache;
			import io.jstach.jstache.JStachePartial;
			import io.jstach.jstache.JStachePartials;

			@JStache(path = "page.mustache")
			@JStachePartials(@JStachePartial(name = "greeting.mustache", template = "INLINE[{{message}}]"))
			public class PageModelNoFlag {

				public String message() {
					return "world";
				}

			}
			""";

	@Test
	public void missingPartialReportsTemplateAndPosition() throws Exception {
		Path classes = temp.newFolder("missing-classes").toPath();
		Path generated = temp.newFolder("missing-gensrc").toPath();
		Path emptyResources = temp.newFolder("missing-resources").toPath();
		/*
		 * The root template exists but the partial does not.
		 */
		Files.writeString(classes.resolve("root.mustache"), MISSING_PARTIAL_TEMPLATE, StandardCharsets.UTF_8);
		Path source = temp.newFile("MissingPartialModel.java").toPath();
		Files.writeString(source, MISSING_PARTIAL_MODEL, StandardCharsets.UTF_8);

		CompileResult result = compile(classes, generated, List.of(source),
				List.of("-Ajstache.resourcesPath=" + emptyResources.toAbsolutePath()));

		assertFalse("Compilation should fail on a missing partial.\n" + result.diagnostics(), result.success());
		String errors = result.errors();
		String tagLine = "{{> missing.mustache }}";
		/*
		 * The tag token position is the end of the tag which is the character just
		 * before the closing delimiter ("}}").
		 */
		String expectedPosition = "root.mustache[2:" + (tagLine.length() - 2) + "]";
		assertTrue("Error should mention the diagnostic.\n" + errors, errors.contains("Missing partial template"));
		assertTrue("Error should contain the missing partial name.\n" + errors,
				errors.contains("partial: 'missing.mustache'"));
		assertTrue("Error should contain the calling template.\n" + errors,
				errors.contains("template: 'root.mustache'"));
		assertTrue("Error should contain the tag position " + expectedPosition + ".\n" + errors,
				errors.contains(expectedPosition));
		assertTrue("Error should show the offending tag line.\n" + errors, errors.contains(tagLine));
		/*
		 * A missing partial must not leave a usable half compiled renderer behind.
		 */
		assertFalse("No renderer source should be generated",
				Files.exists(generated.resolve("test/MissingPartialModelRenderer.java")));
		assertFalse("No renderer class should be generated",
				Files.exists(classes.resolve("test/MissingPartialModelRenderer.class")));
	}

	@Test
	public void missingPartialWithoutFlagKeepsOriginalBehavior() throws Exception {
		Path classes = temp.newFolder("legacy-classes").toPath();
		Path generated = temp.newFolder("legacy-gensrc").toPath();
		Path emptyResources = temp.newFolder("legacy-resources").toPath();
		Files.writeString(classes.resolve("root.mustache"), MISSING_PARTIAL_TEMPLATE, StandardCharsets.UTF_8);
		Path source = temp.newFile("MissingPartialNoFlagModel.java").toPath();
		Files.writeString(source, MISSING_PARTIAL_NO_FLAG_MODEL, StandardCharsets.UTF_8);

		CompileResult result = compile(classes, generated, List.of(source),
				List.of("-Ajstache.resourcesPath=" + emptyResources.toAbsolutePath()));

		/*
		 * The original interface still fails the compilation (the missing partial is
		 * not replaced with empty text) but without the enriched diagnostic.
		 */
		assertFalse("Compilation should still fail on a missing partial.\n" + result.diagnostics(),
				result.success());
		String errors = result.errors();
		assertTrue("Error should still mention the missing template name.\n" + errors,
				errors.contains("missing.mustache"));
		assertFalse("Enriched diagnostic requires the flag.\n" + errors, errors.contains("Missing partial template"));
		assertFalse(Files.exists(generated.resolve("test/MissingPartialNoFlagModelRenderer.java")));
		assertFalse(Files.exists(classes.resolve("test/MissingPartialNoFlagModelRenderer.class")));
	}

	@Test
	public void inlinePartialOverridesFileAndFilePartialRenders() throws Exception {
		Path classes = temp.newFolder("page-classes").toPath();
		Path generated = temp.newFolder("page-gensrc").toPath();
		Files.writeString(classes.resolve("page.mustache"), "{{> greeting.mustache }}|{{> footer.mustache }}",
				StandardCharsets.UTF_8);
		/*
		 * A file based partial with the same name as the inline partial. The inline
		 * partial must win following the original lookup priority.
		 */
		Files.writeString(classes.resolve("greeting.mustache"), "FILE[{{message}}]", StandardCharsets.UTF_8);
		Files.writeString(classes.resolve("footer.mustache"), "FOOTER", StandardCharsets.UTF_8);
		Path source = temp.newFile("PageModel.java").toPath();
		Files.writeString(source, PAGE_MODEL, StandardCharsets.UTF_8);

		CompileResult result = compile(classes, generated, List.of(source), List.of());

		assertTrue("Compilation should succeed.\n" + result.diagnostics(), result.success());
		assertTrue(Files.exists(generated.resolve("test/PageModelRenderer.java")));

		String output = render(classes, "test.PageModelRenderer", "test.PageModel");
		assertEquals("INLINE[world]|FOOTER", output);
	}

	@Test
	public void inlinePartialOverridesFileAndFilePartialRendersWithoutFlag() throws Exception {
		Path classes = temp.newFolder("page-legacy-classes").toPath();
		Path generated = temp.newFolder("page-legacy-gensrc").toPath();
		Files.writeString(classes.resolve("page.mustache"), "{{> greeting.mustache }}|{{> footer.mustache }}",
				StandardCharsets.UTF_8);
		Files.writeString(classes.resolve("greeting.mustache"), "FILE[{{message}}]", StandardCharsets.UTF_8);
		Files.writeString(classes.resolve("footer.mustache"), "FOOTER", StandardCharsets.UTF_8);
		Path source = temp.newFile("PageModelNoFlag.java").toPath();
		Files.writeString(source, PAGE_MODEL_NO_FLAG, StandardCharsets.UTF_8);

		CompileResult result = compile(classes, generated, List.of(source), List.of());

		assertTrue("Compilation should succeed without the flag as before.\n" + result.diagnostics(),
				result.success());
		String output = render(classes, "test.PageModelNoFlagRenderer", "test.PageModelNoFlag");
		assertEquals("INLINE[world]|FOOTER", output);
	}

	record CompileResult(boolean success, String diagnostics, String errors) {
	}

	private CompileResult compile(Path classes, Path generatedSources, List<Path> sources, List<String> extraOptions)
			throws IOException {
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		DiagnosticCollector<JavaFileObject> diagnosticCollector = new DiagnosticCollector<>();
		List<String> options = new ArrayList<>(List.of( //
				"-classpath", testClassPath(), //
				"-d", classes.toString(), //
				"-s", generatedSources.toString(), //
				"-processor", GenerateRendererProcessor.class.getCanonicalName()));
		options.addAll(extraOptions);
		StringWriter compilerOut = new StringWriter();
		boolean success;
		try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnosticCollector, null,
				StandardCharsets.UTF_8)) {
			var units = fileManager.getJavaFileObjects(sources.toArray(Path[]::new));
			success = compiler.getTask(compilerOut, fileManager, diagnosticCollector, options, null, units).call();
		}
		StringBuilder diagnostics = new StringBuilder();
		StringBuilder errors = new StringBuilder();
		for (Diagnostic<? extends JavaFileObject> d : diagnosticCollector.getDiagnostics()) {
			diagnostics.append(d.getKind()).append(": ").append(d.getMessage(null)).append('\n');
			if (d.getKind() == Diagnostic.Kind.ERROR) {
				errors.append(d.getMessage(null)).append('\n');
			}
		}
		diagnostics.append(compilerOut);
		return new CompileResult(success, diagnostics.toString(), errors.toString());
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	private String render(Path classes, String rendererClassName, String modelClassName) throws Exception {
		URL[] urls = { classes.toUri().toURL() };
		try (URLClassLoader loader = new URLClassLoader(urls, getClass().getClassLoader())) {
			Class<?> modelClass = loader.loadClass(modelClassName);
			Object model = modelClass.getConstructor().newInstance();
			Class<?> rendererClass = loader.loadClass(rendererClassName);
			Renderer renderer = (Renderer) rendererClass.getConstructor().newInstance();
			return renderer.execute(model);
		}
	}

	/*
	 * Surefire by default runs tests with a manifest only booter jar so the real
	 * test class path has to be expanded from the jar manifest.
	 */
	static String testClassPath() throws IOException {
		String classPath = System.getProperty("java.class.path");
		List<String> entries = new ArrayList<>();
		for (String entry : classPath.split(Pattern.quote(java.io.File.pathSeparator))) {
			Path path = Path.of(entry);
			boolean expanded = false;
			if (entry.endsWith(".jar") && entry.contains("surefire") && Files.isRegularFile(path)) {
				try (JarFile jar = new JarFile(path.toFile())) {
					var manifest = jar.getManifest();
					String classPathAttr = manifest != null ? manifest.getMainAttributes().getValue("Class-Path")
							: null;
					if (classPathAttr != null) {
						for (String url : classPathAttr.split(" ")) {
							entries.add(Path.of(URI.create(url)).toString());
						}
						expanded = true;
					}
				}
			}
			if (!expanded) {
				entries.add(entry);
			}
		}
		return String.join(java.io.File.pathSeparator, entries);
	}

}
