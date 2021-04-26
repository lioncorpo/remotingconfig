package hudson.remoting;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.Serializable;

/**
 * @author Kohsuke Kawaguchi
 */
public class PipeWriterTest extends RmiTestBase implements Serializable, PipeWriterTestChecker {
    /**
     * {@link OutputStream} that is slow to act.
     */
    transient SlowOutputStream slow = new SlowOutputStream();
    RemoteOutputStream ros = new RemoteOutputStream(slow);

    /**
     * Proxy that can be used from the other side to verify the state.
     */
    PipeWriterTestChecker checker;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        checker = channel.export(PipeWriterTestChecker.class, this, false);
    }

    /**
     * Base test case for the response / IO coordination.
     */
    abstract class ResponseIoCoordCallable extends CallableBase<Object,Exception> {
        public Object call() throws Exception {
            long start = System.currentTimeMillis();
            System.out.println("touch");
            touch();
            assertTrue(System.currentTimeMillis()-start<1000); // write() itself shouldn't block

            class CheckerThread extends Thread {
                Throwable death;
                @Override
                public void run() {
                    try {
                        checker.assertSlowStreamNotTouched();
                    } catch (Throwable t) {
                        death = t;
                    }
                }
            }

            // if we call back from another thread, we should be able to see
            // that the I/O operation hasn't completed.
            // note that if we run the code from the same thread,
            // that call back will synchronize against the earlier I/O.
            System.out.println("taking a look from another thread");
            CheckerThread t = new CheckerThread();
            t.start();
            t.join();
            if (t.death!=null)
                throw new AssertionError(t.death);

            System.out.println("returning");
            return null;
        }

        /**
         * This is where the actual I/O happens.
         */
        abstract void touch() throws IOException;
    }

    /**
     * Verifies that I/O that happens during closure execution and return of closure is
     * coordinated.
     */
    public void testResponseIoCoord() throws Exception {
        channel.call(new ResponseIoCoordCallable() {
            void touch() throws IOException {
                ros.write(0);
            }
        });
        // but I/O should be complete before the call returns.
        assertTrue(slow.written);
    }

    /**
     * Ditto for {@link OutputStream#flush()}
     */
    public void testResponseIoCoordFlush() throws Exception {
        channel.call(new ResponseIoCoordCallable() {
            void touch() throws IOException {
                ros.flush();
            }
        });
        assertTrue(slow.flushed);
    }

    /**
     * Ditto for {@link OutputStream#close()}
     */
    public void testResponseIoCoordClose() throws Exception {
        channel.call(new ResponseIoCoordCallable() {
            void touch() throws IOException {
                ros.close();
            }
        });
        assertTrue(slow.closed);
    }


    /**
     * Base test case for the request / IO coordination.
     */
    abstract class RequestIoCoordCallable extends CallableBase<Object,Exception> {
        public Object call() throws IOException {
            long start = System.currentTimeMillis();
            System.out.println("touch");
            touch();
            System.out.println("verify");
            assertTrue(System.currentTimeMillis()-start<1000); // write() itself shouldn't block
            checker.assertSlowStreamTouched();  // but this call should
            System.out.println("end");
            return null;
        }

        /**
         * This is where the actual I/O happens.
         */
        abstract void touch() throws IOException;
    }

    public void testRequestIoCoord() throws Exception {
        channel.call(new RequestIoCoordCallable() {
            void touch() throws IOException {
                ros.write(0);
            }
        });
        assertSlowStreamTouched();
    }

    public void testRequestIoCoordFlush() throws Exception {
        channel.call(new RequestIoCoordCallable() {
            void touch() throws IOException {
                ros.flush();
            }
        });
        assertSlowStreamTouched();
    }

    public void testRequestIoCoordClose() throws Exception {
        channel.call(new RequestIoCoordCallable() {
            void touch() throws IOException {
                ros.close();
            }
        });
        assertSlowStreamTouched();
    }

    public void assertSlowStreamNotTouched() {
        assertFalse(slow.closed);
        assertFalse(slow.flushed);
        assertFalse(slow.written);
    }

    public void assertSlowStreamTouched() {
        assertTrue(slow.closed || slow.flushed || slow.written);
    }

    /**
     * Induces delay.
     */
    class SlowOutputStream extends OutputStream {
        boolean closed,flushed,written;

        @Override
        public void write(int b) throws IOException {
            slow();
            written = true;
        }

        @Override
        public void close() throws IOException {
            slow();
            closed = true;
        }

        @Override
        public void flush() throws IOException {
            slow();
            flushed = true;
        }

        private void slow() throws InterruptedIOException {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                throw new InterruptedIOException();
            }
        }
    }
}
