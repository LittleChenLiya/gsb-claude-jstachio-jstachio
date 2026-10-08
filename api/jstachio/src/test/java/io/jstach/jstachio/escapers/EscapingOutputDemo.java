package io.jstach.jstachio.escapers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import io.jstach.jstachio.Appender;
import io.jstach.jstachio.Escaper;
import io.jstach.jstachio.EscapingOutput;
import io.jstach.jstachio.EscapingOutputException;
import io.jstach.jstachio.Formatter;
import io.jstach.jstachio.Output;
import io.jstach.jstachio.formatters.DefaultFormatter;

/**
 * Small deterministic acceptance entry point for the escaping output adapter.
 *
 * Simulated generated template:
 *
 * <pre>
 * E1={{escaped1}}|R={{{raw}}}|E2={{escaped2}}
 * </pre>
 *
 * It uses a tiny in-memory Appendable fixture (and, for the normal-output case, a
 * temporary file under the directory given by {@code java.io.tmpdir}). Run with:
 *
 * <pre>
 * java io.jstach.jstachio.escapers.EscapingOutputDemo
 * </pre>
 */
public final class EscapingOutputDemo {

	record Model(String escaped1, String raw, String escaped2) {
	}

	private EscapingOutputDemo() {
	}

	static void render(Model model, Output<IOException> output) throws IOException {
		Formatter formatter = DefaultFormatter.of();
		Escaper html = Html.of();
		if (output instanceof EscapingOutput out) {
			out.append("E1=");
			formatter.format(out.escaper("escaped1", html), out, "escaped1", model.escaped1());
			out.append("|R=");
			formatter.format(out.rawAppender(), out, "raw", model.raw());
			out.append("|E2=");
			formatter.format(out.escaper("escaped2", html), out, "escaped2", model.escaped2());
		}
		else {
			Appender raw = Appender.defaultAppender();
			output.append("E1=");
			formatter.format(html, output, "escaped1", model.escaped1());
			output.append("|R=");
			formatter.format(raw, output, "raw", model.raw());
			output.append("|E2=");
			formatter.format(html, output, "escaped2", model.escaped2());
		}
	}

	/** Appendable that throws on the Nth write call; counts writes and keeps accepted. */
	static final class SmallAppendable implements Appendable {

		final List<String> writes = new ArrayList<>();

		final int failOnWrite;

		int writesAttempted = 0;

		final IOException failure = new IOException("simulated small appendable failure");

		SmallAppendable(int failOnWrite) {
			this.failOnWrite = failOnWrite;
		}

		private void record(String chunk) throws IOException {
			writesAttempted++;
			if (writesAttempted == failOnWrite) {
				throw failure;
			}
			writes.add(chunk);
		}

		@Override
		public Appendable append(CharSequence csq) throws IOException {
			record(csq.toString());
			return this;
		}

		@Override
		public Appendable append(CharSequence csq, int start, int end) throws IOException {
			record(csq.subSequence(start, end).toString());
			return this;
		}

		@Override
		public Appendable append(char c) throws IOException {
			record(String.valueOf(c));
			return this;
		}

		String accepted() {
			return String.join("", writes);
		}
	}

	public static void main(String[] args) throws IOException {
		Model model = new Model("<a>", "<b>&</b>", "x>y");
		System.out.println("== Acceptance demo for escaping output adapter ==");
		System.out.println("template : E1={{escaped1}}|R={{{raw}}}|E2={{escaped2}}");
		System.out.println("model    : escaped1=<a>, raw=<b>&</b>, escaped2=x>y");
		System.out.println();

		// 1) normal output to a temp file fixture
		Path tempFile = Files.createTempFile("escaping-output-demo", ".txt");
		try (var writer = Files.newBufferedWriter(tempFile, StandardCharsets.UTF_8)) {
			render(model, Output.of(writer));
		}
		String fileContent = Files.readString(tempFile, StandardCharsets.UTF_8);
		System.out.println("[normal] temp file : " + tempFile);
		System.out.println("[normal] content   : " + fileContent);
		System.out.println("[normal] matches   : "
				+ "E1=&lt;a&gt;|R=<b>&</b>|E2=x&gt;y".equals(fileContent));
		System.out.println();

		// 2) small in-memory appendable fails on the SECOND write
		SmallAppendable small = new SmallAppendable(2);
		EscapingOutput out = EscapingOutput.of(Output.of(small));
		try {
			render(model, out);
			System.out.println("[fail] ERROR: expected exception");
			System.exit(1);
		}
		catch (EscapingOutputException e) {
			System.out.println("[fail] exception  : " + e.getClass().getName());
			System.out.println("[fail] message    : " + e.getMessage());
			System.out.println("[fail] variable   : " + e.getVariableName());
			System.out.println("[fail] position   : " + e.getVariablePosition());
			System.out.println("[fail] cause type : " + e.getCause().getClass().getName());
			System.out.println("[fail] cause msg  : " + e.getCause().getMessage());
			System.out.println("[fail] cause same : " + (e.getCause() == small.failure));
			System.out.println("[fail] attempted  : " + small.writesAttempted + " (accepted " + small.writes.size()
					+ ", no retry => no duplicated bytes)");
			System.out.println("[fail] accepted   : \"" + small.accepted() + "\"");
		}
		Files.deleteIfExists(tempFile);
	}

}
