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

import edu.umd.cs.findbugs.annotations.Nullable;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.remoting.Channel.Mode;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.Socket;
import java.net.URL;
import java.nio.file.Path;
import java.security.AccessController;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.annotation.concurrent.NotThreadSafe;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import org.jenkinsci.remoting.engine.Jnlp4ConnectionState;
import org.jenkinsci.remoting.engine.JnlpAgentEndpoint;
import org.jenkinsci.remoting.engine.JnlpAgentEndpointResolver;
import org.jenkinsci.remoting.engine.JnlpConnectionState;
import org.jenkinsci.remoting.engine.JnlpConnectionStateListener;
import org.jenkinsci.remoting.engine.JnlpProtocolHandler;
import org.jenkinsci.remoting.engine.JnlpProtocolHandlerFactory;
import org.jenkinsci.remoting.engine.WorkDirManager;
import org.jenkinsci.remoting.protocol.IOHub;
import org.jenkinsci.remoting.protocol.cert.BlindTrustX509ExtendedTrustManager;
import org.jenkinsci.remoting.protocol.cert.DelegatingX509ExtendedTrustManager;
import org.jenkinsci.remoting.protocol.cert.PublicKeyMatchingX509ExtendedTrustManager;
import org.jenkinsci.remoting.protocol.impl.ConnectionRefusalException;
import org.jenkinsci.remoting.util.KeyUtils;

/**
 * Slave agent engine that proactively connects to Jenkins master.
 *
 * @author Kohsuke Kawaguchi
 */
@NotThreadSafe // the fields in this class should not be modified by multiple threads concurrently
public class Engine extends Thread {
    /**
     * Thread pool that sets {@link #CURRENT}.
     */
    private final ExecutorService executor = Executors.newCachedThreadPool(new ThreadFactory() {
        private final ThreadFactory defaultFactory = Executors.defaultThreadFactory();
        public Thread newThread(final Runnable r) {
            Thread t = defaultFactory.newThread(new Runnable() {
                public void run() {
                    CURRENT.set(Engine.this);
                    r.run();
                }
            });
            t.setDaemon(true);
            return t;
        }
    });

    /**
     * @deprecated
     *      Use {@link #events}.
     */
    @Deprecated
    public final EngineListener listener;

    private final EngineListenerSplitter events = new EngineListenerSplitter();

    /**
     * To make Jenkins more graceful against user error,
     * JNLP agent can try to connect to multiple possible Jenkins URLs.
     * This field specifies those candidate URLs, such as
     * "http://foo.bar/jenkins/".
     */
    private List<URL> candidateUrls;
    /**
     * The list of {@link X509Certificate} instances to trust when connecting to any of the {@link #candidateUrls}
     * or {@code null} to use the JVM default trust store.
     */
    private List<X509Certificate> candidateCertificates;

    /**
     * URL that points to Jenkins's tcp slave agent listener, like <tt>http://myhost/hudson/</tt>
     *
     * <p>
     * This value is determined from {@link #candidateUrls} after a successful connection.
     * Note that this URL <b>DOES NOT</b> have "tcpSlaveAgentListener" in it.
     */
    @CheckForNull
    private URL hudsonUrl;

    private final String secretKey;
    public final String slaveName;
    private String credentials;
	private String proxyCredentials = System.getProperty("proxyCredentials");

    /**
     * See Main#tunnel in the jnlp-agent module for the details.
     */
    private String tunnel;

    private boolean noReconnect;

    /**
     * Determines whether the socket will have {@link Socket#setKeepAlive(boolean)} set or not.
     *
     * @since 2.62.1
     */
    private boolean keepAlive = true;

    
    
    
    @CheckForNull
    private JarCache jarCache = null;

    /**
     * Specifies a destination for the agent log.
     * If specified, this option overrides the default destination within {@link #workDir}.
     * If both this options and {@link #workDir} is not set, the log will not be generated.
     * @since 3.8
     */
    @CheckForNull
    private Path agentLog;
    
