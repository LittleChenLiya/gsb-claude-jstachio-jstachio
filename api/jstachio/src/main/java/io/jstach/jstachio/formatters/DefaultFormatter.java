package io.jstach.jstachio.formatters;

import java.net.URI;
import java.net.URL;

import org.eclipse.jdt.annotation.Nullable;

import io.jstach.jstache.JStacheFormatter;
import io.jstach.jstache.JStacheFormatterTypes;
import io.jstach.jstachio.Appender;
import io.jstach.jstachio.Formatter;
import io.jstach.jstachio.Output;
import io.jstach.jstachio.output.EscapeOutputAdapter;

/**
 * Default formatters.
 *
 * Unlike the mustache spec it will throw a NPE trying to format null objects.
 * <p>
 * If the output is an {@link EscapeOutputAdapter} escaped variable writes are associated
 * with the variable path so that downstream {@link java.io.IOException}s are reported as
 * {@link io.jstach.jstachio.output.EscapedVariableException}s. Unescaped (triple
 * mustache) variables and native types are passed through unchanged.
 *
 * @author agentgt
 */
@JStacheFormatter
@JStacheFormatterTypes(types = { URI.class, URL.class })
public interface DefaultFormatter extends Formatter {

	/**
	 * {@inheritDoc} Will throw an NPE if parameter o is <code>null</code>.
	 */
	@Override
	default <A extends Output<E>, E extends Exception> void format(Appender downstream, A a, String path, Class<?> c,
			@Nullable Object o) throws E {
		if (o == null) {
			throw new NullPointerException("null at: '" + path + "'");
		}
		else {
			EscapeOutputAdapter.appendVariable(downstream, a, path, String.valueOf(o));
		}
	}

	/**
	 * {@inheritDoc} Will throw an NPE if parameter s is <code>null</code>.
	 */
	@Override
	default <A extends Output<E>, E extends Exception> void format(Appender downstream, A a, String path,
			@Nullable String s) throws E {
		if (s == null) {
			throw new NullPointerException("null at: '" + path + "'");
		}
		EscapeOutputAdapter.appendVariable(downstream, a, path, s);
	}

	/**
	 * Provides the default formatter for static lookup.
	 * @return the default formatter singleton
	 */
	public static Formatter provider() {
		return DefaultFormatterSingleton.DEFAULT_FORMATTER;
	}

	/**
	 * Provides the default formatter for static lookup.
	 * @return the default formatter singleton
	 */
	public static Formatter of() {
		return DefaultFormatterSingleton.DEFAULT_FORMATTER;
	}

}

enum DefaultFormatterSingleton implements DefaultFormatter {

	DEFAULT_FORMATTER;

}