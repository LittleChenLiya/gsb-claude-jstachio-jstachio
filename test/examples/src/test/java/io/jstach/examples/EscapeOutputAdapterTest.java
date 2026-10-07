package io.jstach.examples;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import io.jstach.jstache.JStache;
import io.jstach.jstachio.JStachio;
import io.jstach.jstachio.escapers.Html;
import io.jstach.jstachio.escapers.PlainText;
import io.jstach.jstachio.output.EscapeOutputAdapter;
import io.jstach.jstachio.output.EscapedVariableException;

/**
 * Tests that the escape output adapter associates {@link Appendable} write failures with
 * the template variable name and position while unescaped variables and the original
 * renderer interfaces are left unchanged.
 */
public class EscapeOutputAdapterTest {

	@JStache(template = "Hello {{name}}! Raw: {{{raw}}}")
	public record Greeting(String name, String raw) {
	}

	/**
	 * Temp folder fixture to show the adapter writing to a file backed appendable.
	 */
	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	/**
	 * A small in memory appendable that fails on a specific write and counts every write
	 * so that retries (which would duplicate bytes) are detected.
	 */
	static class FailingAppendable implements Appendable {

		private final int failOnWrite;

		final StringBuilder received = new StringBuilder();

		int writes;

		FailingAppendable(int failOnWrite) {
			this.failOnWrite = failOnWrite;
		}

		private void count() throws IOException {
			writes++;
			if (writes == failOnWrite) {
				throw new IOException("Simulated failure on write " + writes);
			}
		}

		@Override
		public Appendable append(CharSequence csq) throws IOException {
			count();
			received.append(csq);
			return this;
		}

		@Override
		public Appendable append(CharSequence csq, int start, int end) throws IOException {
			count();
			received.append(csq, start, end);
			return this;
		}

		@Override
		public Appendable append(char c) throws IOException {
			count();
			received.append(c);
			return this;
		}

	}

	@Test
	public void escapedVariableFailureReportsNameAndPosition() throws IOException {
		FailingAppendable appendable = new FailingAppendable(2);
		EscapeOutputAdapter output = EscapeOutputAdapter.of(appendable);
		Greeting model = new Greeting("World", "<b>x</b>");
		try {
			GreetingRenderer.of().execute(model, output);
			fail("Expected an EscapedVariableException");
		}
		catch (EscapedVariableException ex) {
			// the escaped variable is associated ...
			assertEquals("name", ex.variableName());
			// ... with its position in the output ("Hello " is 6 characters) ...
			assertEquals(6L, ex.position());
			// ... and the original IOException is preserved as the cause
			assertEquals(IOException.class, ex.getCause().getClass());
			assertEquals("Simulated failure on write 2", ex.getCause().getMessage());
		}
		// no retry happened so there are no duplicate bytes and exactly two writes
		assertEquals(2, appendable.writes);
		assertEquals("Hello ", appendable.received.toString());
	}

	@Test
	public void unescapedVariableFailurePassesThrough() throws IOException {
		// writes: 1="Hello ", 2="World", 3="! Raw: ", 4="<b>x</b>" (triple mustache)
		FailingAppendable appendable = new FailingAppendable(4);
		EscapeOutputAdapter output = EscapeOutputAdapter.of(appendable);
		Greeting model = new Greeting("World", "<b>x</b>");
		try {
			GreetingRenderer.of().execute(model, output);
			fail("Expected an IOException");
		}
		catch (EscapedVariableException ex) {
			fail("Unescaped variables should not be wrapped but was: " + ex);
		}
		catch (IOException ex) {
			// the original IOException passes through unchanged
			assertEquals("Simulated failure on write 4", ex.getMessage());
		}
		assertEquals(4, appendable.writes);
		assertEquals("Hello World! Raw: ", appendable.received.toString());
	}

	@Test
	public void normalOutputIsUnchanged() throws IOException {
		StringBuilder sb = new StringBuilder();
		EscapeOutputAdapter output = EscapeOutputAdapter.of(sb);
		Greeting model = new Greeting("<World>", "<b>x</b>");
		GreetingRenderer.of().execute(model, output);
		// escaped variable is escaped, triple mustache variable is output as-is
		assertEquals("Hello &lt;World&gt;! Raw: <b>x</b>", sb.toString());
	}

	@Test
	public void tempFileFixture() throws IOException {
		File file = temp.newFile("greeting.txt");
		Greeting model = new Greeting("<World>", "<b>x</b>");
		try (Writer writer = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8)) {
			GreetingRenderer.of().execute(model, EscapeOutputAdapter.of(writer));
		}
		String actual = Files.readString(file.toPath(), StandardCharsets.UTF_8);
		assertEquals("Hello &lt;World&gt;! Raw: <b>x</b>", actual);
	}

	/**
	 * Short regression example that the original renderer and escaper interfaces are
	 * unchanged and produce results that match the adapter output above.
	 */
	@Test
	public void originalRendererInterfaceRegression() {
		Greeting model = new Greeting("<World>", "<b>x</b>");
		String expected = "Hello &lt;World&gt;! Raw: <b>x</b>";
		// original Renderer interface
		assertEquals(expected, GreetingRenderer.of().execute(model));
		// original JStachio runtime
		assertEquals(expected, JStachio.render(model));
		// original Escaper function interface agrees with the escaped output
		assertEquals("&lt;World&gt;", Html.of().apply("<World>"));
		// unescaped content passes through the noop escaper as-is
		assertEquals("<b>x</b>", PlainText.of().apply("<b>x</b>"));
	}

}