    /**
     * Specified location of the property file with JUL settings.
     * @since 3.8
     */
    @CheckForNull
    private Path loggingConfigFilePath = null;
    
    /**
     * Specifies a default working directory of the remoting instance.
     * If specified, this directory will be used to store logs, JAR cache, etc.
     * <p>
     * In order to retain compatibility, the option is disabled by default.
     * <p>
     * Jenkins specifics: This working directory is expected to be equal to the agent root specified in Jenkins configuration.
     * @since 3.8
     */
    @CheckForNull
    public Path workDir = null;

    /**
     * Specifies a directory within {@link #workDir}, which stores all the remoting-internal files.
     * <p>
     * This option is not expected to be used frequently, but it allows remoting users to specify a custom
     * storage directory if the default {@code remoting} directory is consumed by other stuff.
     * @since 3.8
     */
    @Nonnull
    public String internalDir = WorkDirManager.DirType.INTERNAL_DIR.getDefaultLocation();

    /**
     * Fail the initialization if the workDir or internalDir are missing.
     * This option presumes that the workspace structure gets initialized previously in order to ensure that we do not start up with a borked instance
     * (e.g. if a filesystem mount gets disconnected).
     * @since 3.8
     */
    @Nonnull
    public boolean failIfWorkDirIsMissing = WorkDirManager.DEFAULT_FAIL_IF_WORKDIR_IS_MISSING;

    private DelegatingX509ExtendedTrustManager agentTrustManager = new DelegatingX509ExtendedTrustManager(new BlindTrustX509ExtendedTrustManager());

    public Engine(EngineListener listener, List<URL> hudsonUrls, String secretKey, String slaveName) {
        this.listener = listener;
        this.events.add(listener);
        this.candidateUrls = hudsonUrls;
        this.secretKey = secretKey;
        this.slaveName = slaveName;
        if(candidateUrls.isEmpty())
            throw new IllegalArgumentException("No URLs given");
        setUncaughtExceptionHandler((t, e) -> {
            LOGGER.log(Level.SEVERE, "Uncaught exception in Engine thread " + t, e);
            interrupt();
        });
    }

    /**
     * Starts the engine.
     * The procedure initializes the working directory and all the required environment
     * @throws IOException Initialization error
     * @since 3.9
     */
    public synchronized void startEngine() throws IOException {
        startEngine(false);
    }
     
    /**
     * Starts engine.
     * @param dryRun If {@code true}, do not actually start the engine.
     *               This method can be used for testing startup logic.
     */
    /*package*/ void startEngine(boolean dryRun) throws IOException {
        @CheckForNull File jarCacheDirectory = null;
        
        // Prepare the working directory if required
        if (workDir != null) {
            final WorkDirManager workDirManager = WorkDirManager.getInstance();
            if (jarCache != null) {
                // Somebody has already specificed Jar Cache, hence we do not need it in the workspace.
                workDirManager.disable(WorkDirManager.DirType.JAR_CACHE_DIR);
            }
            
            if (loggingConfigFilePath != null) {
                workDirManager.setLoggingConfig(loggingConfigFilePath.toFile());
            }
            
            final Path path = workDirManager.initializeWorkDir(workDir.toFile(), internalDir, failIfWorkDirIsMissing);
            jarCacheDirectory = workDirManager.getLocation(WorkDirManager.DirType.JAR_CACHE_DIR);
            workDirManager.setupLogging(path, agentLog);
        } else if (jarCache == null) {
            LOGGER.log(Level.WARNING, "No Working Directory. Using the legacy JAR Cache location: {0}", JarCache.DEFAULT_NOWS_JAR_CACHE_LOCATION);
            jarCacheDirectory = JarCache.DEFAULT_NOWS_JAR_CACHE_LOCATION;
        }
        
        if (jarCache == null){
            if (jarCacheDirectory == null) {
                // Should never happen in the current code
                throw new IOException("Cannot find the JAR Cache location");
            }
            LOGGER.log(Level.FINE, "Using standard File System JAR Cache. Root Directory is {0}", jarCacheDirectory);
            try {
                jarCache = new FileSystemJarCache(jarCacheDirectory, true);
            } catch (IllegalArgumentException ex) {
                throw new IOException("Failed to initialize FileSystem JAR Cache in " + jarCacheDirectory, ex);
            }
        } else {
            LOGGER.log(Level.INFO, "Using custom JAR Cache: {0}", jarCache);
        }
        
        // Start the engine thread
        if (!dryRun) {
            this.start();
        }
    }

