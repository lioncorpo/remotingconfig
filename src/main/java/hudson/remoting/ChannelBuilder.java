package hudson.remoting;

import java.util.logging.Level;
import java.util.logging.Logger;
import org.jenkinsci.remoting.CallableDecorator;
import hudson.remoting.Channel.Mode;
import org.jenkinsci.remoting.Role;
import org.jenkinsci.remoting.RoleChecker;
import org.jenkinsci.remoting.RoleSensitive;

import javax.annotation.Nonnull;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;

/**
 * Factory for {@link Channel}, including hand-shaking between two sides
 * and various configuration switches to change the behaviour of {@link Channel}.
 *
 * @author Kohsuke Kawaguchi
 */
public class ChannelBuilder {

    /**
     * Our logger.
     */
    private static final Logger LOGGER = Logger.getLogger(ChannelBuilder.class.getName());

    private final String name;
    private final ExecutorService executors;
    private ClassLoader base = this.getClass().getClassLoader();
    private Mode mode = Mode.NEGOTIATE;
    private Capability capability = new Capability();
    private OutputStream header;
    private JarCache jarCache;
    private List<CallableDecorator> decorators = new ArrayList<CallableDecorator>();
    private boolean arbitraryCallableAllowed = true;
    private boolean remoteClassLoadingAllowed = true;
    private final Hashtable<Object,Object> properties = new Hashtable<Object,Object>();
    private ClassFilter filter = ClassFilter.DEFAULT;

    /**
     * Specify the minimum mandatory parameters.
     *
     * @param name
     *      Human readable name of this channel. Used for debug/logging. Can be anything.
     * @param executors
     *      Commands sent from the remote peer will be executed by using this {@link Executor}.
     */
    public ChannelBuilder(String name, ExecutorService executors) {
        this.name = name;
        this.executors = executors;
    }

    public String getName() {
        return name;
    }

    public ExecutorService getExecutors() {
        return executors;
    }

    /**
     * Specify the classloader used for deserializing remote commands.
     * This is primarily related to {@link Channel#getRemoteProperty(Object)}. Sometimes two parties
     * communicate over a channel and pass objects around as properties, but those types might not be
     * visible from the classloader loading the {@link Channel} class. In such a case, specify a classloader
     * so that those classes resolve. If null, {@code Channel.class.getClassLoader()} is used.
     */
    public ChannelBuilder withBaseLoader(ClassLoader base) {
        if (base==null)     base = this.getClass().getClassLoader();
        this.base = base;
        return this;
    }

    public ClassLoader getBaseLoader() {
        return base;
    }

    /**
     * The encoding to be used over the stream.
     */
    public ChannelBuilder withMode(Mode mode) {
        this.mode = mode;
        return this;
    }

    public Mode getMode() {
        return mode;
    }

    /**
     * Controls the capabilities that we'll advertise to the other side.
     */
    public ChannelBuilder withCapability(Capability capability) {
        this.capability = capability;
        return this;
    }

    public Capability getCapability() {
        return capability;
    }

    /**
     * If non-null, receive the portion of data in <tt>is</tt> before
     * the data goes into the "binary mode". This is useful
     * when the established communication channel might include some data that might
     * be useful for debugging/trouble-shooting.
     */
    public ChannelBuilder withHeaderStream(OutputStream header) {
        this.header = header;
        return this;
    }

    public OutputStream getHeaderStream() {
        return header;
    }

    /**
     * For compatibility reasons, activates/disables all the security restriction features.
     *
     * @deprecated
     *      Control individual features.
     */
    @Deprecated
    public ChannelBuilder withRestricted(boolean  restricted) {
        withArbitraryCallableAllowed(!restricted);
        withRemoteClassLoadingAllowed(!restricted);
        return this;
    }

    /**
     * @deprecated
     *      Test individual features instead.
     */
    @Deprecated
    public boolean isRestricted() {
        return !isArbitraryCallableAllowed() || !isRemoteClassLoadingAllowed();
    }

    /**
     * If false, this channel only allows the other side to invoke methods on exported objects,
     * but not {@link Channel#call(Callable)} (and its family of methods.)
     *
     * The default is {@code true}.
     * @since TODO
     */
    public ChannelBuilder withArbitraryCallableAllowed(boolean b) {
        this.arbitraryCallableAllowed = b;
        return this;
    }

