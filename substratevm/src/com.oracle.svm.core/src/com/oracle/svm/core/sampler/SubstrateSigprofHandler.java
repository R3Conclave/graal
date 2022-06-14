/*
 * Copyright (c) 2022, 2022, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package com.oracle.svm.core.sampler;

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.List;

import org.graalvm.compiler.api.replacements.Fold;
import org.graalvm.compiler.options.Option;
import org.graalvm.nativeimage.ImageInfo;
import org.graalvm.nativeimage.ImageSingletons;
import org.graalvm.nativeimage.Isolate;
import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;
import org.graalvm.nativeimage.StackValue;
import org.graalvm.nativeimage.c.function.CodePointer;
import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.word.Pointer;
import org.graalvm.word.UnsignedWord;

import com.oracle.svm.core.IsolateListenerSupport;
import com.oracle.svm.core.RegisterDumper;
import com.oracle.svm.core.SubstrateOptions;
import com.oracle.svm.core.VMInspectionOptions;
import com.oracle.svm.core.annotate.AutomaticFeature;
import com.oracle.svm.core.Uninterruptible;
import com.oracle.svm.core.code.CodeInfo;
import com.oracle.svm.core.code.CodeInfoAccess;
import com.oracle.svm.core.code.CodeInfoTable;
import com.oracle.svm.core.graal.nodes.WriteCurrentVMThreadNode;
import com.oracle.svm.core.graal.nodes.WriteHeapBaseNode;
import com.oracle.svm.core.heap.VMOperationInfos;
import com.oracle.svm.core.jdk.RuntimeSupport;
import com.oracle.svm.core.jdk.management.ManagementFeature;
import com.oracle.svm.core.jdk.management.SubstrateThreadMXBean;
import com.oracle.svm.core.jfr.JfrFeature;
import com.oracle.svm.core.option.RuntimeOptionKey;
import com.oracle.svm.core.stack.JavaFrameAnchor;
import com.oracle.svm.core.stack.JavaFrameAnchors;
import com.oracle.svm.core.stack.JavaStackWalker;
import com.oracle.svm.core.thread.JavaVMOperation;
import com.oracle.svm.core.thread.ThreadListenerFeature;
import com.oracle.svm.core.thread.ThreadListenerSupport;
import com.oracle.svm.core.thread.VMThreads;
import com.oracle.svm.core.util.VMError;

@AutomaticFeature
@SuppressWarnings("unused")
class SubstrateSigprofHandlerFeature implements Feature {

    @Override
    public boolean isInConfiguration(IsInConfigurationAccess access) {
        return VMInspectionOptions.AllowVMInspection.getValue() && SubstrateSigprofHandler.isProfilingSupported() && ImageInfo.isExecutable();
    }

    @Override
    public List<Class<? extends Feature>> getRequiredFeatures() {
        return Arrays.asList(ThreadListenerFeature.class, JfrFeature.class, ManagementFeature.class);
    }

    @Override
    public void beforeAnalysis(BeforeAnalysisAccess access) {
        if (!ImageSingletons.contains(SubstrateSigprofHandler.class)) {
            /* No sigprof handler. */
            return;
        }
        VMError.guarantee(ImageSingletons.contains(RegisterDumper.class));

        /* Create stack visitor. */
        ImageSingletons.add(SamplerStackWalkVisitor.class, new SamplerStackWalkVisitor());

        /* Add listeners. */
        ThreadListenerSupport.get().register(new SamplerThreadLocal());
        IsolateListenerSupport.singleton().register(new SamplerIsolateLocal());

        /* Add startup hook. */
        RuntimeSupport.getRuntimeSupport().addStartupHook(new SubstrateSigprofHandlerStartupHook());
    }
}

final class SubstrateSigprofHandlerStartupHook implements RuntimeSupport.Hook {
    @Override
    public void execute(boolean isFirstIsolate) {
        if (isFirstIsolate) {
            SubstrateSigprofHandler.singleton().install();
        }
    }
}

