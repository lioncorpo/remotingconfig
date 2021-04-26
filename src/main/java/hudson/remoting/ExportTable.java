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

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.util.logging.Level.*;
import javax.annotation.CheckForNull;
import javax.annotation.CheckReturnValue;
import javax.annotation.meta.When;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Manages unique ID for exported objects, and allows look-up from IDs.
 *
 * @author Kohsuke Kawaguchi
 */
final class ExportTable {
    private final Map<Integer,Entry<?>> table = new HashMap<Integer,Entry<?>>();
    private final Map<Object,Entry<?>> reverse = new HashMap<Object,Entry<?>>();
    /**
     * {@link ExportList}s which are actively recording the current
     * export operation.
     */
    private final ThreadLocal<ExportList> lists = new ThreadLocal<ExportList>();

    /**
     * For diagnosing problems like JENKINS-20707 where we seem to be unexporting too eagerly,
     * record most recent unexported objects up to {@link #UNEXPORT_LOG_SIZE}
     *
     * New entries are added to the end, and older ones are removed from the beginning.
     */
    private final List<Entry<?>> unexportLog = new LinkedList<Entry<?>>();

    /**
     * Information about one exported object.
     */
    private final class Entry<T> {
        final int id;
        private Class<? super T>[] interfaces;
        private T object;
        /**
         * {@code object.getClass().getName()} kept around so that we can see the type even after it
         * gets deallocated.
         */
        @Nonnull
        private final String objectType;
        /**
         * Where was this object first exported?
         */
        @Nonnull
        final CreatedAt allocationTrace;
        /**
         * Where was this object unexported?
         */
        @CheckForNull
        ReleasedAt releaseTrace;
        /**
         * Current reference count.
         * Access to {@link ExportTable} is guarded by synchronized block,
         * so accessing this field requires no further synchronization.
         */
        private int referenceCount;

        /**
         * This field can be set programmatically to track reference counting
         */
        @CheckForNull
        private ReferenceCountRecorder recorder;

        Entry(@Nonnull T object, Class<? super T>... interfaces) {
            this.id = iota++;
            this.interfaces = interfaces.clone();
            this.object = object;
            this.objectType = object.getClass().getName();
            this.allocationTrace = new CreatedAt();

            table.put(id,this);
            reverse.put(object,this);
        }

        void addRef() {
            referenceCount++;
            if (recorder!=null)
                recorder.onAddRef(null);
        }

        /**
         * Increase reference count so much to effectively prevent de-allocation.
         * If the reference counting is correct, we just need to increment by one,
         * but this makes it safer even in case of some reference counting errors
         * (and we can still detect the problem by comparing the reference count with the magic value.
         */
        void pin() {
            // only add the magic constant if we are in the range Integer.MIN_VALUE < x < 0x2000000
            // this means that over-reference removal will still yield a referece above 0 and repeated pinning
            // will not yield a negative reference count.
            // e.g. if we had:
            //   init    -> 0x00000000;
            //   pin     -> 0x40000001;
            //   release -> 0x39999999;
            //   pin     -> 0x79999999;
            //   addRef  -> 0x80000000 => BOOM
            // By making the decision point half way, we give the maximum number of releases away from the pinned
            // magic value
            if (referenceCount<0x20000000)
                referenceCount += 0x40000000;
        }

        /**
         * Releases the entry.
         * @param callSite 
         *      Optional location that indicates where the actual call site was that triggered the activity,
         *      in case it was requested from the other side of the channel.
         */
        void release(@CheckForNull Throwable callSite) {
            if (recorder!=null)
                recorder.onRelease(callSite);

            if(--referenceCount==0) {
                table.remove(id);
                reverse.remove(object);

                object = null;
                releaseTrace = new ReleasedAt(callSite);

                unexportLog.add(this);
                while (unexportLog.size()>UNEXPORT_LOG_SIZE)
                    unexportLog.remove(0);
            }
        }

        private String interfaceNames() {
            StringBuilder buf = new StringBuilder(10 + getInterfaces().length * 128);
            String sep = "[";
            for (Class<? super T> clazz: getInterfaces()) {
                buf.append(sep).append(clazz.getName());
                sep = ", ";
            }
            buf.append("]");
            return buf.toString();
        }

        /**
         * Dumps the contents of the entry.
         */
        void dump(PrintWriter w) throws IOException {
            w.printf("#%d (ref.%d) : object=%s type=%s interfaces=%s%n", id, referenceCount, object, objectType, interfaceNames());
            allocationTrace.printStackTrace(w);
            if (releaseTrace!=null) {
                releaseTrace.printStackTrace(w);
            }
            if (recorder!=null) {
                recorder.dump(w);
            }
        }

        String dump() {
            try {
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                dump(pw);
                pw.close();
                return sw.toString();
            } catch (IOException e) {
                throw new Error(e);   // impossible
            }
        }

        synchronized Class<? super T>[] getInterfaces() {
            return interfaces;
        }