    /**
     * @since TODO
     */
    public boolean isArbitraryCallableAllowed() {
        return arbitraryCallableAllowed;
    }

    /**
     * Controls whether or not this channel is willing to load classes from the other side.
     * The default is {@code true}.
     * @since TODO
     */
    public ChannelBuilder withRemoteClassLoadingAllowed(boolean b) {
        this.remoteClassLoadingAllowed = b;
        return this;
    }

    /**
     * @since TODO
     */
    public boolean isRemoteClassLoadingAllowed() {
        return remoteClassLoadingAllowed;
    }

    public ChannelBuilder withJarCache(JarCache jarCache) {
        this.jarCache = jarCache;
        return this;
    }

    public JarCache getJarCache() {
        return jarCache;
    }

    public ChannelBuilder with(CallableDecorator decorator) {
        this.decorators.add(decorator);
        return this;
    }

    public List<CallableDecorator> getDecorators() {
        return this.decorators;
    }

    /**
     * Convenience method to install {@link RoleChecker} that verifies against the fixed set of roles.
     * @since TODO
     */
    public ChannelBuilder withRoles(Role... roles) {
        return withRoles(Arrays.asList(roles));
    }

    /**
     * Convenience method to install {@link RoleChecker} that verifies against the fixed set of roles.
     * @since TODO
     */
    public ChannelBuilder withRoles(final Collection<? extends Role> actual) {
        return withRoleChecker(new RoleChecker() {
            @Override
            public void check(RoleSensitive subject, @Nonnull Collection<Role> expected) {
                if (!actual.containsAll(expected)) {
                    Collection<Role> c = new ArrayList<Role>(expected);
                    c.removeAll(actual);
                    throw new SecurityException("Unexpected role: " + c);
                }
            }
        });
    }

    /**
     * Installs another {@link RoleChecker}.
     * @since TODO
     */
    public ChannelBuilder withRoleChecker(final RoleChecker checker) {
        return with(new CallableDecorator() {
            @Override
            public <V, T extends Throwable> Callable<V, T> userRequest(Callable<V, T> op, Callable<V, T> stem) {
                try {
                    stem.checkRoles(checker);
                } catch (AbstractMethodError e) {
                    checker.check(stem, Role.UNKNOWN);// not implemented, assume 'unknown'
                }

                return stem;
            }
        });
    }

    /**
     * Sets the property.
     *
     * Properties are modifiable after {@link Channel} is created, but a property set
     * during channel building is guaranteed to be visible to the other side as soon
     * as the channel is established.
     * @since TODO
     */
    public ChannelBuilder withProperty(Object key, Object value) {
        properties.put(key,value);
        return this;
    }

    /**
     * @since TODO
     */
    public <T> ChannelBuilder withProperty(ChannelProperty<T> key, T value) {
        return withProperty((Object) key, value);
    }

    /**
     * @since TODO
     */
    public Map<Object,Object> getProperties() {
        return properties;
    }

    /**
     * Replaces the {@link ClassFilter} used by the channel.
     * By default, {@link ClassFilter#DEFAULT} is installed.
     *
     * @since 2.53
     */
    public ChannelBuilder withClassFilter(ClassFilter filter) {
        if (filter==null)   throw new IllegalArgumentException();
        this.filter = filter;
        return this;
    }

    /**
     * @since 2.53
     */
    public ClassFilter getClassFilter() {
        return this.filter;
    }

    /**
     * Performs a handshake over the communication channel and builds a {@link Channel}.
     *
     * @param is
     *      Stream connected to the remote peer. It's the caller's responsibility to do
     *      buffering on this stream, if that's necessary.
     * @param os
     *      Stream connected to the remote peer. It's the caller's responsibility to do
     *      buffering on this stream, if that's necessary.
     */
    public Channel build(InputStream is, OutputStream os) throws IOException {
        return new Channel(this,negotiate(is,os));
    }

    public Channel build(Socket s) throws IOException {
        // support half-close properly
        return build(new BufferedInputStream(SocketChannelStream.in(s)),
                    new BufferedOutputStream(SocketChannelStream.out(s)));
    }

    public Channel build(SocketChannel s) throws IOException {
        return build(
                SocketChannelStream.in(s),
                SocketChannelStream.out(s));
    }

