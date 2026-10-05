/*
 * Copyright (c) 2014, Victor Nazarov <asviraspossible@gmail.com>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 *  1. Redistributions of source code must retain the above copyright notice,
 *     this list of conditions and the following disclaimer.
 *
 *  2. Redistributions in binary form must reproduce the above copyright notice,
 *     this list of conditions and the following disclaimer in the documentation and/or
 *     other materials provided with the distribution.
 *
 *  3. Neither the name of the copyright holder nor the names of its contributors
 *     may be used to endorse or promote products derived from this software
 *     without specific prior written permission.
 *
 *  THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 *  ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO,
 *  THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 *  IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
 *  ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 *  (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 *   LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 *  ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 *  (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE,
 *  EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package io.jstach.apt;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.jdt.annotation.Nullable;

import io.jstach.apt.TemplateCompilerLike.TemplateLoader;
import io.jstach.apt.internal.MustacheToken;
import io.jstach.apt.internal.MustacheToken.TagToken;
import io.jstach.apt.internal.MustacheTokenProcessor;
import io.jstach.apt.internal.Position;
import io.jstach.apt.internal.ProcessingException;
import io.jstach.apt.internal.TokenProcessor;
import io.jstach.apt.internal.token.MustacheTagKind;
import io.jstach.apt.internal.token.MustacheTokenizer;
import io.jstach.apt.prism.Prisms;

/**
 * An explicit expansion plan for template partials.
 * <p>
 * Before any renderer code is generated the plan walks every partial reference
 * ({@code {{> partial}}} as well as parent parameter partials
 * {@code {{< parent}}}) reachable from the root template and:
 * <ul>
 * <li>binds each reference to the real logical name the {@link TemplateLoader}
 * resolves (the resolved path for file templates, the logical name for inline
 * templates),</li>
 * <li>locates recursive partial chains <em>before</em> they would infinitely
 * expand and reports the full chain (e.g. {@code a -&gt; b -&gt; a}),</li>
 * <li>allows the same partial to be legally shared (included) from many places
 * without reporting a false cycle,</li>
 * <li>marks dynamic or otherwise unknown-to-the-static-loader partials (such as
 * the lambda provided {@code @section} partial) as {@link State#PENDING
 * pending} so that normal (dynamic) compilation semantics are preserved,</li>
 * <li>reads each template snapshot exactly once and reports unknown partials
 * with the source position of the referencing tag.</li>
 * </ul>
 * Because the plan is checked up front, a failure prevents a half written Java
 * renderer class from being generated.
 *
 * @author agentgt
 */
final class PartialExpansionPlan {

	/**
	 * Expansion state of a resolved template (node) in the plan.
	 */
	enum State {

		/**
		 * Discovered but not (yet or not statically) expandable. Dynamic partials
		 * stay pending and are left to the compiler.
		 */
		PENDING,
		/**
		 * Currently on the expansion stack. Re-entering an expanding node is a
		 * recursive chain.
		 */
		EXPANDING,
		/**
		 * Fully expanded. Re-entering a done node is legal sharing, not a cycle.
		 */
		DONE;

	}

	/**
	 * A partial reference found in a template source.
	 * @param name partial name as written in the template
	 * @param position source position of the referencing tag
	 */
	record PartialRef(String name, Position position) {
	}

	/**
	 * A template snapshot read once from the loader.
	 * @param key real logical name the loader bound (node identity)
	 * @param name template name as requested
	 * @param path resolved path of the template
	 * @param content template source
	 */
	record Snapshot(String key, String name, String path, String content) {
	}

	private record Frame(String key, String name) {
	}

	/*
	 * CodeWriter binds inline templates to this path.
	 */
	private static final String INLINE_PATH = "INLINE";

	private final TemplateLoader loader;

	private final Map<String, Snapshot> snapshots = new LinkedHashMap<>();

	private final Map<String, String> resolvedNames = new LinkedHashMap<>();

	private final Map<String, State> states = new LinkedHashMap<>();

	private final Set<String> pending = new LinkedHashSet<>();

	private final Deque<Frame> stack = new ArrayDeque<>();

	private PartialExpansionPlan(TemplateLoader loader) {
		this.loader = loader;
	}

	/**
	 * Builds and checks the partial expansion plan for the given root template.
	 * @param rootTemplateName the root template name
	 * @param loader the loader used to bind logical template names
	 * @return the checked plan whose {@linkplain #snapshotLoader() snapshot
	 * loader} should be used for compilation
	 * @throws ProcessingException if a recursive partial chain is found or a
	 * partial cannot be resolved
	 */
	static PartialExpansionPlan check(String rootTemplateName, TemplateLoader loader) throws ProcessingException {
		PartialExpansionPlan plan = new PartialExpansionPlan(loader);
		plan.expand(rootTemplateName, null);
		return plan;
	}

	/**
	 * A loader that serves the snapshots read by the plan so that each template
	 * is read exactly once across planning and compilation. Dynamic (pending)
	 * partials are delegated to the original loader.
	 * @return a read-once snapshot loader
	 */
	TemplateLoader snapshotLoader() {
		return name -> {
			String key = resolvedNames.get(name);
			Snapshot snapshot = key != null ? snapshots.get(key) : null;
			if (snapshot != null) {
				return new NamedReader(new StringReader(snapshot.content()), snapshot.name(), snapshot.path());
			}
			/*
			 * Dynamic or otherwise unplanned (pending) partial.
			 */
			return loader.open(name);
		};
	}

