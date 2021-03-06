/*
 * The MIT License
 * 
 * Copyright (c) 2004-2009, Sun Microsystems, Inc., Kohsuke Kawaguchi, Brian Westrich, Red Hat, Inc., Stephen Connolly, Tom Huybrechts
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
package hudson.model;

import hudson.model.Queue.Executable;
import hudson.Util;
import hudson.FilePath;
import jenkins.model.CauseOfInterruption;
import jenkins.model.CauseOfInterruption.UserInterruption;
import hudson.model.queue.Executables;
import hudson.model.queue.SubTask;
import hudson.model.queue.Tasks;
import hudson.model.queue.WorkUnit;
import hudson.util.TimeUnit2;
import hudson.util.InterceptingProxy;
import hudson.security.ACL;
import jenkins.model.InterruptedBuildAction;
import jenkins.model.Jenkins;
import org.acegisecurity.Authentication;
import org.acegisecurity.providers.anonymous.AnonymousAuthenticationToken;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.HttpResponses;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.kohsuke.stapler.export.ExportedBean;
import org.kohsuke.stapler.export.Exported;

import javax.servlet.ServletException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Vector;
import java.util.logging.Logger;
import java.util.logging.Level;
import java.lang.reflect.Method;

import static hudson.model.queue.Executables.*;
import static java.util.logging.Level.FINE;
import org.kohsuke.stapler.interceptor.RequirePOST;


/**
 * Thread that executes builds.
 *
 * @author Kohsuke Kawaguchi
 */
@ExportedBean
public class Executor extends Thread implements ModelObject {
    protected final Computer owner;
    private final Queue queue;

    private long startTime;
    /**
     * Used to track when a job was last executed.
     */
    private long finishTime;

    /**
     * Executor number that identifies it among other executors for the same {@link Computer}.
     */
    private int number;
    /**
     * {@link Queue.Executable} being executed right now, or null if the executor is idle.
     */
    private volatile Queue.Executable executable;

    private volatile WorkUnit workUnit;

    private Throwable causeOfDeath;

    private boolean induceDeath;

    /**
     * When the executor is interrupted, we allow the code that interrupted the thread to override the
     * result code it prefers.
     */
    private Result interruptStatus;

    /**
     * Cause of interruption. Access needs to be synchronized.
     */
    private final List<CauseOfInterruption> causes = new Vector<CauseOfInterruption>();

    public Executor(Computer owner, int n) {
        super("Executor #"+n+" for "+owner.getDisplayName());
        this.owner = owner;
        this.queue = Jenkins.getInstance().getQueue();
        this.number = n;
    }

    @Override
    public void interrupt() {
        interrupt(Result.ABORTED);
    }

    /**
     * Interrupt the execution,
     * but instead of marking the build as aborted, mark it as specified result.
     *
     * @since 1.417
     */
    public void interrupt(Result result) {
        Authentication a = Jenkins.getAuthentication();
        if(a instanceof AnonymousAuthenticationToken || a==ACL.SYSTEM)
            interrupt(result, new CauseOfInterruption[0]);
        else {
            // worth recording who did it
            // avoid using User.get() to avoid deadlock.
            interrupt(result, new UserInterruption(a.getName()));
        }
    }

    /**
     * Interrupt the execution. Mark the cause and the status accordingly.
     */
    public void interrupt(Result result, CauseOfInterruption... causes) {
        interruptStatus = result;
        synchronized (this.causes) {
            for (CauseOfInterruption c : causes) {
                if (!this.causes.contains(c))
                    this.causes.add(c);
            }
        }
        super.interrupt();
    }

    public Result abortResult() {
        Result r = interruptStatus;
        if (r==null)    r = Result.ABORTED; // this is when we programmatically throw InterruptedException instead of calling the interrupt method.
        return r;
    }