    public Channel build(CommandTransport transport) throws IOException {
        return new Channel(this,transport);
    }

    /**
     * Performs hand-shaking and creates a {@link CommandTransport}.
     *
     * This is an implementation detail of ChannelBuilder and it's protected
     * just so that
     */
    protected CommandTransport negotiate(final InputStream is, final OutputStream os) throws IOException {
        // write the magic preamble.
        // certain communication channel, such as forking JVM via ssh,
        // may produce some garbage at the beginning (for example a remote machine
        // might print some warning before the program starts outputting its own data.)
        //
        // so use magic preamble and discard all the data up to that to improve robustness.

        LOGGER.log(Level.FINER, "Sending capability preamble: {0}", capability);
        capability.writePreamble(os);

        Mode mode = this.getMode();

        if(mode!= Mode.NEGOTIATE) {
            LOGGER.log(Level.FINER, "Sending mode preamble: {0}", mode);
            os.write(mode.preamble);
            os.flush();    // make sure that stream preamble is sent to the other end. avoids dead-lock
        } else {
            LOGGER.log(Level.FINER, "Awaiting mode preamble...");
        }

        {// read the input until we hit preamble
            Mode[] modes={Mode.BINARY,Mode.TEXT};
            byte[][] preambles = new byte[][]{Mode.BINARY.preamble, Mode.TEXT.preamble, Capability.PREAMBLE};
            int[] ptr=new int[3];
            Capability cap = new Capability(0); // remote capacity that we obtained. If we don't hear from remote, assume no capability

            while(true) {
                int ch = is.read();
                if(ch==-1)
                    throw new EOFException("unexpected stream termination");

                for(int i=0;i<preambles.length;i++) {
                    byte[] preamble = preambles[i];
                    if(preamble[ptr[i]]==ch) {
                        if(++ptr[i]==preamble.length) {
                            switch (i) {
                            case 0:
                            case 1:
                                LOGGER.log(Level.FINER, "Received mode preamble: {0}", modes[i]);
                                // transmission mode negotiation
                                if(mode==Mode.NEGOTIATE) {
                                    // now we know what the other side wants, so send the consistent preamble
                                    mode = modes[i];
                                    LOGGER.log(Level.FINER, "Sending agreed mode preamble: {0}", mode);
                                    os.write(mode.preamble);
                                    os.flush();    // make sure that stream preamble is sent to the other end. avoids dead-lock
                                } else {
                                    if(modes[i]!=mode)
                                        throw new IOException("Protocol negotiation failure");
                                }
                                LOGGER.log(Level.FINE, "Channel name {0} negotiated mode {1} with capability {2}",
                                        new Object[]{name, mode, cap});

                                return makeTransport(is, os, mode, cap);
                            case 2:
                                cap = Capability.read(is);
                                LOGGER.log(Level.FINER, "Received capability preamble: {0}", cap);
                                break;
                            default:
                                throw new IllegalStateException("Unexpected preamble byte #" + i + 
                                        ". Only " + preambles.length + " bytes are supported");
                            }
                            ptr[i]=0; // reset
                        }
                    } else {
                        // didn't match.
                        ptr[i]=0;
                    }
                }

                if(header!=null)
                    header.write(ch);
            }
        }
    }

    /**
     * Instantiate a transport.
     *
     * @param is
     *      The negotiated input stream that hides
     * @param os
     *      {@linkplain CommandTransport#getUnderlyingStream() the underlying stream}.
     * @param mode
     *      The mode to create the transport in.
     * @param cap
     *      Capabilities of the other side, as determined during the handshaking.
     */
    protected CommandTransport makeTransport(InputStream is, OutputStream os, Mode mode, Capability cap) throws IOException {
        FlightRecorderInputStream fis = new FlightRecorderInputStream(is);

        if (cap.supportsChunking())
            return new ChunkedCommandTransport(cap, mode.wrap(fis), mode.wrap(os), os);
        else {
            ObjectOutputStream oos = new ObjectOutputStream(mode.wrap(os));
            oos.flush();    // make sure that stream preamble is sent to the other end. avoids dead-lock

            return new ClassicCommandTransport(
                    new ObjectInputStreamEx(mode.wrap(fis),getBaseLoader(),getClassFilter()),
                    oos,fis,os,cap);
        }
    }
}
