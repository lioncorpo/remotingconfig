/*
 * The MIT License
 * 
 * Copyright (c) 2004-2009, Sun Microsystems, Inc., Kohsuke Kawaguchi
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
package hudson.remoting;

import hudson.remoting.RemoteClassLoader.IClassLoader;
import hudson.remoting.ExportTable.ExportList;
import hudson.remoting.RemoteInvocationHandler.RPCRequest;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.io.NotSerializableException;
import java.io.ObjectInputStream;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;

/**
 * {@link Request} that can take {@link Callable} whose actual implementation
 * may not be known to the remote system in advance.
 *
 * <p>
 * This code assumes that the {@link Callable} object and all reachable code
 * are loaded by a single classloader.
 *
 * @author Kohsuke Kawaguchi
 */
final class UserRequest<RSP,EXC extends Throwable> extends Request<UserResponse<RSP,EXC>,EXC> {

    private final byte[] request;
    
    @Nonnull
    private final IClassLoader classLoaderProxy;
    private final String toString;
    /**
     * Objects exported by the request. This value will remain local
     * and won't be sent over to the remote side.
     */
    private transient final ExportList exports;

    /**
     * Creates a user request to be executed on the remote side.
     * @param local Channel, for which the request should be executed
     * @param c Command to be executed
     * @throws IOException The command cannot be serialized
     */
    public UserRequest(Channel local, Callable<?,EXC> c) throws IOException {
        this.toString = c.toString();
        if (local.isClosingOrClosed()) {
            Throwable createdAtValue = createdAt;
            if (createdAtValue == null) {
                // If Command API changes, the cause may be null here (e.g. if it stops recording cause by default)
                createdAtValue = new IllegalStateException("Command is created for the channel being interrupted");
            }
            throw new ChannelClosedException("Cannot create UserRequest for channel " + local + 
                    ". The channel is closed or being closed.", createdAtValue);
        }
        
        
        // Before serializing anything, check that we actually have a classloader for it
        final ClassLoader cl = getClassLoader(c);
        if (cl == null) {
            // If we cannot determine classloader on the local side, there is no sense to continue the request, because the proxy object won't be created
            // It will cause failure in UserRequest#perform()
            throw new IOException("Cannot determine classloader for the command " + toString);
        }
        
        // Serialize the command to the channel
        exports = local.startExportRecording();
        try {
            request = serialize(c,local);
        } finally {
            exports.stopRecording();
        }

        this.classLoaderProxy = RemoteClassLoader.export(cl, local);
    }

    @Override
    public void checkIfCanBeExecutedOnChannel(Channel channel) throws IOException {
        // Default check for all requests
        super.checkIfCanBeExecutedOnChannel(channel);
        
        // We also do not want to run UserRequests when the channel is being closed
        if (channel.isClosingOrClosed()) {
            throw new ChannelClosedException("The request cannot be executed on channel " + channel + ". "
                    + "The channel is closing down or has closed down", channel.getCloseRequestCause());
        }
    }
    
    /**
     * Retrieves classloader for the callable.
     * For {@link DelegatingCallable} the method will try to retrieve a classloader specified there.
     * If it is not available, a classloader from the class will be tried.
     * If it is not available as well, {@link ClassLoader#getSystemClassLoader()} will be used
     * @param c Callable
     * @return Classloader from the callable. May be {@code null} if all attempts to retrieve the classloader return {@code null}.
     */
    @CheckForNull
    /*package*/ static ClassLoader getClassLoader(@Nonnull Callable<?,?> c) {
    	ClassLoader result = null;
        
    	if(c instanceof DelegatingCallable) {
        	result =((DelegatingCallable)c).getClassLoader();
        }
        if (result == null) {
        	result = c.getClass().getClassLoader();
        }
        
        if (result == null) {
        	result = ClassLoader.getSystemClassLoader();
        }
        
        return result;
    }

