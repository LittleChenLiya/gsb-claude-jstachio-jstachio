package io.jstach.jstachio.escapers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import io.jstach.jstachio.Appender;
import io.jstach.jstachio.Escaper;
import io.jstach.jstachio.EscapingOutput;
import io.jstach.jstachio.EscapingOutputException;
import io.jstach.jstachio.Formatter;
import io.jstach.jstachio.Output;
import io.jstach.jstachio.formatters.DefaultFormatter;

/**
 * Acceptance and regression tests for the escaping output adapter.
 *
 * The simulated generated template is:
 *
 * <pre>
 * E1={{escaped1}}|R={{{raw}}}|E2={{escaped2}}
 * </pre>
 *
 * i.e. it contains an escaped variable, an unescaped (triple brace) variable and another
 * escaped variable.
 */
public class EscapingOutputTest {

	/*
	 * Simulates the code generated for:
	 *
	 * E1={{escaped1}}|R={{{raw}}}|E2={{escaped2}}
	 *
	 * The original Renderer contract is preserved: the model is rendered straight into
	 * the supplied Output and IOException propagates to the caller.
	 */
	static void renderMiniTemplate(Model model, Output<IOException> output) throws IOException {
		Formatter formatter = DefaultFormatter.of();
		Escaper html = Html.of();

		if (output instanceof EscapingOutput out) {
			// Template rendered through the new escaping output adapter.
			out.append("E1=");
			// {{escaped1}}
			formatter.format(out.escaper("escaped1", html), out, "escaped1", model.escaped1());
			out.append("|R=");
			// {{{raw}}} -> unescaped, written verbatim
			formatter.format(out.rawAppender(), out, "raw", model.raw());
			out.append("|E2=");
			// {{escaped2}}
			formatter.format(out.escaper("escaped2", html), out, "escaped2", model.escaped2());
		}
		else {
			// Original generated-renderer interface: escaper is passed directly and
			// nothing is adapted; raw IOException surfaces unchanged.
			Appender raw = Appender.defaultAppender();
			output.append("E1=");
			formatter.format(html, output, "escaped1", model.escaped1());
			output.append("|R=");
			formatter.format(raw, output, "raw", model.raw());
			output.append("|E2=");
			formatter.format(html, output, "escaped2", model.escaped2());
		}
	}

	record Model(String escaped1, String raw, String escaped2) {
	}

	/** Appendable that throws on the Nth write call and records everything accepted. */
	static final class CountingAppendable implements Appendable {

		final List<String> writes = new ArrayList<>();

		final int failOnWrite;

		int writesAttempted = 0;

		final IOException failure = new IOException("disk full");

		CountingAppendable(int failOnWrite) {
			this.failOnWrite = failOnWrite;
		}

		private void before(String chunk) throws IOException {
			writesAttempted++;
			if (writesAttempted == failOnWrite) {
				throw failure;
			}
			writes.add(chunk);
		}

		@Override
		public Appendable append(CharSequence csq) throws IOException {
			before(csq.toString());
			return this;
		}

		@Override
		public Appendable append(CharSequence csq, int start, int end) throws IOException {
			before(csq.subSequence(start, end).toString());
			return this;
		}

		@Override
		public Appendable append(char c) throws IOException {
			before(String.valueOf(c));
			return this;
		}

		String accepted() {
			return String.join("", writes);
		}
	}

	// Happy path: nothing fails, escaped and unescaped output is correct.

	@Test
	public void normalOutputEscapesAndKeepsRawVerbatim() throws IOException {
		Model model = new Model("<a>", "<b>&</b>", "x>y");
		StringBuilder sb = new StringBuilder();
		renderMiniTemplate(model, Output.of((Appendable) sb));
		assertEquals("E1=&lt;a&gt;|R=<b>&</b>|E2=x&gt;y", sb.toString());
	}

	// Acceptance: small Appendable fails on the SECOND write.