    /**
     * Configures custom JAR Cache location.
     * Starting from TODO, this option disables JAR Caching in the working directory.
     * @param jarCache JAR Cache to be used
     * @since 2.24
     */
    public void setJarCache(@Nonnull JarCache jarCache) {
        this.jarCache = jarCache;
    }
    
    /**
     * Sets path to the property file with JUL settings.
     * @param filePath JAR Cache to be used
     * @since 3.8
     */
    public void setLoggingConfigFile(@Nonnull Path filePath) {
        this.loggingConfigFilePath = filePath;
    }

    /**
     * Provides Jenkins URL if available.
     * @return Jenkins URL. May return {@code null} if the connection is not established or if the URL cannot be determined
     *         in the {@link JnlpAgentEndpointResolver}.
     */
    @CheckForNull
    public URL getHudsonUrl() {
        return hudsonUrl;
    }

    public void setTunnel(String tunnel) {
        this.tunnel = tunnel;
    }

    public void setCredentials(String creds) {
        this.credentials = creds;
    }

	public void setProxyCredentials(String proxyCredentials) {
		this.proxyCredentials = proxyCredentials;
	}

    public void setNoReconnect(boolean noReconnect) {
        this.noReconnect = noReconnect;
    }

    /**
     * Sets the destination for agent logs.
     * @param agentLog Path to the agent log.
     *      If {@code null}, the engine will pick the default behavior depending on the {@link #workDir} value
     * @since 3.8
     */
    public void setAgentLog(@CheckForNull Path agentLog) {
        this.agentLog = agentLog;
    }

    /**
     * Specified a path to the work directory.
     * @param workDir Path to the working directory of the remoting instance.
     *                {@code null} Disables the working directory.
     * @since 3.8
     */
    public void setWorkDir(@CheckForNull Path workDir) {
        this.workDir = workDir;
    }

    /**
     * Specifies name of the internal data directory within {@link #workDir}.
     * @param internalDir Directory name
     * @since 3.8
     */
    public void setInternalDir(@Nonnull String internalDir) {
        this.internalDir = internalDir;
    }

    /**
     * Sets up behavior if the workDir or internalDir are missing during the startup.
     * This option presumes that the workspace structure gets initialized previously in order to ensure that we do not start up with a borked instance
     * (e.g. if a filesystem mount gets disconnected).
     * @param failIfWorkDirIsMissing Flag
     * @since 3.8
     */
    public void setFailIfWorkDirIsMissing(boolean failIfWorkDirIsMissing) { this.failIfWorkDirIsMissing = failIfWorkDirIsMissing; }

    /**
     * Returns {@code true} if and only if the socket to the master will have {@link Socket#setKeepAlive(boolean)} set.
     *
     * @return {@code true} if and only if the socket to the master will have {@link Socket#setKeepAlive(boolean)} set.
     */
    public boolean isKeepAlive() {
        return keepAlive;
    }

    /**
     * Sets the {@link Socket#setKeepAlive(boolean)} to use for the connection to the master.
     *
     * @param keepAlive the {@link Socket#setKeepAlive(boolean)} to use for the connection to the master.
     */
    public void setKeepAlive(boolean keepAlive) {
        this.keepAlive = keepAlive;
    }