    private static boolean workaroundDone = false;
    protected UserResponse<RSP,EXC> perform(Channel channel) throws EXC {
        try {
            ClassLoader cl = channel.importedClassLoaders.get(classLoaderProxy);

            // Allow forcibly load of a class, allows to workaround:
            // @See        https://issues.jenkins-ci.org/browse/JENKINS-19445
            // @Related    https://issues.tmatesoft.com/issue/SGT-451
            // @Since 2.4
            final String clazz = System.getProperty(RemoteClassLoader.class.getName() + ".force", null);
            if ( clazz != null && !workaroundDone) {
                // Optimistic logging set.
                String eventMsg = "Loaded";
                Level logLevel = Level.INFO;
                // java.lang classes can only be instantiated by the bootstrap Classloader.
                // Guarantees that *all* threads with whatever Classloader in use, have the
                // same mutex instance:    an intance of java.lang.Class<java.lang.Object>
                synchronized(java.lang.Object.class)
                {
                    workaroundDone = true;
                    try {
                        final Class<?> loaded = Class.forName( clazz, true, cl );
                    } catch (final ClassNotFoundException cnfe) {
                        // not big deal, elevate log to warning and swallow exception
                        eventMsg = "Couldn't find";
                        logLevel = Level.WARNING;
                    }
                }
                final Logger logger = Logger.getLogger(RemoteClassLoader.class.getName());
                if( logger.isLoggable(logLevel) )
                {
                    logger.log(logLevel, "%s class '%s' using classloader: %s", new String[]{ eventMsg, clazz, cl.toString()} );
                }
            }

            RSP r = null;
            Channel oldc = Channel.setCurrent(channel);
            try {
                Object o;
                try {
                    o = deserialize(channel,request,cl);
                } catch (ClassNotFoundException e) {
                    throw new ClassNotFoundException("Failed to deserialize the Callable object. Perhaps you needed to implement DelegatingCallable?",e);
                } catch (RuntimeException e) {
                    // if the error is during deserialization, throw it in one of the types Channel.call will
                    // capture its call site stack trace. See 
                    throw new Error("Failed to deserialize the Callable object.",e);
                }

                Callable<RSP,EXC> callable = (Callable<RSP,EXC>)o;
                if(!channel.isArbitraryCallableAllowed() && !(callable instanceof RPCRequest))
                    // if we allow restricted channel to execute arbitrary Callable, the remote JVM can pick up many existing
                    // Callable implementations (such as ones in Hudson's FilePath) and do quite a lot. So restrict that.
                    // OTOH, we need to allow RPCRequest so that method invocations on exported objects will go through.
                    throw new SecurityException("Execution of "+callable.toString()+" is prohibited because the channel is restricted");

                callable = channel.decorators.wrapUserRequest(callable);

                ClassLoader old = Thread.currentThread().getContextClassLoader();
                Thread.currentThread().setContextClassLoader(cl);
                // execute the service
                try {
                    r = callable.call();
                } finally {
                    Thread.currentThread().setContextClassLoader(old);
                }
            } finally {
                Channel.setCurrent(oldc);
            }

            return new UserResponse<RSP,EXC>(serialize(r,channel),false);
        } catch (Throwable e) {
            // propagate this to the calling process
            try {
                byte[] response;
                try {
                    response = _serialize(e, channel);
                } catch (NotSerializableException x) {
                    // perhaps the thrown runtime exception is of type we can't handle
                    response = serialize(new ProxyException(e), channel);
                }
                return new UserResponse<RSP,EXC>(response,true);
            } catch (IOException x) {
                // throw it as a lower-level exception
                throw (EXC)x;
            }
        }
    }

    private byte[] _serialize(Object o, final Channel channel) throws IOException {
        Channel old = Channel.setCurrent(channel);
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ObjectOutputStream oos;
            if (channel.remoteCapability.supportsMultiClassLoaderRPC())
                oos = new MultiClassLoaderSerializer.Output(channel,baos);
            else
                oos = new ObjectOutputStream(baos);

            oos.writeObject(o);
            return baos.toByteArray();
        } finally {
            Channel.setCurrent(old);
        }
    }

    private byte[] serialize(Object o, Channel localChannel) throws IOException {
        try {
            return _serialize(o,localChannel);
        } catch( NotSerializableException e ) {
            IOException x = new IOException("Unable to serialize " + o);
            x.initCause(e);
            throw x;
        }
    }

    /*package*/ static Object deserialize(final Channel channel, byte[] data, ClassLoader defaultClassLoader) throws IOException, ClassNotFoundException {
        ByteArrayInputStream in = new ByteArrayInputStream(data);

        ObjectInputStream ois;
        if (channel.remoteCapability.supportsMultiClassLoaderRPC()) {
            // this code is coupled with the ObjectOutputStream subtype above
            ois = new MultiClassLoaderSerializer.Input(channel, in);
        } else {
            ois = new ObjectInputStreamEx(in, defaultClassLoader, channel.classFilter);
        }
        return ois.readObject();
    }

    public void releaseExports() {
        releaseExports(null);
    }

    /*package*/ void releaseExports(Throwable callSite) {
        exports.release(callSite);
    }

    public String toString() {
        return "UserRequest:"+toString;
    }

    private static final long serialVersionUID = 1L;
}

final class UserResponse<RSP,EXC extends Throwable> implements Serializable {
    private final byte[] response;
    private final boolean isException;

    public UserResponse(byte[] response, boolean isException) {
        this.response = response;
        this.isException = isException;
    }

    /**
     * Deserializes the response byte stream into an object.
     */
    public RSP retrieve(Channel channel, ClassLoader cl) throws IOException, ClassNotFoundException, EXC {
        Channel old = Channel.setCurrent(channel);
        try {
            Object o = UserRequest.deserialize(channel,response,cl);

            if(isException) {
                channel.attachCallSiteStackTrace((Throwable)o);
                throw (EXC) o;
            } else
                return (RSP) o;
        } finally {
            Channel.setCurrent(old);
        }
    }

    private static final long serialVersionUID = 1L;
}