	@Test
	public void secondWriteFailureReportsVariablePositionCauseWritesAndOutput() {
		Model model = new Model("<a>", "<b>&</b>", "x>y");
		CountingAppendable appendable = new CountingAppendable(2);
		EscapingOutput out = EscapingOutput.of(Output.of(appendable));

		try {
			renderMiniTemplate(model, out);
			fail("Expected EscapingOutputException");
		}
		catch (EscapingOutputException e) {
			// 1st write was static text "E1=", 2nd write is the first escaped variable
			assertEquals("escaped1", e.getVariableName());
			assertEquals(0, e.getVariablePosition());
			assertSame(appendable.failure, e.getCause());
			assertEquals(IOException.class, e.getCause().getClass());
		}
		catch (IOException e) {
			throw new AssertionError("Expected EscapingOutputException but got " + e, e);
		}

		// Exactly two writes attempted: one success and the failing one. No retries,
		// hence no duplicated bytes.
		assertEquals(2, appendable.writesAttempted);
		assertEquals(1, appendable.writes.size());
		assertEquals("E1=", appendable.accepted());
	}

	// Failure while rendering the SECOND escaped variable: position advances.

	@Test
	public void laterEscapedVariableHasCorrectPosition() {
		Model model = new Model("<a>", "<b>&</b>", "x>y");
		CountingAppendable appendable = new CountingAppendable(10);
		EscapingOutput out = EscapingOutput.of(Output.of(appendable));

		try {
			renderMiniTemplate(model, out);
			fail("Expected EscapingOutputException");
		}
		catch (EscapingOutputException e) {
			assertEquals("escaped2", e.getVariableName());
			assertEquals(1, e.getVariablePosition());
			assertSame(appendable.failure, e.getCause());
		}
		catch (IOException e) {
			throw new AssertionError(e);
		}
	}

	// Unescaped variable failure must surface as the raw IOException and must
	// not be retried (no duplicated bytes); raw content is never escaped.

	@Test
	public void unescapedVariableFailureIsRawAndNotRetried() {
		Model model = new Model("<a>", "<b>&</b>", "x>y");
		// writes 1-7: static text + escaped first variable; write 8 is the raw variable
		CountingAppendable appendable = new CountingAppendable(8);
		EscapingOutput out = EscapingOutput.of(Output.of(appendable));

		try {
			renderMiniTemplate(model, out);
			fail("Expected IOException");
		}
		catch (IOException e) {
			assertSame(appendable.failure, e);
		}

		assertEquals(8, appendable.writesAttempted);
		assertEquals(7, appendable.writes.size());
		assertTrue(appendable.accepted().contains("|R="));
		assertTrue(!appendable.accepted().contains("&amp;"));
	}

	// The original Renderer-style contract: callers may render to a plain
	// Output without the adapter and the raw IOException propagates unchanged.

	@Test
	public void plainOutputStillPropagatesRawIOException() {
		Model model = new Model("<a>", "<b>&</b>", "x>y");
		CountingAppendable appendable = new CountingAppendable(2);
		Output<IOException> out = Output.of(appendable);
		try {
			renderMiniTemplate(model, out);
			fail("Expected IOException");
		}
		catch (IOException e) {
			assertSame(appendable.failure, e);
		}
	}

	// Regression example for the original Appender/Escaper interface: escaping
	// a String and appending still works (Function<String,String> use).

	@Test
	public void originalEscaperInterfaceRegressionExample() {
		Escaper html = Html.of();
		assertEquals("&lt;a&gt;", html.apply("<a>"));

		StringBuilder sb = new StringBuilder();
		html.append(Output.of(sb), "<&>");
		assertEquals("&lt;&amp;&gt;", sb.toString());

		// identity raw appender stays an Appender and writes verbatim
		Appender raw = EscapingOutput.of(Output.of((Appendable) new StringBuilder())).rawAppender();
		StringBuilder rawSb = new StringBuilder();
		var rawOut = Output.of(rawSb);
		raw.append(rawOut, "<a>");
		assertEquals("<a>", rawSb.toString());
	}

}