    public void setCandidateCertificates(List<X509Certificate> candidateCertificates) {
        this.candidateCertificates = candidateCertificates == null
                ? null
                : new ArrayList<X509Certificate>(candidateCertificates);
    }

    public void addCandidateCertificate(X509Certificate certificate) {
        if (candidateCertificates == null) {
            candidateCertificates = new ArrayList<X509Certificate>();
        }
        candidateCertificates.add(certificate);
    }

    public void addListener(EngineListener el) {
        events.add(el);
    }

    public void removeListener(EngineListener el) {
        events.remove(el);
    }

    @Override
    public void run() {
        // Create the engine
        try {
            IOHub hub = IOHub.create(executor);
            try {
                SSLContext context;
                // prepare our SSLContext
                try {
                    context = SSLContext.getInstance("TLS");
                } catch (NoSuchAlgorithmException e) {
                    throw new IllegalStateException("Java runtime specification requires support for TLS algorithm", e);
                }
                char[] password = "password".toCharArray();
                KeyStore store;
                try {
                    store = KeyStore.getInstance("JKS");
                } catch (KeyStoreException e) {
                    throw new IllegalStateException("Java runtime specification requires support for JKS key store", e);
                }
                try {
                    store.load(null, password);
                } catch (NoSuchAlgorithmException e) {
                    throw new IllegalStateException("Java runtime specification requires support for JKS key store", e);
                } catch (CertificateException e) {
                    throw new IllegalStateException("Empty keystore", e);
                }
                KeyManagerFactory kmf;
                try {
                    kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                } catch (NoSuchAlgorithmException e) {
                    throw new IllegalStateException("Java runtime specification requires support for default key manager", e);
                }
                try {
                    kmf.init(store, password);
                } catch (KeyStoreException e) {
                    throw new IllegalStateException(e);
                } catch (NoSuchAlgorithmException e) {
                    throw new IllegalStateException(e);
                } catch (UnrecoverableKeyException e) {
                    throw new IllegalStateException(e);
                }
                try {
                    context.init(kmf.getKeyManagers(), new TrustManager[]{agentTrustManager}, null);
                } catch (KeyManagementException e) {
                    events.error(e);
                    return;
                }
                innerRun(hub, context, executor);
            } finally {
                hub.close();
            }
        } catch (IOException e) {
            events.error(e);
        }
    }

