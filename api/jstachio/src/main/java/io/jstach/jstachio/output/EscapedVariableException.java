package io.jstach.jstachio.output;

import java.io.IOException;

/**
 * An {@link IOException} that associates a failed {@link Appendable} write with the
 * template variable that was being escaped and written when the failure happened.
 * <p>
 * The original {@link IOException} thrown by the downstream {@link Appendable} is always
 * preserved as the {@linkplain #getCause() cause} and is never retried so that already
 * written bytes are not duplicated.
 *
 * @apiNote This exception is an {@link IOException} itself thus the
 * {@link io.jstach.jstachio.Renderer#execute(Object, Appendable)} contract of throwing
 * {@link IOException} is unchanged.
 * @author agentgt
 * @see EscapeOutputAdapter
 */
public class EscapedVariableException extends IOException {

	private static final long serialVersionUID = 4050904093158549693L;

	private final String variableName;

	private final long position;

	/**
	 * Create the exception from the current variable, its position and the original
	 * failure.
	 * @param variableName the dotted mustache like path of the variable being written.
	 * @param position the character offset in the output where the variable started to be
	 * written.
	 * @param cause the original {@link IOException} thrown by the downstream appendable.
	 */
	public EscapedVariableException(String variableName, long position, IOException cause) {
		super("Failed to write escaped variable '" + variableName + "' at position " + position + ".", cause);
		this.variableName = variableName;
		this.position = position;
	}

	/**
	 * The dotted mustache like path of the template variable that was being written when
	 * the downstream appendable failed.
	 * @return the variable name never <code>null</code>.
	 */
	public String variableName() {
		return variableName;
	}

	/**
	 * The character offset in the output where the variable started to be written.
	 * @return the position of the variable in the output.
	 */
	public long position() {
		return position;
	}

	/**
	 * The original {@link IOException} thrown by the downstream appendable.
	 * @return the original IOException never <code>null</code>.
	 */
	@Override
	public synchronized IOException getCause() {
		return (IOException) super.getCause();
	}

}