    /**
     * report cause of interruption and record it to the build, if available.
     *
     * @since 1.425
     */
    public void recordCauseOfInterruption(Run<?,?> build, TaskListener listener) {
        List<CauseOfInterruption> r;

        // atomically get&clear causes, and minimize the lock.
        synchronized (causes) {
            if (causes.isEmpty())   return;
            r = new ArrayList<CauseOfInterruption>(causes);
            causes.clear();
        }

        build.addAction(new InterruptedBuildAction(r));
        for (CauseOfInterruption c : r)
            c.print(listener);
    }

    @Override
    public void run() {
        // run as the system user. see ACL.SYSTEM for more discussion about why this is somewhat broken
        ACL.impersonate(ACL.SYSTEM);

        try {
            finishTime = System.currentTimeMillis();
            while(shouldRun()) {
                executable = null;
                workUnit = null;
                interruptStatus = null;
                causes.clear();

                synchronized(owner) {
                    if(owner.getNumExecutors()<owner.getExecutors().size()) {
                        // we've got too many executors.
                        owner.removeExecutor(this);
                        return;
                    }
                }

                // clear the interrupt flag as a precaution.
                // sometime an interrupt aborts a build but without clearing the flag.
                // see issue #1583
                if (Thread.interrupted())   continue;
                if (induceDeath)        throw new ThreadDeath();

                SubTask task;
                try {
                    // transition from idle to building.
                    // perform this state change as an atomic operation wrt other queue operations
                    synchronized (queue) {
                        workUnit = grabJob();
                        workUnit.setExecutor(this);
                        if (LOGGER.isLoggable(FINE))
                            LOGGER.log(FINE, getName()+" grabbed "+workUnit+" from queue");
                        task = workUnit.work;
                        startTime = System.currentTimeMillis();
                        executable = task.createExecutable();
                    }
                    if (LOGGER.isLoggable(FINE))
                        LOGGER.log(FINE, getName()+" is going to execute "+executable);
                } catch (IOException e) {
                    LOGGER.log(Level.SEVERE, "Executor threw an exception", e);
                    continue;
                } catch (InterruptedException e) {
                    LOGGER.log(FINE, getName()+" interrupted",e);
                    continue;
                }

                Throwable problems = null;
                final String threadName = getName();
                try {
                    workUnit.context.synchronizeStart();

                    if (executable instanceof Actionable) {
                        for (Action action: workUnit.context.actions) {
                            ((Actionable) executable).addAction(action);
                        }
                    }
                    setName(threadName+" : executing "+executable.toString());
                    if (LOGGER.isLoggable(FINE))
                        LOGGER.log(FINE, getName()+" is now executing "+executable);
                    queue.execute(executable, task);
                } catch (Throwable e) {
                    // for some reason the executor died. this is really
                    // a bug in the code, but we don't want the executor to die,
                    // so just leave some info and go on to build other things
                    LOGGER.log(Level.SEVERE, "Executor threw an exception", e);
                    workUnit.context.abort(e);
                    problems = e;
                } finally {
                    setName(threadName);
                    finishTime = System.currentTimeMillis();
                    if (LOGGER.isLoggable(FINE))
                        LOGGER.log(FINE, getName()+" completed "+executable+" in "+(finishTime-startTime)+"ms");
                    try {
                        workUnit.context.synchronizeEnd(executable,problems,finishTime - startTime);
                    } catch (InterruptedException e) {
                        workUnit.context.abort(e);
                        continue;
                    } finally {
                        workUnit.setExecutor(null);
                    }
                }
            }
        } catch(RuntimeException e) {
            causeOfDeath = e;
            throw e;
        } catch (Error e) {
            causeOfDeath = e;
            throw e;
        }
    }

    /**
     * For testing only. Simulate a fatal unexpected failure.
     */
    public void killHard() {
        induceDeath = true;
        interrupt();
    }

    /**
     * Returns true if we should keep going.
     */
    protected boolean shouldRun() {
        return Jenkins.getInstance() != null && !Jenkins.getInstance().isTerminating();
    }

    protected WorkUnit grabJob() throws InterruptedException {
        return queue.pop();
    }