        synchronized void setInterfaces(Class<? super T>[] interfaces) {
            this.interfaces = interfaces;
        }

        synchronized void addInterface(Class<? super T> clazz) {
            for (Class<? super T> c: interfaces) {
                if (c.equals(clazz)) return;
            }
            Class<? super T>[] replacement = new Class[interfaces.length+1];
            System.arraycopy(interfaces, 0, replacement, 0, interfaces.length);
            replacement[interfaces.length] = clazz;
            interfaces = replacement;
        }

    }

    static class Source extends Exception {
        protected final long timestamp = System.currentTimeMillis();

        /**
         * @param callSite
         *      Optional location that indicates where the actual call site was that triggered the activity,
         *      in case it was requested from the other side of the channel.
         */
        @SuppressWarnings("ResultOfMethodCallIgnored")
        Source(@CheckForNull Throwable callSite) {
            super(callSite);
            updateOurStackTraceCache();
        }
        
        // TODO: We export the objects frequently, The current approach ALWAYS leads
        // to creation of two Stacktrace arrays in the memory: the original and the cloned one
        // Throwable API. Throwable API allows to workaround it only by using a heavy printStackTrace() method.
        // Approach #1: Maybe a manual implementation of getOurStackTrace() and local storage is preferable.
        // Approach #2: Consider disabling this logic by default
        /**
         * Update the internal stacktrace cache.
         * Forces the computation of the stack trace in a Java friendly data structure,
         * so that the call stack can be seen from the heap dump after the fact.
         * @return Cloned version of the inner cache.
         */
        @CheckReturnValue(when = When.NEVER)
        protected final StackTraceElement[] updateOurStackTraceCache() {
            return getStackTrace();
        }
    }

    static class CreatedAt extends Source {
        CreatedAt() {
            super(null);
        }

        @Override
        public String toString() {
            return "  Created at "+new Date(timestamp);
        }
    }

    static class ReleasedAt extends Source {
        ReleasedAt(@CheckForNull Throwable callSite) {
            super(callSite);
        }

        @Override
        public String toString() {
            return "  Released at "+new Date(timestamp);
        }
    }

    /**
     * Captures the list of export, so that they can be unexported later.
     * This is tied to a particular thread, so it only records operations
     * on the current thread.
     * The class is not serializable.
     */
    @Restricted(NoExternalUse.class)
    @SuppressFBWarnings(value = "SE_BAD_FIELD_INNER_CLASS",
            justification = "ExportList is supposed to be serializable as ArrayList, but it is not. "
                          + "The issue is ignored since the class does not belong to the public API")
    public final class ExportList extends ArrayList<Entry> {
        private final ExportList old;
        private ExportList() {
            old=lists.get();
            lists.set(this);
        }
        void release(Throwable callSite) {
            synchronized(ExportTable.this) {
                for (Entry e : this)
                    e.release(callSite);
            }
        }
        void stopRecording() {
            lists.set(old);
        }

        private static final long serialVersionUID = 1L;    // we don't actually serialize this class but just to shutup FindBugs
    }

    /**
     * Unique ID generator.
     */
    private int iota = 1;

    /**
     * Starts the recording of the export operations
     * and returns the list that captures the result.
     *
     * @see ExportList#stopRecording()
     */
    ExportList startRecording() {
        ExportList el = new ExportList();
        lists.set(el);
        return el;
    }

    boolean isRecording() {
        return lists.get()!=null;
    }

    /**
     * Exports the given object.
     *
     * <p>
     * Until the object is {@link #unexport(Object,Throwable) unexported}, it will
     * not be subject to GC.
     *
     * @return
     *      The assigned 'object ID'. If the object is already exported,
     *      it will return the ID already assigned to it.
     *      {@code 0} if the input parameter is {@code null}.
     * @param clazz Class of the object
     * @param t Class instance
     */
    synchronized <T> int export(@Nonnull Class<T> clazz, @CheckForNull T t) {
        return export(clazz, t,true);
    }

    /**
     * Exports the given object.
     * @param clazz Class of the object
     * @param t Object to be exported
     * @param notifyListener
     *      If false, listener will not be notified. This is used to
     * @return
     *      The assigned 'object ID'. If the object is already exported,
     *      it will return the ID already assigned to it.
     *      {@code 0} if the input parameter is {@code null}.
     */
    synchronized <T> int export(@Nonnull Class<T> clazz, @CheckForNull T t, boolean notifyListener) {
        if(t==null)    return 0;   // bootstrap classloader

        Entry e = reverse.get(t);
        if (e == null) {
            e = new Entry<T>(t, clazz);
        } else {
            e.addInterface(clazz);
        }
        e.addRef();

        if(notifyListener) {
            ExportList l = lists.get();
            if(l!=null) l.add(e);
        }

        return e.id;
    }

    /*package*/ synchronized void pin(@Nonnull Object t) {
        Entry e = reverse.get(t);
        if(e!=null)
            e.pin();
    }

