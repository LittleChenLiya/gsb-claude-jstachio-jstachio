package io.jstach.jstachio;

import java.io.IOException;

/**
 * Appender decorator used by {@link EscapingOutput} for a single escaped Mustache
 * variable. It delegates to the wrapped {@link Escaper} and translates the first
 * {@link IOException} into an {@link EscapingOutputException} carrying the variable name
 * and position. The failed delegate call is never retried.
 */
final class EscapingAppender implements Appender {

	private final String variableName;

	private final int variablePosition;

	private final Escaper escaper;

	private final Output<IOException> output;

	EscapingAppender(String variableName, int variablePosition, Escaper escaper, Output<IOException> output) {
		this.variableName = variableName;
		this.variablePosition = variablePosition;
		this.escaper = escaper;
		this.output = output;
	}

	private IOException translate(IOException e) {
		if (e instanceof EscapingOutputException) {
			// Already contextualized; do not wrap twice or retry.
			return e;
		}
		return new EscapingOutputException(variableName, variablePosition, e);
	}

	/*
	 * EscapingOutput always declares E == IOException, but the Appender signature is
	 * generic. Re-throw through an erased helper so the checked IOException reaches the
	 * caller unchanged (and un-wrapped) without any retry.
	 */
	@SuppressWarnings("unchecked")
	private static <E extends Exception> E sneaky(IOException e) throws E {
		throw (E) e;
	}

	@Override
	public <A extends Output<E>, E extends Exception> void append(A a, CharSequence s) throws E {
		try {
			escaper.append(output, s);
		}
		catch (IOException e) {
			throw sneaky(translate(e));
		}
	}

	@Override
	public <A extends Output<E>, E extends Exception> void append(A a, CharSequence csq, int start, int end) throws E {
		try {
			escaper.append(output, csq, start, end);
		}
		catch (IOException e) {
			throw sneaky(translate(e));
		}
	}

	@Override
	public <A extends Output<E>, E extends Exception> void append(A a, char c) throws E {
		try {
			escaper.append(output, c);
		}
		catch (IOException e) {
			throw sneaky(translate(e));
		}
	}

	@Override
	public <A extends Output<E>, E extends Exception> void append(A a, short s) throws E {
		try {
			escaper.append(output, s);
		}
		catch (IOException e) {
			throw sneaky(translate(e));
		}
	}

	@Override
	public <A extends Output<E>, E extends Exception> void append(A a, int i) throws E {
		try {
			escaper.append(output, i);
		}
		catch (IOException e) {
			throw sneaky(translate(e));
		}
	}

	@Override
	public <A extends Output<E>, E extends Exception> void append(A a, long l) throws E {
		try {
			escaper.append(output, l);
		}
		catch (IOException e) {
			throw sneaky(translate(e));
		}
	}

	@Override
	public <A extends Output<E>, E extends Exception> void append(A a, double d) throws E {
		try {
			escaper.append(output, d);
		}
		catch (IOException e) {
			throw sneaky(translate(e));
		}
	}

	@Override
	public <A extends Output<E>, E extends Exception> void append(A a, boolean b) throws E {
		try {
			escaper.append(output, b);
		}
		catch (IOException e) {
			throw sneaky(translate(e));
		}
	}

}