/**
 * <p>
 * The core class of low overhead asynchronous sampling based <b>profiler</b>.
 * {@link SubstrateSigprofHandler} handles the periodic signal generated by the OS with a given
 * time-frequency. The asynchronous nature of the signal means that the OS could invoke the signal
 * handler at any time (could be during GC, VM operation, uninterruptible code) and that the signal
 * handler can only execute specific code i.e. the calls that are asynchronous signal safe.
 * </p>
 *
 * <p>
 * The signal handler is divided into three part: restore isolate, isolate-thread, stack and
 * instruction pointers, prepare everything necessary for stack walk, do a stack walk and write IPs
 * into buffer.
 * </p>
 * 
 * <p>
 * The signal handler is as a <b>producer</b>. On the other side of relation is
 * {@link com.oracle.svm.core.jfr.JfrRecorderThread} that is <b>consumer</b>. The
 * {@link SamplerBuffer} that we are using in this consumer-producer communication is allocated
 * eagerly, in a part of the heap that is not accessible via GC, and there will always be more
 * available buffers that threads.
 * </p>
 *
 * <p>
 * The communication between consumer and producer goes as follows:
 * <ul>
 * <li>Signal handler (producer): pops the buffer from the pool of available buffers (the buffer now
 * becomes thread-local), writes the IPs into buffer, if the buffer is full and moves it to a pool
 * with buffers that awaits processing.</li>
 * <li>Recorder thread (consumer): pops the buffer from the pool of full buffers, reconstructs the
 * stack walk based on IPs and pushes the buffer into pool of available buffers.</li>
 * </ul>
 * <b>NOTE:</b> The producer and the consumer are always accessing different buffers.
 * </p>
 *
 * <p>
 * In some rare cases, the profiling is impossible e.g. no available buffers in the pool, unknown IP
 * during stack walk, the thread holds the pool's lock when the signal arrives, etc.
 * </p>
 * 
 * @see SamplerSpinLock
 * @see SamplerBufferStack
 */
public abstract class SubstrateSigprofHandler {

    public static class Options {
        @Option(help = "Allow sampling-based profiling. Default: disabled in execution.")//
        static final RuntimeOptionKey<Boolean> SamplingBasedProfiling = new RuntimeOptionKey<>(Boolean.FALSE);
    }

    private boolean enabled;
    private final SamplerBufferStack availableBuffers;
    private final SamplerBufferStack fullBuffers;
    private SubstrateThreadMXBean threadMXBean;

    @Platforms(Platform.HOSTED_ONLY.class)
    protected SubstrateSigprofHandler() {
        this.availableBuffers = new SamplerBufferStack();
        this.fullBuffers = new SamplerBufferStack();
    }

    @Fold
    static SubstrateSigprofHandler singleton() {
        return ImageSingletons.lookup(SubstrateSigprofHandler.class);
    }

    @Fold
    static SamplerStackWalkVisitor visitor() {
        return ImageSingletons.lookup(SamplerStackWalkVisitor.class);
    }

