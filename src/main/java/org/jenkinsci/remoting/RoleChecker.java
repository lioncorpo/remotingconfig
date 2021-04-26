package org.jenkinsci.remoting;

import hudson.remoting.Callable;
import hudson.remoting.ChannelBuilder;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;

/**
 * Verifies that the callable is getting run on the intended recipient.
 *
 * @author Kohsuke Kawaguchi
 * @see ChannelBuilder#withRoleChecker(RoleChecker)
 * @since TODO
 */
public abstract class RoleChecker {
    /**
     * Called from {@link RoleSensitive#checkRoles(RoleChecker)} to ensure that this side of the channel
     * is willing to execute {@link Callable}s that expects one of the given roles on their intended recipients.
     * <p><strong>If you think you need to implement {@link RoleSensitive#checkRoles}</strong> please reread that method’s Javadoc.
     * <p>
     * Normally, each side of the channel has a fixed set of roles (say {@code actualRoles}),
     * and the implementation would be {@code actualRoles.containsAll(roles)}.
     *
     * @param subject
     *      Object whose role we are checking right now. Useful context information when reporting an error.
     * @param expected
     *      The current JVM that executes the callable should have one of these roles.
     *      Never empty nor null.
     * @throws SecurityException
     *      Any exception thrown will prevent the callable from getting executed, but we recommend
     *      {@link SecurityException}
     */
    public abstract void check(@Nonnull RoleSensitive subject, @Nonnull Collection<Role> expected) throws SecurityException;

    public void check(@Nonnull RoleSensitive subject, @Nonnull Role expected) throws SecurityException {
        check(subject, Collections.singleton(expected));
    }

    public void check(@Nonnull RoleSensitive subject, Role... expected) throws SecurityException {
        check(subject, Arrays.asList(expected));
    }
}
