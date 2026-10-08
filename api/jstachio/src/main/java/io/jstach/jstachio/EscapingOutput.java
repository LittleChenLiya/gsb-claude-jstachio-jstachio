package io.jstach.jstachio;

import java.io.IOException;
import java.util.Objects;

import io.jstach.jstachio.output.ForwardingOutput;

/**
 * An {@link Output} adapter that associates {@link IOException}s raised while writing an
 * <strong>escaped</strong> Mustache variable ({@code {{name}}}) with the current template
 * variable name and its position in the rendered output.
 * <p>
 * Writes that do not belong to an escaped variable (template markup and
 * <strong>unescaped</strong> variables such as {@code {{{name}}} or {@code {{& name}}})
 * are forwarded to the delegate output unchanged. The original
 * {@link io.jstach.jstachio.Renderer renderer} interface and its output contract are
 * untouched: this class is purely opt-in adaptation.
 * <p>
 * A render call against this output looks like:
 * <pre>{@code
 * EscapingOutput out = EscapingOutput.of(Output.of(appendable));
 * // generated code for "{{name}}"
 * formatter.format(out.escaper("name", Html.of()), out, "name", value);
 * // generated code for "{{{raw}}}"
 * formatter.format(out.rawAppender(), out, "raw", rawValue);
 * }</pre>
 * <p>
 * When the delegate appendable fails during an escaped write the failure is converted to
 * an {@link EscapingOutputException} that carries the variable name, the zero based
 * variable position and the original {@link IOException} as its
 * {@linkplain Throwable#getCause() cause}. The failed write is <strong>not</strong>
 * retried so the appendable never receives duplicated bytes.
 *
 * @author agentgt
 * @see EscapingOutputException
 */
public final class EscapingOutput extends ForwardingOutput<IOException> {

	private final Output<IOException> delegate;

	private int escapedVariableCount = 0;

	private EscapingOutput(Output<IOException> delegate) {
		super();
		this.delegate = delegate;
	}

	/**
	 * Wraps an {@link IOException} based output (for example
	 * {@link Output#of(Appendable)}).
	 * @param output the output to forward writes to. Never null.
	 * @return a new escaping output.
	 */
	public static EscapingOutput of(Output<IOException> output) {
		return new EscapingOutput(Objects.requireNonNull(output, "output"));
	}

	@Override
	protected Output<IOException> delegate() {
		return delegate;
	}

	/**
	 * Decorates an {@link Escaper} for the next escaped variable rendered to this output.
	 * Every call advances the zero based variable position by one, so callers should
	 * invoke this method exactly once per escaped Mustache variable in template order.
	 * @param variableName the dotted Mustache path of the variable (e.g. {@code a.b}).
	 * Never null.
	 * @param escaper the escaper that normally receives escaped variable output
	 * (typically {@link io.jstach.jstachio.escapers.Html#of()}). Never null.
	 * @return an appender that reports write failures as
	 * {@link EscapingOutputException}.
	 */
	public Appender escaper(String variableName, Escaper escaper) {
		Objects.requireNonNull(variableName, "variableName");
		Objects.requireNonNull(escaper, "escaper");
		int position = escapedVariableCount++;
		return new EscapingAppender(variableName, position, escaper, this);
	}

	/**
	 * The appender used for unescaped variables ({@code {{{name}}} and {@code {{& name}}}
	 * ). It writes verbatim, never escapes and does <strong>not</strong> translate
	 * exceptions: failures surface as the original {@link IOException} with the exact
	 * bytes the appendable accepted.
	 * @return an identity appender writing directly to this output.
	 */
	public Appender rawAppender() {
		return RAW_APPENDER;
	}

	private static final Appender RAW_APPENDER = Appender.defaultAppender();

}