    @Fold
    static boolean isProfilingSupported() {
        return Platform.includedIn(Platform.LINUX.class);
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    boolean isProfilingEnabled() {
        return enabled;
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    SamplerBufferStack availableBuffers() {
        return availableBuffers;
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    SamplerBufferStack fullBuffers() {
        return fullBuffers;
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    SubstrateThreadMXBean substrateThreadMXBean() {
        return threadMXBean;
    }

    /**
     * Installs the platform dependent sigprof handler.
     */
    void install() {
        if (Options.SamplingBasedProfiling.getValue()) {
            threadMXBean = (SubstrateThreadMXBean) ManagementFactory.getThreadMXBean();
            /* Call VM operation to initialize the sampler and the threads. */
            InitializeSamplerOperation initializeSamplerOperation = new InitializeSamplerOperation();
            initializeSamplerOperation.enqueue();

            /* After the VM operations finishes. Install handler and start profiling. */
            install0();
        }
    }

    protected abstract void install0();

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    protected abstract UnsignedWord createThreadLocalKey();

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    protected abstract void deleteThreadLocalKey(UnsignedWord key);

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    protected abstract void setThreadLocalKeyValue(UnsignedWord key, IsolateThread value);

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    protected abstract IsolateThread getThreadLocalKeyValue(UnsignedWord key);

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static boolean isIPInJavaCode(RegisterDumper.Context uContext) {
        Pointer ip = (Pointer) RegisterDumper.singleton().getIP(uContext);
        CodeInfo codeInfo = CodeInfoTable.getImageCodeInfo();
        Pointer codeStart = (Pointer) CodeInfoAccess.getCodeStart(codeInfo);
        UnsignedWord codeSize = CodeInfoAccess.getCodeSize(codeInfo);
        return ip.aboveOrEqual(codeStart) && ip.belowOrEqual(codeStart.add(codeSize));
    }

    @Uninterruptible(reason = "The method executes during signal handling.", callerMustBe = true)
    protected static void doUninterruptibleStackWalk(RegisterDumper.Context uContext) {
        CodePointer ip;
        Pointer sp;
        if (isIPInJavaCode(uContext)) {
            ip = (CodePointer) RegisterDumper.singleton().getIP(uContext);
            sp = (Pointer) RegisterDumper.singleton().getSP(uContext);
        } else {
            JavaFrameAnchor anchor = JavaFrameAnchors.getFrameAnchor();
            if (anchor.isNull()) {
                /*
                 * The anchor is still null if the function is interrupted during prologue. See:
                 * com.oracle.svm.core.graal.snippets.CFunctionSnippets.prologueSnippet
                 */
                return;
            }

            ip = anchor.getLastJavaIP();
            sp = anchor.getLastJavaSP();
            if (ip.isNull() || sp.isNull()) {
                /*
                 * It can happen that anchor is in list of all anchors, but its IP and SP are not
                 * filled yet.
                 */
                return;
            }
        }

        /* Initialize stack walk. */
        SamplerSampleWriterData data = StackValue.get(SamplerSampleWriterData.class);
        if (prepareStackWalk(data)) {
            /* Walk the stack. */
            if (JavaStackWalker.walkCurrentThread(sp, ip, visitor())) {
                SamplerSampleWriter.commit(data);
            }
        }
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static boolean prepareStackWalk(SamplerSampleWriterData data) {
        if (singleton().availableBuffers().isLockedByCurrentThread() || singleton().fullBuffers().isLockedByCurrentThread()) {
            /*
             * The current thread already holds the stack lock, so we can't access it. It's way
             * better to lose one sample, then potentially the whole buffer.
             */
            SamplerThreadLocal.increaseMissedSamples();
            return false;
        }

        SamplerBuffer buffer = SamplerThreadLocal.getThreadLocalBuffer();
        if (buffer.isNull()) {
            /* Pop first free buffer from the pool. */
            buffer = singleton().availableBuffers().popBuffer();
            if (buffer.isNull()) {
                /* No available buffers on the pool. Fallback! */
                SamplerThreadLocal.increaseMissedSamples();
                return false;
            }
            SamplerThreadLocal.setThreadLocalBuffer(buffer);
        }

        /* Initialize the buffer. */
        data.setSamplerBuffer(buffer);
        data.setStartPos(buffer.getPos());
        data.setCurrentPos(buffer.getPos());
        data.setEndPos(SamplerBufferAccess.getDataEnd(buffer));
        SamplerThreadLocal.setWriterData(data);
        return true;
    }

    /** Called from the platform dependent sigprof handler to enter isolate. */
    @Uninterruptible(reason = "The method executes during signal handling.", callerMustBe = true)
    protected static boolean tryEnterIsolate() {
        if (SubstrateOptions.SpawnIsolates.getValue()) {
            Isolate isolate = SamplerIsolateLocal.getIsolate();
            if (isolate.isNull()) {
                /* It may happen that the initial isolate exited. */
                return false;
            }

            /* Write isolate pointer (heap base) into register. */
            WriteHeapBaseNode.writeCurrentVMHeapBase(isolate);
        }

        /* We are keeping reference to isolate thread inside OS thread local area. */
        if (SubstrateOptions.MultiThreaded.getValue()) {
            if (!SamplerIsolateLocal.isKeySet()) {
                /* The key becomes invalid during initial isolate teardown. */
                return false;
            }
            UnsignedWord key = SamplerIsolateLocal.getKey();
            IsolateThread thread = singleton().getThreadLocalKeyValue(key);
            if (thread.isNull()) {
                /* Thread is not yet initialized or already detached from isolate. */
                return false;
            }

            /* Write isolate thread pointer into register. */
            WriteCurrentVMThreadNode.writeCurrentVMThread(thread);
        }
        return true;
    }

    private class InitializeSamplerOperation extends JavaVMOperation {

        protected InitializeSamplerOperation() {
            super(VMOperationInfos.get(InitializeSamplerOperation.class, "Initialize Sampler", SystemEffect.SAFEPOINT));
        }

        @Override
        protected void operate() {
            initialize();
        }

        /**
         * We need to ensure that all threads are properly initialized at a moment when we start a
         * profiling.
         */
        @Uninterruptible(reason = "Prevent pollution of the current thread's thread local JFR buffer.")
        private void initialize() {
            /*
             * Iterate all over all thread and initialize the thread-local storage of each thread.
             */
            for (IsolateThread thread = VMThreads.firstThread(); thread.isNonNull(); thread = VMThreads.nextThread(thread)) {
                SamplerThreadLocal.initialize(thread);
            }
            enabled = true;
        }
    }
}
