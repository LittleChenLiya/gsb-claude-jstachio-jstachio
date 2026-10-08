package io.jstach.jstachio;

import java.io.IOException;
import java.util.Objects;

/**
 * Thrown when an {@link Escaper escaper} fails while writing an <strong>escaped</strong>
 * Mustache variable ({@code {{name}}}) to the downstream appendable.
 * <p>
 * The exception associates the write failure with the template variable that was being
 * rendered:
 * <ul>
 * <li>{@link #getVariableName()} is the dotted Mustache path of the variable
 * (e.g. {@code user.name}).</li>
 * <li>{@link #getVariablePosition()} is the zero based position of the variable amongst
 * the variables rendered to the wrapped output.</li>
 * </ul>
 * The original failure is always available as {@link #getCause()} and is an
 * {@link IOException}. Only the bytes written before the failure reached the appendable;
 * no retry is attempted so bytes are never duplicated.
 * <p>
 * Unescaped variables ({@code {{{name}}}} or {@code {{& name}}}) are written verbatim and
 * are not reported by this exception.
 *
 * @author agentgt
 * @see EscapingOutput
 */
public class EscapingOutputException extends IOException {

	private static final long serialVersionUID = 1L;

	private final String variableName;

	private final int variablePosition;

	/**
	 * Creates the exception.
	 * @param variableName the dotted Mustache path of the escaped variable being written.
	 * @param variablePosition zero based position of the variable amongst the variables
	 * rendered to the output.
	 * @param cause the original {@link IOException} from the appendable. Never null.
	 */
	public EscapingOutputException(String variableName, int variablePosition, IOException cause) {
		super(message(variableName, variablePosition), Objects.requireNonNull(cause, "cause"));
		this.variableName = Objects.requireNonNull(variableName, "variableName");
		this.variablePosition = variablePosition;
	}

	private static String message(String variableName, int variablePosition) {
		return "Appendable write failed while escaping variable '" + variableName + "' at variable position "
				+ variablePosition;
	}

	/**
	 * The escaped template variable that was being rendered when the appendable failed.
	 * @return the dotted Mustache path, never null.
	 */
	public String getVariableName() {
		return variableName;
	}

	/**
	 * Zero based position of the variable amongst the variables rendered to the wrapped
	 * output.
	 * @return the variable position.
	 */
	public int getVariablePosition() {
		return variablePosition;
	}

	/**
	 * The original appendable failure.
	 * @return an {@link IOException}, never null.
	 */
	@Override
	public synchronized IOException getCause() {
		return (IOException) super.getCause();
	}

}
