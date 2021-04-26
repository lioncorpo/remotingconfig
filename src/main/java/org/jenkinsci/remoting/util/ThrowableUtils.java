/*
 * The MIT License
 *
 * Copyright (c) 2016, CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.jenkinsci.remoting.util;

import javax.annotation.CheckForNull;
import org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Utility methods to help when working with {@link Throwable} instances.
 *
 * @since FIXME
 */
public class ThrowableUtils {

    /**
     * This is a utility class, prevent accidental instance creation.
     */
    private ThrowableUtils() {
        throw new IllegalAccessError("Utility class");
    }

    /**
     * Helper to access {@link Throwable#addSuppressed(Throwable)} easily in code that is checked against the Java 1.6
     * signatures.
     *
     * @param primary    the main {@link Throwable}.
     * @param suppressed the suppressed {@link Throwable} to attach.
     * @param <T>        the type of the main {@link Throwable}.
     * @return the main {@link Throwable}.
     */
    @SuppressWarnings("Since15")
    @IgnoreJRERequirement // TODO remove and inline method once Java 7 is baseline
    @Restricted(NoExternalUse.class)
    public static <T extends Throwable> T addSuppressed(T primary, Throwable suppressed) {
        try {
            primary.addSuppressed(suppressed);
        } catch (LinkageError e) {
            // best effort only
        }
        return primary;
    }

    /**
     * Allows building a chain of exceptions.
     *
     * @param e1   The first exception (or {@code null}).
     * @param e2   The second exception (or {@code null}).
     * @param <T>  The widened return type.
     * @param <T1> The type of first exception.
     * @param <T2> The type of second exception.
     * @return The first exception with the second added as a suppressed exception or the closest approximation to that.
     */
    @CheckForNull
    public static <T extends Throwable, T1 extends T, T2 extends T> T chain(@CheckForNull T1 e1, @CheckForNull T2 e2) {
        return e1 == null ? e2 : e2 == null ? e1 : addSuppressed(e1, e2);
    }
}