    /**
     * Returns the current {@link Queue.Task} this executor is running.
     *
     * @return
     *      null if the executor is idle.
     */
    @Exported
    public Queue.Executable getCurrentExecutable() {
        return executable;
    }

    /**
     * Returns the current {@link WorkUnit} (of {@link #getCurrentExecutable() the current executable})
     * that this executor is running.
     *
     * @return
     *      null if the executor is idle.
     */
    @Exported
    public WorkUnit getCurrentWorkUnit() {
        return workUnit;
    }

    /**
     * If {@linkplain #getCurrentExecutable() current executable} is {@link AbstractBuild},
     * return the workspace that this executor is using, or null if the build hasn't gotten
     * to that point yet.
     */
    public FilePath getCurrentWorkspace() {
        Executable e = executable;
        if(e==null) return null;
        if (e instanceof AbstractBuild) {
            AbstractBuild ab = (AbstractBuild) e;
            return ab.getWorkspace();
        }
        return null;
    }

    /**
     * Same as {@link #getName()}.
     */
    public String getDisplayName() {
        return "Executor #"+getNumber();
    }

    /**
     * Gets the executor number that uniquely identifies it among
     * other {@link Executor}s for the same computer.
     *
     * @return
     *      a sequential number starting from 0.
     */
    @Exported
    public int getNumber() {
        return number;
    }

    /**
     * Returns true if this {@link Executor} is ready for action.
     */
    @Exported
    public boolean isIdle() {
        return executable==null;
    }

    /**
     * The opposite of {@link #isIdle()} &mdash; the executor is doing some work.
     */
    public boolean isBusy() {
        return executable!=null;
    }

    /**
     * If this thread dies unexpectedly, obtain the cause of the failure.
     *
     * @return null if the death is expected death or the thread is {@link #isAlive() still alive}.
     * @since 1.142
     */
    public Throwable getCauseOfDeath() {
        return causeOfDeath;
    }

    /**
     * Returns the progress of the current build in the number between 0-100.
     *
     * @return -1
     *      if it's impossible to estimate the progress.
     */
    @Exported
    public int getProgress() {
        Queue.Executable e = executable;
        if(e==null)     return -1;
        long d = Executables.getEstimatedDurationFor(e);
        if(d<0)         return -1;

        int num = (int)(getElapsedTime()*100/d);
        if(num>=100)    num=99;
        return num;
    }

    /**
     * Returns true if the current build is likely stuck.
     *
     * <p>
     * This is a heuristics based approach, but if the build is suspiciously taking for a long time,
     * this method returns true.
     */
    @Exported
    public boolean isLikelyStuck() {
        Queue.Executable e = executable;
        if(e==null)     return false;

        long elapsed = getElapsedTime();
        long d = Executables.getEstimatedDurationFor(e);
        if(d>=0) {
            // if it's taking 10 times longer than ETA, consider it stuck
            return d*10 < elapsed;
        } else {
            // if no ETA is available, a build taking longer than a day is considered stuck
            return TimeUnit2.MILLISECONDS.toHours(elapsed)>24;
        }
    }

    public long getElapsedTime() {
        return System.currentTimeMillis() - startTime;
    }

    /**
     * Returns the number of milli-seconds the currently executing job spent in the queue
     * waiting for an available executor. This excludes the quiet period time of the job.
     * @since 1.440
     */
    public long getTimeSpentInQueue() {
        return startTime - workUnit.context.item.buildableStartMilliseconds;
    }

    /**
     * Gets the string that says how long since this build has started.
     *
     * @return
     *      string like "3 minutes" "1 day" etc.
     */
    public String getTimestampString() {
        return Util.getPastTimeString(getElapsedTime());
    }

    /**
     * Computes a human-readable text that shows the expected remaining time
     * until the build completes.
     */
    public String getEstimatedRemainingTime() {
        Queue.Executable e = executable;
        if(e==null)     return Messages.Executor_NotAvailable();

        long d = Executables.getEstimatedDurationFor(e);
        if(d<0)         return Messages.Executor_NotAvailable();

        long eta = d-getElapsedTime();
        if(eta<=0)      return Messages.Executor_NotAvailable();

        return Util.getTimeSpanString(eta);
    }

