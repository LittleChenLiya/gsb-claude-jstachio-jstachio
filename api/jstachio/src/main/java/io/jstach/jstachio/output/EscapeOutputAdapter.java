package io.jstach.jstachio.output;

import java.io.IOException;

import org.eclipse.jdt.annotation.Nullable;

import io.jstach.jstachio.Appender;
import io.jstach.jstachio.Escaper;
import io.jstach.jstachio.Formatter;
import io.jstach.jstachio.Output;

/**
 * An escape output adapter that decorates an {@link Output} (or {@link Appendable}) and
 * associates write failures with the template variable currently being escaped.
 * <p>
 * When an escaped variable is written through this adapter and the downstream
 * {@link Appendable} fails with an {@link IOException} the adapter throws an
 * {@link EscapedVariableException} that carries the {@linkplain #currentVariable()
 * current variable} name and the {@linkplain #position() position} in the output where
 * the variable started. The original {@link IOException} is always preserved as the
 * cause.
 * <p>
 * The adapter never alters, buffers or retries content. In particular unescaped
 * (triple mustache) variables and literal text are written to the downstream output
 * exactly as-is and a failed write is never retried so already written bytes are not
 * duplicated.
 * <p>
 * Generated renderers call {@link Formatter#format(Appender, Output, String, String)}
 * (and its object variant) for variables. {@link io.jstach.jstachio.formatters.DefaultFormatter}
 * routes those calls through {@link #appendVariable(Appender, Output, String, CharSequence)}
 * so simply executing a template with this adapter enables the attribution:
 *
 * <pre>
 * EscapeOutputAdapter output = EscapeOutputAdapter.of(appendable);
 * template.execute(model, output);
 * </pre>
 *
 * The adapter is <strong>not</strong> threadsafe and is expected to be used for a single
 * render like the underlying appendable.
 *
 * @author agentgt
 * @see EscapedVariableException
 * @see io.jstach.jstachio.formatters.DefaultFormatter
 */
public final class EscapeOutputAdapter implements Output<IOException> {

	private final Output<IOException> downstream;

	private @Nullable String variableName;

	private long variablePosition;

	private long position;

	private EscapeOutputAdapter(Output<IOException> downstream) {
		this.downstream = downstream;
	}

	/**
	 * Adapts an {@link Appendable} as an escape output adapter.
	 * @param appendable the downstream appendable to write to.
	 * @return the adapter
	 */
	public static EscapeOutputAdapter of(Appendable appendable) {
		return of(Output.of(appendable));
	}

	/**
	 * Adapts an {@link Output} as an escape output adapter.
	 * @param downstream the downstream output to write to.
	 * @return the adapter
	 */
	public static EscapeOutputAdapter of(Output<IOException> downstream) {
		return new EscapeOutputAdapter(downstream);
	}

	/**
	 * Marks the beginning of an escaped variable write and records the current
	 * {@linkplain #position() position} as the variable position. Any downstream
	 * {@link IOException} until {@link #endVariable()} is called will be associated with
	 * this variable.
	 * @param variableName the dotted mustache like path of the variable.
	 * @return this adapter for chaining.
	 */
	public EscapeOutputAdapter variable(String variableName) {
		this.variableName = variableName;
		this.variablePosition = position;
		return this;
	}

	/**
	 * Clears the current variable so that subsequent writes are not attributed to it.
	 * @return this adapter for chaining.
	 */
	public EscapeOutputAdapter endVariable() {
		this.variableName = null;
		return this;
	}

	/**
	 * The current escaped variable being written or <code>null</code> if none.
	 * @return the current variable name or <code>null</code>.
	 */
	public @Nullable String currentVariable() {
		return variableName;
	}

	/**
	 * The current character offset of successfully written content in the output.
	 * @return the current position in the output.
	 */
	public long position() {
		return position;
	}

	/**
	 * The downstream output that content is forwarded to.
	 * @return the downstream output.
	 */
	public Output<IOException> downstream() {
		return downstream;
	}

	@Override
	public void append(CharSequence s) throws IOException {
		try {
			downstream.append(s);
		}
		catch (IOException ex) {
			throw wrap(ex);
		}
		position += s.length();
	}

	@Override
	public void append(String s) throws IOException {
		try {
			downstream.append(s);
		}
		catch (IOException ex) {
			throw wrap(ex);
		}
		position += s.length();
	}

	@Override
	public void append(CharSequence csq, int start, int end) throws IOException {
		try {
			downstream.append(csq, start, end);
		}
		catch (IOException ex) {
			throw wrap(ex);
		}
		position += (end - start);
	}

	@Override
	public void append(char c) throws IOException {
		try {
			downstream.append(c);
		}
		catch (IOException ex) {
			throw wrap(ex);
		}
		position += 1;
	}

	private IOException wrap(IOException ex) {
		String name = this.variableName;
		if (name == null || ex instanceof EscapedVariableException) {
			return ex;
		}
		return new EscapedVariableException(name, variablePosition, ex);
	}

	/**
	 * Appends the formatted content of a template variable attributing downstream
	 * {@link IOException}s to the variable if the output is an
	 * {@link EscapeOutputAdapter} and the downstream appender is an escaping
	 * {@link Escaper}.
	 * <p>
	 * If the output is not an adapter or the downstream appender is the
	 * {@linkplain Appender#defaultAppender() default pass through appender} (which
	 * generated renderers use for <em>unescaped</em> triple mustache variables) the
	 * content is appended as-is and no attribution happens.
	 * @param <A> the output type
	 * @param <E> the error type which is {@link IOException} when the output is an
	 * adapter
	 * @param downstream the appender (usually the escaper) that will write the content
	 * @param output the output maybe an adapter
	 * @param variableName the dotted mustache like path of the variable
	 * @param content the formatted content to write
	 * @throws E if the appender or output throws an exception
	 */
	public static <A extends Output<E>, E extends Exception> void appendVariable(Appender downstream, A output,
			String variableName, CharSequence content) throws E {
		if (output instanceof EscapeOutputAdapter adapter && isEscaping(downstream)) {
			adapter.variable(variableName);
			try {
				downstream.append(adapter, content);
			}
			catch (IOException ex) {
				/*
				 * The output is this adapter which only throws IOException so E is
				 * effectively IOException here.
				 */
				throw EscapeOutputAdapter.<E>sneakyRethrow(ex);
			}
			finally {
				adapter.endVariable();
			}
		}
		else {
			downstream.append(output, content);
		}
	}

	private static boolean isEscaping(Appender downstream) {
		return downstream instanceof Escaper && downstream != Appender.defaultAppender();
	}

	@SuppressWarnings("unchecked")
	private static <E extends Exception> RuntimeException sneakyRethrow(IOException ex) throws E {
		throw (E) ex;
	}

}