	/**
	 * The partial names that were marked pending because they cannot be
	 * statically resolved (e.g. the dynamic lambda {@code @section} partial).
	 * @return pending partial names
	 */
	Set<String> pending() {
		return Collections.unmodifiableSet(pending);
	}

	/**
	 * The resolved template snapshots keyed by their real logical name.
	 * @return resolved snapshots
	 */
	Map<String, Snapshot> snapshots() {
		return Collections.unmodifiableMap(snapshots);
	}

	private void expand(String name, @Nullable Position refPosition) throws ProcessingException {
		if (isDynamic(name)) {
			/*
			 * Dynamic partials (e.g. the lambda provided "@section") are bound by a
			 * different loader at compile time so we mark them pending and do not
			 * statically expand them.
			 */
			pending.add(name);
			states.putIfAbsent(dynamicKey(name), State.PENDING);
			return;
		}
		Snapshot snapshot = resolve(name, refPosition);
		String key = snapshot.key();
		State state = states.get(key);
		if (state == State.DONE) {
			/*
			 * Legal sharing of an already expanded partial.
			 */
			return;
		}
		if (state == State.EXPANDING) {
			throw new ProcessingException(positionOrNone(refPosition), cycleMessage(key, name));
		}
		states.put(key, State.EXPANDING);
		stack.push(new Frame(key, name));
		try {
			for (PartialRef ref : scan(snapshot)) {
				expand(ref.name(), ref.position());
			}
		}
		finally {
			stack.pop();
		}
		states.put(key, State.DONE);
	}

	private Snapshot resolve(String name, @Nullable Position refPosition) throws ProcessingException {
		String key = resolvedNames.get(name);
		if (key != null) {
			Snapshot snapshot = snapshots.get(key);
			if (snapshot != null) {
				return snapshot;
			}
		}
		final NamedReader reader;
		try {
			reader = loader.open(name);
		}
		catch (IOException e) {
			throw new ProcessingException(positionOrNone(refPosition), "Unknown partial \"" + name
					+ "\" could not be resolved" + describeChain() + ". " + e.getMessage());
		}
		String path = reader.path();
		String resolvedKey = INLINE_PATH.equals(path) ? INLINE_PATH + ":" + reader.name() : path;
		Snapshot existing = snapshots.get(resolvedKey);
		if (existing != null) {
			/*
			 * A different logical name bound to the same template. Do not read the
			 * snapshot again.
			 */
			resolvedNames.put(name, resolvedKey);
			try {
				reader.close();
			}
			catch (IOException e) {
				throw new ProcessingException(positionOrNone(refPosition), e);
			}
			return existing;
		}
		final String content;
		try {
			content = readAll(reader);
		}
		catch (IOException e) {
			throw new ProcessingException(positionOrNone(refPosition),
					"Partial \"" + name + "\" could not be read" + describeChain() + ". " + e.getMessage());
		}
		Snapshot snapshot = new Snapshot(resolvedKey, reader.name(), path, content);
		resolvedNames.put(name, resolvedKey);
		snapshots.put(resolvedKey, snapshot);
		return snapshot;
	}

	private List<PartialRef> scan(Snapshot snapshot) throws ProcessingException {
		List<PartialRef> refs = new ArrayList<>();
		MustacheTokenProcessor collector = positionedToken -> {
			MustacheToken token = positionedToken.innerToken();
			if (token instanceof TagToken tt && (tt.tagKind() == MustacheTagKind.PARTIAL
					|| tt.tagKind() == MustacheTagKind.BEGIN_PARENT_SECTION)) {
				refs.add(new PartialRef(tt.name(), positionedToken.position()));
			}
		};
		TokenProcessor<@Nullable Character> processor = MustacheTokenizer.createInstance(snapshot.name(), collector);
		String content = snapshot.content();
		for (int i = 0; i < content.length(); i++) {
			processor.processToken(content.charAt(i));
		}
		processor.processToken(TokenProcessor.EOF);
		return refs;
	}

	private String cycleMessage(String key, String name) {
		List<String> chain = new ArrayList<>();
		boolean recording = false;
		Iterator<Frame> it = stack.descendingIterator();
		while (it.hasNext()) {
			Frame frame = it.next();
			if (frame.key().equals(key)) {
				recording = true;
			}
			if (recording) {
				chain.add(frame.name());
			}
		}
		chain.add(name);
		return "Recursive partial reference detected: " + String.join(" -> ", chain)
				+ ". Partials cannot recursively include each other.";
	}

	private String describeChain() {
		if (stack.isEmpty()) {
			return "";
		}
		List<String> chain = new ArrayList<>();
		Iterator<Frame> it = stack.descendingIterator();
		while (it.hasNext()) {
			chain.add(it.next().name());
		}
		return " while expanding " + String.join(" -> ", chain);
	}

	private static Position positionOrNone(@Nullable Position position) {
		return position != null ? position : Position.noPosition();
	}

	private static boolean isDynamic(String name) {
		return Prisms.JSTACHE_LAMBDA_SECTION_PARTIAL_NAME.equals(name);
	}

	private static String dynamicKey(String name) {
		return "DYNAMIC:" + name;
	}

	private static String readAll(NamedReader reader) throws IOException {
		StringBuilder sb = new StringBuilder();
		char[] buffer = new char[4096];
		try (reader) {
			int n;
			while ((n = reader.read(buffer, 0, buffer.length)) >= 0) {
				sb.append(buffer, 0, n);
			}
		}
		return sb.toString();
	}

}