    @SuppressWarnings({"ThrowableInstanceNeverThrown"})
    private void innerRun(IOHub hub, SSLContext context, ExecutorService service) {
        // Create the protocols that will be attempted to connect to the master.
        List<JnlpProtocolHandler> protocols = new JnlpProtocolHandlerFactory(service)
                .withIOHub(hub)
                .withSSLContext(context)
                .withPreferNonBlockingIO(false) // we only have one connection, prefer blocking I/O
                .handlers();
        final Map<String,String> headers = new HashMap<String,String>();
        headers.put(JnlpConnectionState.CLIENT_NAME_KEY, slaveName);
        headers.put(JnlpConnectionState.SECRET_KEY, secretKey);
        List<String> jenkinsUrls = new ArrayList<String>();
        for (URL url: candidateUrls) {
            jenkinsUrls.add(url.toExternalForm());
        }
        JnlpAgentEndpointResolver resolver = new JnlpAgentEndpointResolver(jenkinsUrls);
        resolver.setCredentials(credentials);
        resolver.setProxyCredentials(proxyCredentials);
        resolver.setTunnel(tunnel);
        try {
            resolver.setSslSocketFactory(getSSLSocketFactory());
        } catch (Exception e) {
            events.error(e);
        }


        try {
            boolean first = true;
            while(true) {
                if(first) {
                    first = false;
                } else {
                    if(noReconnect)
                        return; // exit
                }

                events.status("Locating server among " + candidateUrls);
                final JnlpAgentEndpoint endpoint;
                try {
                    endpoint = resolver.resolve();
                } catch (Exception e) {
                    events.error(e);
                    return;
                }
                if (endpoint == null) {
                    events.status("Could not resolve server among " + candidateUrls);
                    return;
                }
                hudsonUrl = endpoint.getServiceUrl();

                events.status(String.format("Agent discovery successful%n"
                        + "  Agent address: %s%n"
                        + "  Agent port:    %d%n"
                        + "  Identity:      %s",
                        endpoint.getHost(),
                        endpoint.getPort(),
                        KeyUtils.fingerprint(endpoint.getPublicKey()))
                );
                PublicKeyMatchingX509ExtendedTrustManager delegate = new PublicKeyMatchingX509ExtendedTrustManager();
                RSAPublicKey publicKey = endpoint.getPublicKey();
                if (publicKey != null) {
                    // This is so that JNLP4-connect will only connect if the public key matches
                    // if the public key is not published then JNLP4-connect will refuse to connect
                    delegate.add(publicKey);
                }
                agentTrustManager.setDelegate(delegate);

                events.status("Handshaking");
                Socket jnlpSocket = connect(endpoint);
                Channel channel = null;

                try {
                    // Try available protocols.
                    boolean triedAtLeastOneProtocol = false;
                    for (JnlpProtocolHandler<?> protocol : protocols) {
                        if (!protocol.isEnabled()) {
                            events.status("Protocol " + protocol.getName() + " is not enabled, skipping");
                            continue;
                        }
                        if (jnlpSocket == null) {
                            jnlpSocket = connect(endpoint);
                        }
                        if (!endpoint.isProtocolSupported(protocol.getName())) {
                            events.status("Server reports protocol " + protocol.getName() + " not supported, skipping");
                            continue;
                        }
                        triedAtLeastOneProtocol = true;
                        events.status("Trying protocol: " + protocol.getName());
                        try {
                            channel = protocol.connect(jnlpSocket, headers, new JnlpConnectionStateListener() {

                                @Override
                                public void beforeProperties(@Nonnull JnlpConnectionState event) {
                                    if (event instanceof Jnlp4ConnectionState) {
                                        X509Certificate certificate = ((Jnlp4ConnectionState) event).getCertificate();
                                        if (certificate != null) {
                                            String fingerprint = KeyUtils
                                                    .fingerprint(certificate.getPublicKey());
                                            if (!KeyUtils.equals(endpoint.getPublicKey(), certificate.getPublicKey())) {
                                                event.reject(new ConnectionRefusalException(
                                                        "Expecting identity " + fingerprint));
                                            }
                                            events.status("Remote identity confirmed: " + fingerprint);
                                        }
                                    }
                                }

                                @Override
                                public void afterProperties(@Nonnull JnlpConnectionState event) {
                                    event.approve();
                                }

                                @Override
                                public void beforeChannel(@Nonnull JnlpConnectionState event) {
                                    ChannelBuilder bldr = event.getChannelBuilder().withMode(Mode.BINARY);
                                    if (jarCache != null) {
                                        bldr.withJarCache(jarCache);
                                    }
                                }

                                @Override
                                public void afterChannel(@Nonnull JnlpConnectionState event) {
                                    // store the new cookie for next connection attempt
                                    String cookie = event.getProperty(JnlpConnectionState.COOKIE_KEY);
                                    if (cookie == null) {
                                        headers.remove(JnlpConnectionState.COOKIE_KEY);
                                    } else {
                                        headers.put(JnlpConnectionState.COOKIE_KEY, cookie);
                                    }
                                }
                            }).get();
                        } catch (IOException ioe) {
                            events.status("Protocol " + protocol.getName() + " failed to establish channel", ioe);
                        } catch (RuntimeException e) {
                            events.status("Protocol " + protocol.getName() + " encountered a runtime error", e);
                        } catch (Error e) {
                            events.status("Protocol " + protocol.getName() + " could not be completed due to an error",
                                    e);
                        } catch (Throwable e) {
                            events.status("Protocol " + protocol.getName() + " encountered an unexpected exception", e);
                        }

                        // On success do not try other protocols.
                        if (channel != null) {
                            break;
                        }

                        // On failure form a new connection.
                        jnlpSocket.close();
                        jnlpSocket = null;
                    }

                    // If no protocol worked.
                    if (channel == null) {
                        if (triedAtLeastOneProtocol) {
                            onConnectionRejected("None of the protocols were accepted");
                        } else {
                            onConnectionRejected("None of the protocols are enabled");
                            return; // exit
                        }
                        continue;
                    }

                    events.status("Connected");
                    channel.join();
                    events.status("Terminated");
                } finally {
                    if (jnlpSocket != null) {
                        try {
                            jnlpSocket.close();
                        } catch (IOException e) {
                            events.status("Failed to close socket", e);
                        }
                    }
                }
                if(noReconnect)
                    return; // exit

                events.onDisconnect();

                // try to connect back to the server every 10 secs.
                resolver.waitForReady();

                events.onReconnect();
            }
        } catch (Throwable e) {
            events.error(e);
        }
    }

