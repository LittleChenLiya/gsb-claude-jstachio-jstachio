package io.jstach.apt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

import io.jstach.apt.TemplateCompilerLike.TemplateLoader;
import io.jstach.apt.internal.NamedTemplate;
import io.jstach.apt.internal.Position;
import io.jstach.apt.internal.ProcessingException;
import io.jstach.apt.internal.context.TemplateStack;
import io.jstach.apt.internal.util.ClassRef;

/**
 * Tests the explicit partial expansion plan using a three template example:
 * {@code a.mustache} includes {@code b.mustache} and {@code c.mustache},
 * {@code b.mustache} includes {@code a.mustache} (a cycle) and {@code c.mustache}
 * (a legal diamond share).
 */
public class PartialExpansionPlanTest {

	static final String A = "a.mustache";

	static final String B = "b.mustache";

	static final String C = "c.mustache";

	static final Map<String, String> TEMPLATES = Map.of(//
			A, "A {{> " + B + " }} {{> " + C + " }}", //
			B, "B {{> " + A + " }} {{> " + C + " }}", //
			C, "C {{message}}");

	static class CountingLoader implements TemplateLoader {

		final Map<String, Integer> counts = new LinkedHashMap<>();

		@Override
		public NamedReader open(String name) throws IOException {
			counts.merge(name, 1, Integer::sum);
			String template = TEMPLATES.get(name);
			if (template == null) {
				throw new FileNotFoundException("No such template: " + name);
			}
			return new NamedReader(new StringReader(template), name, name);
		}

		int count(String name) {
			return counts.getOrDefault(name, 0);
		}

	}

	static TemplateStack stack(String... names) {
		NamedTemplate root = new NamedTemplate.FileTemplate(names[0], names[0], null, null);
		TemplateStack stack = TemplateStack.ofRoot(ClassRef.ofBinaryName("com.example.Model"), root, Set.of());
		for (int i = 1; i < names.length; i++) {
			stack = stack.ofPartial(names[i]);
		}
		return stack;
	}

	static String read(Reader reader) throws IOException {
		StringBuilder sb = new StringBuilder();
		char[] buffer = new char[1024];
		int read;
		while ((read = reader.read(buffer)) >= 0) {
			sb.append(buffer, 0, read);
		}
		return sb.toString();
	}

	@Test
	public void cycleIsReportedWithChain() {
		/* a includes b and b includes a. */
		TemplateStack stack = stack(A, B);
		var ex = assertThrows(ProcessingException.class,
				() -> PartialExpansionPlan.checkCycle(stack, A, Position.noPosition()));
		String message = ex.getMessage();
		assertTrue(message, message.contains(A + " -> " + B + " -> " + A));
	}

	@Test
	public void selfIncludeIsReported() {
		TemplateStack stack = stack(A);
		var ex = assertThrows(ProcessingException.class,
				() -> PartialExpansionPlan.checkCycle(stack, A, Position.noPosition()));
		assertTrue(ex.getMessage(), ex.getMessage().contains(A + " -> " + A));
	}

	@Test
	public void sharedPartialIsNotACycle() throws ProcessingException {
		/*
		 * c is referenced from two places (a and b) which is legal and must not be
		 * reported as a cycle.
		 */
		PartialExpansionPlan.checkCycle(stack(A), C, Position.noPosition());
		PartialExpansionPlan.checkCycle(stack(A, B), C, Position.noPosition());
	}

	@Test
	public void parameterPartialCycleIsReported() {
		/* A parent (parameter) partial may not include itself either. */
		TemplateStack stack = stack(A).ofParameterPartial(B);
		var ex = assertThrows(ProcessingException.class,
				() -> PartialExpansionPlan.checkCycle(stack, B, Position.noPosition()));
		assertTrue(ex.getMessage(), ex.getMessage().contains(B + " -> " + B));
	}

	@Test
	public void lambdaNamesAreNotTemplateNames() throws ProcessingException {
		/* A lambda section named like a partial is not an expansion cycle. */
		TemplateStack stack = stack(A).ofLambda(B);
		PartialExpansionPlan.checkCycle(stack, B, Position.noPosition());
	}

	@Test
	public void snapshotIsReadOnce() throws IOException {
		var loader = new CountingLoader();
		var plan = PartialExpansionPlan.of(loader);
		String first = read(plan.open(C));
		String second = read(plan.open(C));
		assertEquals(TEMPLATES.get(C), first);
		assertEquals(first, second);
		assertEquals(1, loader.count(C));
		assertTrue(plan.isResolved(C));
	}

	@Test
	public void unknownPartialIsMarkedUnresolved() {
		var loader = new CountingLoader();
		var plan = PartialExpansionPlan.of(loader);
		String missing = "missing.mustache";
		var first = assertThrows(IOException.class, () -> plan.open(missing));
		var second = assertThrows(IOException.class, () -> plan.open(missing));
		assertSame(first, second);
		assertEquals(1, loader.count(missing));
		assertFalse(plan.isResolved(missing));
	}

}
