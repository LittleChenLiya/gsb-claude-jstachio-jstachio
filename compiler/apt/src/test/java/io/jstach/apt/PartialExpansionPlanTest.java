package io.jstach.apt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.StringReader;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

import io.jstach.apt.TemplateCompilerLike.TemplateLoader;
import io.jstach.apt.internal.ProcessingException;

/**
 * Tests the explicit partial expansion plan using a three template example
 * ({@code a}, {@code b} and {@code c}).
 *
 * <p>
 * Run just this test with the apt module:
 *
 * <pre>
 * mvn -pl compiler/apt -am test -Dtest=PartialExpansionPlanTest -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 */
public class PartialExpansionPlanTest {

	/**
	 * In memory loader that binds the logical name as the resolved path (like
	 * {@link CodeWriter} does for file templates) and counts how many times a
	 * template is opened.
	 */
	static class MapLoader implements TemplateLoader {

		final Map<String, String> templates = new LinkedHashMap<>();

		final Map<String, Integer> opens = new HashMap<>();

		final Map<String, Integer> contentReads = new HashMap<>();

		MapLoader template(String name, String template) {
			templates.put(name, template);
			return this;
		}

		@Override
		public NamedReader open(String name) throws IOException {
			opens.merge(name, 1, Integer::sum);
			String template = templates.get(name);
			if (template == null) {
				throw new IOException("Failed to find template resource: '" + name + "'");
			}
			StringReader reader = new StringReader(template) {
				boolean counted;

				@Override
				public int read(char[] cbuf, int off, int len) throws IOException {
					if (!counted) {
						counted = true;
						contentReads.merge(name, 1, Integer::sum);
					}
					return super.read(cbuf, off, len);
				}
			};
			return new NamedReader(reader, name, name);
		}

		int openCount(String name) {
			return opens.getOrDefault(name, 0);
		}

		int contentReadCount(String name) {
			return contentReads.getOrDefault(name, 0);
		}

	}

	/**
	 * a includes b, b includes a: the recursive chain has to be located before
	 * infinite expansion.
	 */
	@Test
	public void cycleChainIsReported() {
		MapLoader loader = new MapLoader() //
				.template("a", "A {{> b}}") //
				.template("b", "B {{> a}}");
		try {
			PartialExpansionPlan.check("a", loader);
			fail("expected recursive partial failure");
		}
		catch (ProcessingException e) {
			String message = e.getMessage();
			assertTrue(message, message.contains("a -> b -> a"));
			/*
			 * The error is located at the {{> a}} tag in template b.
			 */
			assertEquals("b", e.position().fileName());
			assertEquals(1, e.position().row());
		}
	}

	/**
	 * A template that includes itself is a chain of one.
	 */
	@Test
	public void selfCycleIsReported() {
		MapLoader loader = new MapLoader() //
				.template("a", "{{> a}}");
		try {
			PartialExpansionPlan.check("a", loader);
			fail("expected recursive partial failure");
		}
		catch (ProcessingException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("a -> a"));
		}
	}

	/**
	 * c is included from two places (a and b): legal sharing, no false cycle and
	 * the snapshot of c is read exactly once.
	 */
	@Test
	public void sharedPartialIsLegalAndReadOnce() throws Exception {
		MapLoader loader = new MapLoader() //
				.template("root", "{{> a}}\n{{> b}}") //
				.template("a", "A {{> c}}") //
				.template("b", "B {{> c}}") //
				.template("c", "C");
		PartialExpansionPlan plan = PartialExpansionPlan.check("root", loader);
		assertEquals(1, loader.openCount("c"));
		assertEquals(1, loader.contentReadCount("c"));
		assertEquals(1, loader.openCount("a"));
		assertEquals(1, loader.openCount("b"));
		assertEquals(4, plan.snapshots().size());
		/*
		 * Compilation reads from the snapshot and does not reopen the template.
		 */
		try (NamedReader reader = plan.snapshotLoader().open("c")) {
			assertEquals("c", reader.name());
			assertEquals('C', reader.read());
		}
		assertEquals(1, loader.openCount("c"));
	}

	/**
	 * Two different logical names bound by the loader to the same resolved path
	 * share one snapshot and are not a cycle.
	 */
	@Test
	public void aliasesShareOneSnapshot() throws Exception {
		MapLoader aliasing = new MapLoader() //
				.template("root", "{{> a1}}\n{{> a2}}") //
				.template("a", "{{> c}}") //
				.template("c", "C");
		TemplateLoader loader = name -> {
			String logical = switch (name) {
				case "a1", "a2" -> "a";
				default -> name;
			};
			NamedReader reader = aliasing.open(logical);
			return new NamedReader(reader, name, reader.path());
		};
		PartialExpansionPlan plan = PartialExpansionPlan.check("root", loader);
		/*
		 * Each logical name is opened once to learn its binding but the snapshot
		 * content of the aliased template is read exactly once.
		 */
		assertEquals(1, aliasing.contentReadCount("a"));
		assertEquals(3, plan.snapshots().size());
	}

	/**
	 * An unknown partial fails with the source position of the referencing tag.
	 */
	@Test
	public void unknownPartialHasSourcePosition() {
		MapLoader loader = new MapLoader() //
				.template("root", "first\n{{> missing}}\nthird");
		try {
			PartialExpansionPlan.check("root", loader);
			fail("expected unknown partial failure");
		}
		catch (ProcessingException e) {
			String message = e.getMessage();
			assertTrue(message, message.contains("missing"));
			assertTrue(message, message.contains("Unknown partial"));
			assertEquals("root", e.position().fileName());
			assertEquals(2, e.position().row());
		}
	}

	/**
	 * Parent (parameter) partials {@code {{< parent}}} are part of the plan so
	 * the parameter partial context is preserved and planned like normal
	 * partials.
	 */
	@Test
	public void parameterPartialContextIsPlanned() throws Exception {
		MapLoader loader = new MapLoader() //
				.template("root", "{{< layout}}{{$body}}x{{/body}}{{/layout}}") //
				.template("layout", "L {{> c}}") //
				.template("c", "C");
		PartialExpansionPlan plan = PartialExpansionPlan.check("root", loader);
		assertEquals(1, loader.openCount("layout"));
		assertEquals(1, loader.openCount("c"));
		assertEquals(3, plan.snapshots().size());
	}

	/**
	 * Recursive chains through parent (parameter) partials are also located.
	 */
	@Test
	public void cycleThroughParentPartialIsReported() {
		MapLoader loader = new MapLoader() //
				.template("root", "{{< layout}}{{/layout}}") //
				.template("layout", "{{> root}}");
		try {
			PartialExpansionPlan.check("root", loader);
			fail("expected recursive partial failure");
		}
		catch (ProcessingException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("root -> layout -> root"));
		}
	}

	/**
	 * Dynamic partials (the lambda provided {@code @section}) cannot be resolved
	 * by the static loader and are marked pending instead of failing.
	 */
	@Test
	public void dynamicPartialIsPending() throws Exception {
		MapLoader loader = new MapLoader() //
				.template("root", "{{#lambda}}{{> @section}}{{/lambda}}");
		PartialExpansionPlan plan = PartialExpansionPlan.check("root", loader);
		assertTrue(plan.pending().contains("@section"));
		assertEquals(0, loader.openCount("@section"));
	}

}