    private void onConnectionRejected(String greeting) throws InterruptedException {
        events.error(new Exception("The server rejected the connection: " + greeting));
        Thread.sleep(10*1000);
    }

    /**
     * Connects to TCP slave host:port, with a few retries.
     * @param endpoint Connection endpoint
     * @throws IOException Connection failure or invalid parameter specification
     */
    private Socket connect(@Nonnull JnlpAgentEndpoint endpoint) throws IOException, InterruptedException {

        String msg = "Connecting to " + endpoint.getHost() + ':' + endpoint.getPort();
        events.status(msg);
        int retry = 1;
        while(true) {
            try {
                final Socket s = endpoint.open(SOCKET_TIMEOUT); // default is 30 mins. See PingThread for the ping interval
                s.setKeepAlive(keepAlive);
                return s;
            } catch (IOException e) {
                if(retry++>10) {
                    throw e;
                }
                Thread.sleep(1000*10);
                events.status(msg+" (retrying:"+retry+")",e);
            }
        }
    }

    /**
     * When invoked from within remoted {@link Callable} (that is,
     * from the thread that carries out the remote requests),
     * this method returns the {@link Engine} in which the remote operations
     * run.
     */
    public static Engine current() {
        return CURRENT.get();
    }

    private static final ThreadLocal<Engine> CURRENT = new ThreadLocal<Engine>();

    private static final Logger LOGGER = Logger.getLogger(Engine.class.getName());