    /**
     * Retrieves object by id.
     * @param id Object ID
     * @return Object
     * @throws ExecutionException The requested ID cannot be found.
     *      The root cause will be diagnosed by {@link #diagnoseInvalidObjectId(int)}.
     */
    @Nonnull
    synchronized Object get(int id) throws ExecutionException {
        Entry e = table.get(id);
        if(e!=null) return e.object;

        throw diagnoseInvalidObjectId(id);
    }

    /**
     * Retrieves object by id.
     * @param oid Object ID
     * @return Object or {@code null} if the ID is missing in the {@link ExportTable}.
     * @since TODO
     */
    @CheckForNull
    synchronized Object getOrNull(int oid) {
        Entry<?> e = table.get(oid);
        if(e!=null) return e.object;

        return null;
    }
    
    @Nonnull
    synchronized Class[] type(int id) throws ExecutionException {
        Entry e = table.get(id);
        if(e!=null) return e.getInterfaces();

        throw diagnoseInvalidObjectId(id);
    }

    /**
     * Propagate a channel termination error to all the exported objects.
     * 
     * <p>
     * Exported {@link Pipe}s are vulnerable to infinite blocking
     * when the channel is lost and the sender side is cut off. The reader
     * end will not see that the writer has disappeared.
     * 
     * @param e Termination error
     * 
     */
    void abort(@CheckForNull Throwable e) {
        List<Entry<?>> values;
        synchronized (this) {
            values = new ArrayList<Entry<?>>(table.values());
        }
        for (Entry<?> v : values) {
            if (v.object instanceof ErrorPropagatingOutputStream) {
                try {
                    ((ErrorPropagatingOutputStream)v.object).error(e);
                } catch (Throwable x) {
                    LOGGER.log(INFO, "Failed to propagate a channel termination error",x);
                }
            }
        }
        
        // clear the references to allow exported objects to get GCed.
        // don't bother putting them into #unexportLog because this channel
        // is forever closed.
        synchronized (this) {
            table.clear();
            reverse.clear();
        }
    }

    /**
     * Creates a diagnostic exception for Invalid object id.
     * @param id Object ID
     * @return Exception to be thrown
     */
    @Nonnull
    private synchronized ExecutionException diagnoseInvalidObjectId(int id) {
        Exception cause=null;

        if (!unexportLog.isEmpty()) {
            for (Entry e : unexportLog) {
                if (e.id==id)
                    cause = new Exception("Object was recently deallocated\n"+Util.indent(e.dump()), e.releaseTrace);
            }
            if (cause==null)
                cause = new Exception("Object appears to be deallocated at lease before "+
                    new Date(unexportLog.get(0).releaseTrace.timestamp));
        }

        return new ExecutionException("Invalid object ID "+id+" iota="+iota, cause);
    }

    /**
     * Removes the exported object from the table.
     * @param t Object to be unexported. {@code null} instances will be ignored.
     * @param callSite Stacktrace of the invocation source 
     */
    synchronized void unexport(@CheckForNull Object t, Throwable callSite) {
        if(t==null)     return;
        Entry e = reverse.get(t);
        if(e==null) {
            LOGGER.log(SEVERE, "Trying to unexport an object that's not exported: "+t);
            return;
        }
        e.release(callSite);
    }

    /**
     * Removes the exported object for the specified oid from the table.
     * Logs error if the object has been already unexported.
     */
    void unexportByOid(Integer oid, Throwable callSite) {
        unexportByOid(oid, callSite, true);
    }
    
    /**
     * Removes the exported object for the specified oid from the table.
     * @param oid Object ID. If {@code null} the method will do nothing.
     * @param callSite Unexport command caller
     * @param severeErrorIfMissing Consider missing object as {@link #SEVERE} error. {@link #FINE} otherwise
     * @since TODO
     */
    synchronized void unexportByOid(@CheckForNull Integer oid, @CheckForNull Throwable callSite, boolean severeErrorIfMissing) {
        if(oid==null)     return;
        Entry e = table.get(oid);
        if(e==null) {
            Level loggingLevel = severeErrorIfMissing ? SEVERE : FINE;
            LOGGER.log(loggingLevel, "Trying to unexport an object that's already unexported", diagnoseInvalidObjectId(oid));
            if (callSite!=null)
                LOGGER.log(loggingLevel, "2nd unexport attempt is here", callSite);
            return;
        }
        e.release(callSite);
    }

    /**
     * Dumps the contents of the table to a file.
     * @throws IOException Output error
     */
    synchronized void dump(@Nonnull PrintWriter w) throws IOException {
        for (Entry e : table.values()) {
            e.dump(w);
        }
    }

    /*package*/ synchronized  boolean isExported(Object o) {
        return reverse.containsKey(o);
    }

    /**
     * Defines number of entries to be stored in the unexport history.
     * @since 2.40
     */
    public static int UNEXPORT_LOG_SIZE = Integer.getInteger(ExportTable.class.getName()+".unexportLogSize",1024);

    private static final Logger LOGGER = Logger.getLogger(ExportTable.class.getName());
}