    /**
     * The same as {@link #getEstimatedRemainingTime()} but return
     * it as a number of milli-seconds.
     */
    public long getEstimatedRemainingTimeMillis() {
        Queue.Executable e = executable;
        if(e==null)     return -1;

        long d = Executables.getEstimatedDurationFor(e);
        if(d<0)         return -1;

        long eta = d-getElapsedTime();
        if(eta<=0)      return -1;

        return eta;
    }

    /**
     * @deprecated as of 1.489
     *      Use {@link #doStop()}.
     */
    @RequirePOST
    public void doStop( StaplerRequest req, StaplerResponse rsp ) throws IOException, ServletException {
        doStop().generateResponse(req,rsp,this);
    }

    /**
     * Stops the current build.
     * 
     * @since 1.489
     */
    public HttpResponse doStop() {
        Queue.Executable e = executable;
        if(e!=null) {
            Tasks.getOwnerTaskOf(getParentOf(e)).checkAbortPermission();
            interrupt();
        }
        return HttpResponses.forwardToPreviousPage();
    }

    /**
     * Throws away this executor and get a new one.
     */
    public HttpResponse doYank() {
        Jenkins.getInstance().checkPermission(Jenkins.ADMINISTER);
        if (isAlive())
            throw new Failure("Can't yank a live executor");
        owner.removeExecutor(this);
        return HttpResponses.redirectViaContextPath("/");
    }

    /**
     * Checks if the current user has a permission to stop this build.
     */
    public boolean hasStopPermission() {
        Queue.Executable e = executable;
        return e!=null && Tasks.getOwnerTaskOf(getParentOf(e)).hasAbortPermission();
    }

    public Computer getOwner() {
        return owner;
    }

    /**
     * Returns when this executor started or should start being idle.
     */
    public long getIdleStartMilliseconds() {
        if (isIdle())
            return Math.max(finishTime, owner.getConnectTime());
        else {
            return Math.max(startTime + Math.max(0, Executables.getEstimatedDurationFor(executable)),
                    System.currentTimeMillis() + 15000);
        }
    }

    /**
     * Exposes the executor to the remote API.
     */
    public Api getApi() {
        return new Api(this);
    }

    /**
     * Creates a proxy object that executes the callee in the context that impersonates
     * this executor. Useful to export an object to a remote channel. 
     */
    public <T> T newImpersonatingProxy(Class<T> type, T core) {
        return new InterceptingProxy() {
            protected Object call(Object o, Method m, Object[] args) throws Throwable {
                final Executor old = IMPERSONATION.get();
                IMPERSONATION.set(Executor.this);
                try {
                    return m.invoke(o,args);
                } finally {
                    IMPERSONATION.set(old);
                }
            }
        }.wrap(type,core);
    }

    /**
     * Returns the executor of the current thread or null if current thread is not an executor.
     */
    public static Executor currentExecutor() {
        Thread t = Thread.currentThread();
        if (t instanceof Executor) return (Executor) t;
        return IMPERSONATION.get();
    }
    
    /**
     * Returns the estimated duration for the executable.
     * Protects against {@link AbstractMethodError}s if the {@link Executable} implementation
     * was compiled against Hudson < 1.383
     *
     * @deprecated as of 1.388
     *      Use {@link Executables#getEstimatedDurationFor(Executable)}
     */
    public static long getEstimatedDurationFor(Executable e) {
        return Executables.getEstimatedDurationFor(e);
    }

    /**
     * Mechanism to allow threads (in particular the channel request handling threads) to
     * run on behalf of {@link Executor}.
     */
    private static final ThreadLocal<Executor> IMPERSONATION = new ThreadLocal<Executor>();

    private static final Logger LOGGER = Logger.getLogger(Executor.class.getName());

}