    static KeyStore getCacertsKeyStore()
            throws PrivilegedActionException, KeyStoreException, NoSuchProviderException, CertificateException,
            NoSuchAlgorithmException, IOException {
        Map<String, String> properties = AccessController.doPrivileged(
                new PrivilegedExceptionAction<Map<String, String>>() {
                    public Map<String, String> run() throws Exception {
                        Map<String, String> result = new HashMap<String, String>();
                        result.put("trustStore", System.getProperty("javax.net.ssl.trustStore"));
                        result.put("javaHome", System.getProperty("java.home"));
                        result.put("trustStoreType",
                                System.getProperty("javax.net.ssl.trustStoreType", KeyStore.getDefaultType()));
                        result.put("trustStoreProvider", System.getProperty("javax.net.ssl.trustStoreProvider", ""));
                        result.put("trustStorePasswd", System.getProperty("javax.net.ssl.trustStorePassword", ""));
                        return result;
                    }
                });
        KeyStore keystore = null;

        FileInputStream trustStoreStream = null;
        try {
            String trustStore = properties.get("trustStore");
            if (!"NONE".equals(trustStore)) {
                File trustStoreFile;
                if (trustStore != null) {
                    trustStoreFile = new File(trustStore);
                    trustStoreStream = getFileInputStream(trustStoreFile);
                } else {
                    String javaHome = properties.get("javaHome");
                    trustStoreFile = new File(
                            javaHome + File.separator + "lib" + File.separator + "security" + File.separator
                                    + "jssecacerts");
                    if ((trustStoreStream = getFileInputStream(trustStoreFile)) == null) {
                        trustStoreFile = new File(
                                javaHome + File.separator + "lib" + File.separator + "security" + File.separator
                                        + "cacerts");
                        trustStoreStream = getFileInputStream(trustStoreFile);
                    }
                }

                if (trustStoreStream != null) {
                    trustStore = trustStoreFile.getPath();
                } else {
                    trustStore = "No File Available, using empty keystore.";
                }
            }

            String trustStoreType = properties.get("trustStoreType");
            String trustStoreProvider = properties.get("trustStoreProvider");
            LOGGER.log(Level.FINE, "trustStore is: {0}", trustStore);
            LOGGER.log(Level.FINE, "trustStore type is: {0}", trustStoreType);
            LOGGER.log(Level.FINE, "trustStore provider is: {0}", trustStoreProvider);

            if (trustStoreType.length() != 0) {
                LOGGER.log(Level.FINE, "init truststore");

                if (trustStoreProvider.length() == 0) {
                    keystore = KeyStore.getInstance(trustStoreType);
                } else {
                    keystore = KeyStore.getInstance(trustStoreType, trustStoreProvider);
                }

                char[] trustStorePasswdChars = null;
                String trustStorePasswd = properties.get("trustStorePasswd");
                if (trustStorePasswd.length() != 0) {
                    trustStorePasswdChars = trustStorePasswd.toCharArray();
                }

                keystore.load(trustStoreStream, trustStorePasswdChars);
                if (trustStorePasswdChars != null) {
                    for (int i = 0; i < trustStorePasswdChars.length; ++i) {
                        trustStorePasswdChars[i] = 0;
                    }
                }
            }
        } finally {
            if (trustStoreStream != null) {
                trustStoreStream.close();
            }
        }

        return keystore;
    }

    @CheckForNull
    private static FileInputStream getFileInputStream(final File file) throws PrivilegedActionException {
        return AccessController.doPrivileged(new PrivilegedExceptionAction<FileInputStream>() {
            public FileInputStream run() throws Exception {
                try {
                    return file.exists() ? new FileInputStream(file) : null;
                } catch (FileNotFoundException e) {
                    return null;
                }
            }
        });
    }

    private SSLSocketFactory getSSLSocketFactory()
            throws PrivilegedActionException, KeyStoreException, NoSuchProviderException, CertificateException,
            NoSuchAlgorithmException, IOException, KeyManagementException {
        SSLSocketFactory sslSocketFactory = null;
        if (candidateCertificates != null && !candidateCertificates.isEmpty()) {
            KeyStore keyStore = getCacertsKeyStore();
            // load the keystore
            keyStore.load(null, null);
            int i = 0;
            for (X509Certificate c : candidateCertificates) {
                keyStore.setCertificateEntry(String.format("alias-%d", i++), c);
            }
            // prepare the trust manager
            TrustManagerFactory trustManagerFactory =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagerFactory.init(keyStore);
            // prepare the SSL context
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, trustManagerFactory.getTrustManagers(), null);
            // now we have our custom socket factory
            sslSocketFactory = ctx.getSocketFactory();
        }
        return sslSocketFactory;
    }
    
    /**
     * Socket read timeout.
     * A {@link SocketInputStream#read()} call associated with underlying Socket will block for only this amount of time
     * @since 2.4
     */
    static final int SOCKET_TIMEOUT = Integer.getInteger(Engine.class.getName()+".socketTimeout",30*60*1000);
}
