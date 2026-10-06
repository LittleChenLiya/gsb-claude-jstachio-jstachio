package io.jstach.apt;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.annotation.Nullable;

import io.jstach.apt.TemplateCompilerLike.TemplateLoader;
import io.jstach.apt.internal.Position;
import io.jstach.apt.internal.ProcessingException;
import io.jstach.apt.internal.context.TemplateStack;

/**
 * An explicit expansion plan for partial templates.
 * <p>
 * The plan serves two purposes while a single renderer class is generated:
 * <ul>
 * <li><strong>Read snapshot once.</strong> Each template is loaded at most once per
 * plan and the decoded content is cached. Partials that are legally shared by many
 * templates (a diamond in the include graph) are therefore only read from the
 * underlying loader a single time and every expansion sees the same snapshot.</li>
 * <li><strong>Cycle plan.</strong> {@link #checkCycle(TemplateStack, String, Position)}
 * binds cycle detection to the real logical names the {@link TemplateLoader} is
 * consulted with and locates the recursion chain before a partial is expanded
 * infinitely. Only the current expansion path (the template stack callers) is
 * considered, so the same partial referenced from sibling locations is not
 * mistaken for a cycle.</li>
 * </ul>
 * Dynamic or otherwise unknown loaders cannot be planned ahead of time. Such
 * loaders are consulted lazily at expansion time and names they fail to resolve
 * are marked <em>unresolved</em>: the failure is replayed deterministically for
 * each referencing location without consulting the loader again.
 *
 * @author agent
 */
final class PartialExpansionPlan implements TemplateLoader {

	private final TemplateLoader loader;

	private final Map<String, Snapshot> snapshots = new LinkedHashMap<>();

	private PartialExpansionPlan(TemplateLoader loader) {
		this.loader = loader;
	}

	/**
	 * Creates an expansion plan on top of the given loader. If the loader already
	 * is a plan it is returned as is so that snapshots are shared.
	 * @param loader the backing loader
	 * @return the expansion plan
	 */
	public static PartialExpansionPlan of(TemplateLoader loader) {
		if (loader instanceof PartialExpansionPlan plan) {
			return plan;
		}
		return new PartialExpansionPlan(loader);
	}

	@Override
	public NamedReader open(String name) throws IOException {
		var snapshot = snapshots.get(name);
		if (snapshot == null) {
			snapshot = loadSnapshot(name);
			snapshots.put(name, snapshot);
		}
		return snapshot.open(name);
	}

	/**
	 * Determines whether the given logical name has been resolved to a template
	 * snapshot. Names that a dynamic or unknown loader failed to resolve stay
	 * unresolved.
	 * @param name the logical template name
	 * @return true if the name resolved to template content
	 */
	public boolean isResolved(String name) {
		return snapshots.get(name) instanceof Resolved;
	}

	private Snapshot loadSnapshot(String name) {
		try (NamedReader reader = loader.open(name)) {
			return new Resolved(readAll(reader), reader.path());
		}
		catch (IOException ex) {
			/*
			 * The loader cannot resolve the name. Mark it unresolved so that the
			 * loader is not consulted again and every referencing location sees the
			 * same failure.
			 */
			return new Unresolved(ex);
		}
	}

	private static String readAll(Reader reader) throws IOException {
		StringBuilder sb = new StringBuilder();
		char[] buffer = new char[4096];
		int read;
		while ((read = reader.read(buffer)) >= 0) {
			sb.append(buffer, 0, read);
		}
		return sb.toString();
	}

	/**
	 * Validates that expanding the partial with the given logical name from the
	 * current template stack will not close a recursion cycle. The check happens
	 * before the partial is expanded so a cyclic include chain is reported instead
	 * of expanding infinitely.
	 * @param stack the current template stack (innermost first)
	 * @param templateName the logical name the loader would be consulted with
	 * @param position the source position of the partial tag
	 * @throws ProcessingException if the name already appears on the current
	 * expansion path, the exception message contains the full cycle chain
	 */
	public static void checkCycle(TemplateStack stack, String templateName, Position position)
			throws ProcessingException {
		List<String> chain = new ArrayList<>();
		boolean cycle = false;
		for (@Nullable TemplateStack s = stack; s != null; s = s.getCaller()) {
			switch (s.getTemplateType()) {
				case ROOT, PARTIAL, PARAMETER_PARTIAL -> {
					chain.add(s.getTemplateName());
					if (s.getTemplateName().equals(templateName)) {
						cycle = true;
					}
				}
				case LAMBDA -> {
					/* Lambda names are model variables and not template names. */
				}
			}
			if (cycle) {
				break;
			}
		}
		if (cycle) {
			Collections.reverse(chain);
			chain.add(templateName);
			throw new ProcessingException(position,
					"Partial template cycle detected: " + String.join(" -> ", chain) + ".");
		}
	}

	private sealed interface Snapshot {

		NamedReader open(String name) throws IOException;

	}

	private record Resolved(String content, String path) implements Snapshot {

		@Override
		public NamedReader open(String name) {
			return new NamedReader(new StringReader(content), name, path);
		}

	}

	private record Unresolved(IOException exception) implements Snapshot {

		@Override
		public NamedReader open(String name) throws IOException {
			throw exception;
		}

	}

}
