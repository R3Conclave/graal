/*
 * Copyright (c) 2015, 2021, Oracle and/or its affiliates. All rights reserved.
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
package org.graalvm.compiler.hotspot.meta;

import static jdk.vm.ci.services.Services.IS_IN_NATIVE_IMAGE;
import static org.graalvm.compiler.core.common.calc.Condition.EQ;
import static org.graalvm.compiler.core.common.calc.Condition.LT;
import static org.graalvm.compiler.core.common.calc.Condition.NE;
import static org.graalvm.compiler.hotspot.GraalHotSpotVMConfigAccess.JDK;
import static org.graalvm.compiler.hotspot.HotSpotBackend.AESCRYPT_DECRYPTBLOCK;
import static org.graalvm.compiler.hotspot.HotSpotBackend.AESCRYPT_ENCRYPTBLOCK;
import static org.graalvm.compiler.hotspot.HotSpotBackend.BASE64_ENCODE_BLOCK;
import static org.graalvm.compiler.hotspot.HotSpotBackend.CIPHER_BLOCK_CHAINING_DECRYPT_AESCRYPT;
import static org.graalvm.compiler.hotspot.HotSpotBackend.CIPHER_BLOCK_CHAINING_ENCRYPT_AESCRYPT;
import static org.graalvm.compiler.hotspot.HotSpotBackend.COUNTERMODE_IMPL_CRYPT;
import static org.graalvm.compiler.hotspot.HotSpotBackend.CRC_TABLE_LOCATION;
import static org.graalvm.compiler.hotspot.HotSpotBackend.GHASH_PROCESS_BLOCKS;
import static org.graalvm.compiler.hotspot.HotSpotBackend.UPDATE_BYTES_CRC32;
import static org.graalvm.compiler.hotspot.HotSpotBackend.UPDATE_BYTES_CRC32C;
import static org.graalvm.compiler.hotspot.meta.HotSpotGraphBuilderPlugins.AESCryptPlugin.AES_BLOCK_SIZE_IN_BYTES;
import static org.graalvm.compiler.java.BytecodeParserOptions.InlineDuringParsing;
import static org.graalvm.compiler.nodes.ConstantNode.forBoolean;

import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MutableCallSite;
import java.lang.invoke.VolatileCallSite;
import java.lang.ref.PhantomReference;
import java.lang.ref.Reference;
import java.lang.reflect.Array;
import java.lang.reflect.Modifier;
import java.math.BigInteger;
import java.util.zip.CRC32;

import org.graalvm.collections.Pair;
import org.graalvm.compiler.api.directives.GraalDirectives;
import org.graalvm.compiler.api.replacements.SnippetReflectionProvider;
import org.graalvm.compiler.core.common.calc.CanonicalCondition;
import org.graalvm.compiler.core.common.calc.Condition;
import org.graalvm.compiler.core.common.spi.ForeignCallDescriptor;
import org.graalvm.compiler.core.common.type.StampFactory;
import org.graalvm.compiler.core.common.type.TypeReference;
import org.graalvm.compiler.debug.GraalError;
import org.graalvm.compiler.hotspot.GraalHotSpotVMConfig;
import org.graalvm.compiler.hotspot.HotSpotBackend;
import org.graalvm.compiler.hotspot.HotSpotGraalRuntimeProvider;
import org.graalvm.compiler.hotspot.nodes.CurrentJavaThreadNode;
import org.graalvm.compiler.hotspot.nodes.HotSpotLoadReservedReferenceNode;
import org.graalvm.compiler.hotspot.nodes.HotSpotStoreReservedReferenceNode;
import org.graalvm.compiler.hotspot.replacements.BigIntegerSubstitutions;
import org.graalvm.compiler.hotspot.replacements.CallSiteTargetNode;
import org.graalvm.compiler.hotspot.replacements.DigestBaseSubstitutions;
import org.graalvm.compiler.hotspot.replacements.FastNotifyNode;
import org.graalvm.compiler.hotspot.replacements.HotSpotIdentityHashCodeNode;
import org.graalvm.compiler.hotspot.replacements.HotSpotInvocationPluginHelper;
import org.graalvm.compiler.hotspot.replacements.HotSpotReflectionGetCallerClassNode;
import org.graalvm.compiler.hotspot.replacements.HotSpotReplacementsUtil;
import org.graalvm.compiler.hotspot.replacements.HubGetClassNode;
import org.graalvm.compiler.hotspot.replacements.ObjectCloneNode;
import org.graalvm.compiler.hotspot.replacements.UnsafeCopyMemoryNode;
import org.graalvm.compiler.hotspot.word.HotSpotWordTypes;
import org.graalvm.compiler.nodes.ComputeObjectAddressNode;
import org.graalvm.compiler.nodes.ConstantNode;
import org.graalvm.compiler.nodes.FieldLocationIdentity;
import org.graalvm.compiler.nodes.LogicNode;
import org.graalvm.compiler.nodes.NodeView;
import org.graalvm.compiler.nodes.PiNode;
import org.graalvm.compiler.nodes.ValueNode;
import org.graalvm.compiler.nodes.calc.AddNode;
import org.graalvm.compiler.nodes.calc.AndNode;
import org.graalvm.compiler.nodes.calc.ConditionalNode;
import org.graalvm.compiler.nodes.calc.IntegerTestNode;
import org.graalvm.compiler.nodes.calc.IsNullNode;
import org.graalvm.compiler.nodes.calc.LeftShiftNode;
import org.graalvm.compiler.nodes.calc.ObjectEqualsNode;
import org.graalvm.compiler.nodes.calc.SignExtendNode;
import org.graalvm.compiler.nodes.calc.SubNode;
import org.graalvm.compiler.nodes.calc.UnsignedRightShiftNode;
import org.graalvm.compiler.nodes.calc.XorNode;
import org.graalvm.compiler.nodes.extended.ForeignCallNode;
import org.graalvm.compiler.nodes.extended.GuardingNode;
import org.graalvm.compiler.nodes.extended.JavaReadNode;
import org.graalvm.compiler.nodes.extended.RawLoadNode;
import org.graalvm.compiler.nodes.graphbuilderconf.ForeignCallPlugin;
import org.graalvm.compiler.nodes.graphbuilderconf.GeneratedPluginFactory;
import org.graalvm.compiler.nodes.graphbuilderconf.GraphBuilderConfiguration.Plugins;
import org.graalvm.compiler.nodes.graphbuilderconf.GraphBuilderContext;
import org.graalvm.compiler.nodes.graphbuilderconf.InvocationPlugin;
import org.graalvm.compiler.nodes.graphbuilderconf.InvocationPlugin.Receiver;
import org.graalvm.compiler.nodes.graphbuilderconf.InvocationPlugins;
import org.graalvm.compiler.nodes.graphbuilderconf.InvocationPlugins.Registration;
import org.graalvm.compiler.nodes.java.ArrayLengthNode;
import org.graalvm.compiler.nodes.java.DynamicNewArrayNode;
import org.graalvm.compiler.nodes.java.DynamicNewInstanceNode;
import org.graalvm.compiler.nodes.java.InstanceOfNode;
import org.graalvm.compiler.nodes.java.NewArrayNode;
import org.graalvm.compiler.nodes.memory.OnHeapMemoryAccess.BarrierType;
import org.graalvm.compiler.nodes.memory.address.AddressNode;
import org.graalvm.compiler.nodes.memory.address.OffsetAddressNode;
import org.graalvm.compiler.nodes.spi.Replacements;
import org.graalvm.compiler.nodes.util.GraphUtil;
import org.graalvm.compiler.options.OptionValues;
import org.graalvm.compiler.phases.tiers.CompilerConfiguration;
import org.graalvm.compiler.replacements.InlineDuringParsingPlugin;
import org.graalvm.compiler.replacements.InvocationPluginHelper;
import org.graalvm.compiler.replacements.MethodHandlePlugin;
import org.graalvm.compiler.replacements.NodeIntrinsificationProvider;
import org.graalvm.compiler.replacements.ReplacementsImpl;
import org.graalvm.compiler.replacements.StandardGraphBuilderPlugins;
import org.graalvm.compiler.replacements.arraycopy.ArrayCopyCallNode;
import org.graalvm.compiler.replacements.arraycopy.ArrayCopyForeignCalls;
import org.graalvm.compiler.replacements.arraycopy.ArrayCopySnippets;
import org.graalvm.compiler.replacements.nodes.MacroNode.MacroParams;
import org.graalvm.compiler.serviceprovider.GraalServices;
import org.graalvm.compiler.serviceprovider.JavaVersionUtil;
import org.graalvm.compiler.word.WordTypes;
import org.graalvm.word.LocationIdentity;

import jdk.vm.ci.code.TargetDescription;
import jdk.vm.ci.hotspot.VMIntrinsicMethod;
import jdk.vm.ci.meta.ConstantReflectionProvider;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.MetaAccessProvider;
import jdk.vm.ci.meta.ResolvedJavaField;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.meta.ResolvedJavaType;
import jdk.vm.ci.meta.UnresolvedJavaType;
import jdk.vm.ci.services.Services;
import sun.misc.Unsafe;

/**
 * Defines the {@link Plugins} used when running on HotSpot.
 */
public class HotSpotGraphBuilderPlugins {

    /**
     * Creates a {@link Plugins} object that should be used when running on HotSpot.
     */
    public static Plugins create(HotSpotGraalRuntimeProvider graalRuntime,
                    CompilerConfiguration compilerConfiguration,
                    GraalHotSpotVMConfig config,
                    HotSpotWordTypes wordTypes,
                    MetaAccessProvider metaAccess,
                    ConstantReflectionProvider constantReflection,
                    SnippetReflectionProvider snippetReflection,
                    HotSpotHostForeignCallsProvider foreignCalls,
                    ReplacementsImpl replacements,
                    OptionValues options,
                    TargetDescription target) {
        InvocationPlugins invocationPlugins = new HotSpotInvocationPlugins(graalRuntime, config, compilerConfiguration);

        Plugins plugins = new Plugins(invocationPlugins);
        plugins.appendNodePlugin(new HotSpotExceptionDispatchPlugin(config, wordTypes.getWordKind()));
        if (!IS_IN_NATIVE_IMAGE) {
            // In libgraal all word related operations have been fully processed so this is unneeded
            HotSpotWordOperationPlugin wordOperationPlugin = new HotSpotWordOperationPlugin(snippetReflection, wordTypes);
            HotSpotNodePlugin nodePlugin = new HotSpotNodePlugin(wordOperationPlugin);

            plugins.appendTypePlugin(nodePlugin);
            plugins.appendNodePlugin(nodePlugin);
        }
        plugins.appendNodePlugin(new MethodHandlePlugin(constantReflection.getMethodHandleAccess(), !config.supportsMethodHandleDeoptimizationEntry()));
        plugins.appendInlineInvokePlugin(replacements);
        if (InlineDuringParsing.getValue(options)) {
            plugins.appendInlineInvokePlugin(new InlineDuringParsingPlugin());
        }

        if (config.instanceKlassInitThreadOffset != -1) {
            plugins.setClassInitializationPlugin(new HotSpotJITClassInitializationPlugin());
        }

        invocationPlugins.defer(new Runnable() {

            @Override
            public void run() {
                registerObjectPlugins(invocationPlugins, config, replacements);
                registerClassPlugins(plugins, config, replacements);
                registerSystemPlugins(invocationPlugins);
                registerThreadPlugins(invocationPlugins, config, replacements);
                registerCallSitePlugins(invocationPlugins);
                registerReflectionPlugins(invocationPlugins, replacements, config);
                registerAESPlugins(invocationPlugins, config, replacements);
                registerCRC32Plugins(invocationPlugins, config, replacements);
                registerCRC32CPlugins(invocationPlugins, config, replacements);
                registerBigIntegerPlugins(invocationPlugins, config, replacements);
                registerSHAPlugins(invocationPlugins, config, replacements);
                registerGHASHPlugins(invocationPlugins, config, metaAccess);
                registerCounterModePlugins(invocationPlugins, config, replacements);
                registerBase64Plugins(invocationPlugins, config, metaAccess);
                registerUnsafePlugins(invocationPlugins, config, replacements);
                StandardGraphBuilderPlugins.registerInvocationPlugins(metaAccess, snippetReflection, invocationPlugins, replacements, true, false, true, graalRuntime.getHostProviders().getLowerer());
                registerArrayPlugins(invocationPlugins, replacements, config);
                registerStringPlugins(invocationPlugins, replacements, wordTypes, foreignCalls, config);
                registerArraysSupportPlugins(invocationPlugins, config, replacements);
                registerReferencePlugins(invocationPlugins, replacements);
                registerTrufflePlugins(invocationPlugins, wordTypes, config);
            }

        });
        if (!IS_IN_NATIVE_IMAGE) {
            // In libgraal all NodeIntrinsics been converted into special nodes so the plugins
            // aren't needed.
            NodeIntrinsificationProvider nodeIntrinsificationProvider = new NodeIntrinsificationProvider(metaAccess, snippetReflection, foreignCalls, wordTypes, target);
            invocationPlugins.defer(() -> {
                for (GeneratedPluginFactory factory : GraalServices.load(GeneratedPluginFactory.class)) {
                    factory.registerPlugins(invocationPlugins, nodeIntrinsificationProvider);
                }
            });
        }
        return plugins;
    }

    private static void registerTrufflePlugins(InvocationPlugins plugins, WordTypes wordTypes, GraalHotSpotVMConfig config) {
        if (config.jvmciReservedReference0Offset == -1) {
            // cannot install intrinsics without
            return;
        }

        InvocationPlugins.Registration tl = new InvocationPlugins.Registration(plugins, "org.graalvm.compiler.truffle.runtime.hotspot.HotSpotFastThreadLocal");
        tl.register1("get", InvocationPlugin.Receiver.class, new InvocationPlugin() {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver) {
                int jvmciReservedReference0Offset = config.jvmciReservedReference0Offset;
                GraalError.guarantee(jvmciReservedReference0Offset != -1, "jvmciReservedReference0Offset is not available but used.");
                b.addPush(JavaKind.Object, new HotSpotLoadReservedReferenceNode(b.getMetaAccess(), wordTypes, jvmciReservedReference0Offset));
                return true;
            }

        });
        tl.register2("set", InvocationPlugin.Receiver.class, Object[].class, new InvocationPlugin() {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver,
                            ValueNode value) {
                int jvmciReservedReference0Offset = config.jvmciReservedReference0Offset;
                GraalError.guarantee(jvmciReservedReference0Offset != -1, "jvmciReservedReference0Offset is not available but used.");
                b.add(new HotSpotStoreReservedReferenceNode(wordTypes, value, jvmciReservedReference0Offset));
                return true;
            }
        });

    }

    private static void registerObjectPlugins(InvocationPlugins plugins, GraalHotSpotVMConfig config, Replacements replacements) {
        Registration r = new Registration(plugins, Object.class, replacements);
        r.register1("clone", Receiver.class, new InvocationPlugin() {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver) {
                ValueNode object = receiver.get();
                b.addPush(JavaKind.Object, new ObjectCloneNode(MacroParams.of(b, targetMethod, object)));
                return true;
            }

            @Override
            public boolean inlineOnly() {
                return true;
            }
        });
        r.register1("hashCode", Receiver.class, new InvocationPlugin() {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver) {
                ValueNode object = receiver.get();
                b.addPush(JavaKind.Int, new HotSpotIdentityHashCodeNode(object, b.bci()));
                return true;
            }

            @Override
            public boolean inlineOnly() {
                return true;
            }
        });
        if (config.inlineNotify()) {
            r.register1("notify", Receiver.class, new InvocationPlugin() {
                @Override
                public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver) {
                    ValueNode object = receiver.get();
                    b.add(new FastNotifyNode(object, false, b.bci()));
                    return true;
                }

                @Override
                public boolean inlineOnly() {
                    return true;
                }
            });
        }
        if (config.inlineNotifyAll()) {
            r.register1("notifyAll", Receiver.class, new InvocationPlugin() {
                @Override
                public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver) {
                    ValueNode object = receiver.get();
                    b.add(new FastNotifyNode(object, true, b.bci()));
                    return true;
                }

                @Override
                public boolean inlineOnly() {
                    return true;
                }
            });
        }
    }

    private static void registerClassPlugins(Plugins plugins, GraalHotSpotVMConfig config, Replacements replacements) {
        Registration r = new Registration(plugins.getInvocationPlugins(), Class.class, replacements);

        r.register1("getModifiers", Receiver.class, new InvocationPlugin() {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver) {
                try (HotSpotInvocationPluginHelper helper = new HotSpotInvocationPluginHelper(b, targetMethod, config)) {
                    ValueNode klass = helper.readKlassFromClass(receiver.get());
                    // Primitive Class case
                    ValueNode nonNullKlass = helper.emitNullReturnGuard(klass, ConstantNode.forInt(Modifier.ABSTRACT | Modifier.FINAL | Modifier.PUBLIC), GraalDirectives.UNLIKELY_PROBABILITY);
                    // other return Klass::_modifier_flags
                    helper.emitFinalReturn(JavaKind.Int, helper.readKlassModifierFlags(nonNullKlass));
                }
                return true;
            }
        });
        r.register1("isInterface", Receiver.class, new InvocationPlugin() {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver) {
                try (HotSpotInvocationPluginHelper helper = new HotSpotInvocationPluginHelper(b, targetMethod, config)) {
                    ValueNode klass = helper.readKlassFromClass(receiver.get());
                    // Primitive Class case returns false
                    ValueNode klassNonNull = helper.emitNullReturnGuard(klass, ConstantNode.forBoolean(false), GraalDirectives.UNLIKELY_PROBABILITY);
                    ValueNode accessFlags = helper.readKlassAccessFlags(klassNonNull);
                    // return (Klass::_access_flags & Modifier.INTERFACE) == 0 ? false : true
                    LogicNode test = IntegerTestNode.create(accessFlags, ConstantNode.forInt(Modifier.INTERFACE), NodeView.DEFAULT);
                    helper.emitFinalReturn(JavaKind.Boolean, ConditionalNode.create(test, ConstantNode.forBoolean(false), ConstantNode.forBoolean(true), NodeView.DEFAULT));
                }
                return true;
            }
        });
        r.register1("isPrimitive", Receiver.class, new InvocationPlugin() {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver) {
                try (HotSpotInvocationPluginHelper helper = new HotSpotInvocationPluginHelper(b, targetMethod, config)) {
                    ValueNode klass = helper.readKlassFromClass(receiver.get());
                    LogicNode isNull = b.add(IsNullNode.create(klass));
                    b.addPush(JavaKind.Boolean, ConditionalNode.create(isNull, b.add(forBoolean(true)), b.add(forBoolean(false)), NodeView.DEFAULT));
                }
                return true;
            }
        });
        r.register1("getSuperclass", Receiver.class, new InvocationPlugin() {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver) {
                try (HotSpotInvocationPluginHelper helper = new HotSpotInvocationPluginHelper(b, targetMethod, config)) {
                    ValueNode klass = helper.readKlassFromClass(receiver.get());
                    ConstantNode nullValue = ConstantNode.defaultForKind(JavaKind.Object);

                    // Primitive Class case returns null
                    PiNode klassNonNull = helper.emitNullReturnGuard(klass, nullValue, GraalDirectives.UNLIKELY_PROBABILITY);

                    // if ((Klass::_access_flags & Modifer.INTERCAE) != 0) return null
                    ValueNode accessFlags = helper.readKlassAccessFlags(klassNonNull);
                    LogicNode test = IntegerTestNode.create(accessFlags, ConstantNode.forInt(Modifier.INTERFACE), NodeView.DEFAULT);
                    helper.emitReturnIfNot(test, nullValue, GraalDirectives.UNLIKELY_PROBABILITY);

                    // Handle array Class case
                    // if (Klass::_layout_helper < 0) return Object.class
                    ValueNode layoutHelper = helper.klassLayoutHelper(klassNonNull);
                    ResolvedJavaType objectType = b.getMetaAccess().lookupJavaType(Object.class);
                    ValueNode objectClass = ConstantNode.forConstant(b.getConstantReflection().asJavaClass(objectType), b.getMetaAccess());
                    helper.emitReturnIf(layoutHelper, Condition.LT, ConstantNode.forInt(config.klassLayoutHelperNeutralValue), objectClass,
                                    GraalDirectives.UNLIKELY_PROBABILITY);

                    // Read Klass::_super
                    ValueNode superKlass = helper.readKlassSuperKlass(klassNonNull);
                    // Return null if super is null
                    PiNode superKlassNonNull = helper.emitNullReturnGuard(superKlass, nullValue, GraalDirectives.UNLIKELY_PROBABILITY);
                    // Convert Klass to Class and return
                    helper.emitFinalReturn(JavaKind.Object, new HubGetClassNode(b.getMetaAccess(), superKlassNonNull));
                }
                return true;
            }
        });

        if (config.jvmAccIsHiddenClass != 0) {
            r.register1("isHidden", Receiver.class, new InvocationPlugin() {
                @Override
                public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver) {
                    try (HotSpotInvocationPluginHelper helper = new HotSpotInvocationPluginHelper(b, targetMethod, config)) {
                        ValueNode klass = helper.readKlassFromClass(receiver.get());
                        // Primitive Class case returns false
                        ValueNode nonNullKlass = helper.emitNullReturnGuard(klass, ConstantNode.forBoolean(false), GraalDirectives.UNLIKELY_PROBABILITY);
                        // return (Klass::_access_flags & jvmAccIsHiddenClass) == 0 ? false : true
                        ValueNode accessFlags = helper.readKlassAccessFlags(nonNullKlass);
                        LogicNode test = IntegerTestNode.create(accessFlags, ConstantNode.forInt(config.jvmAccIsHiddenClass), NodeView.DEFAULT);
                        helper.emitFinalReturn(JavaKind.Boolean, ConditionalNode.create(test, ConstantNode.forBoolean(false), ConstantNode.forBoolean(true), NodeView.DEFAULT));
                    }
                    return true;
                }
            });
        }

        if (config.getFieldOffset("ArrayKlass::_component_mirror", Integer.class, "oop", Integer.MAX_VALUE, JDK <= 8) != Integer.MAX_VALUE) {
            r.register1("getComponentType", Receiver.class, new InvocationPlugin() {
                @Override
                public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver) {
                    try (HotSpotInvocationPluginHelper helper = new HotSpotInvocationPluginHelper(b, targetMethod, config)) {
                        ValueNode klass = helper.readKlassFromClass(receiver.get());
                        // Primitive Class case returns null
                        final ConstantNode nullValue = ConstantNode.defaultForKind(JavaKind.Object);
                        ValueNode klassNonNull = helper.emitNullReturnGuard(klass, nullValue, GraalDirectives.UNLIKELY_PROBABILITY);
                        // Non-array case
                        // if (Klass::_layout_helper >= 0) return null
                        ValueNode layoutHelper = helper.klassLayoutHelper(klassNonNull);
                        GuardingNode guard = helper.emitReturnIf(layoutHelper, Condition.GE, ConstantNode.forInt(config.klassLayoutHelperNeutralValue), nullValue,
                                        GraalDirectives.UNLIKELY_PROBABILITY);
                        // Return ArrayKlass::_component_mirror
                        ValueNode componentMirror = helper.readArrayKlassComponentMirror(klassNonNull, guard);
                        helper.emitFinalReturn(JavaKind.Object, componentMirror);
                    }
                    return true;
                }
            });
        }
    }

    private static void registerCallSitePlugins(InvocationPlugins plugins) {
        InvocationPlugin plugin = new InvocationPlugin() {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver) {
                ValueNode callSite = receiver.get();
                ValueNode folded = CallSiteTargetNode.tryFold(GraphUtil.originalValue(callSite, true), b.getMetaAccess(), b.getAssumptions());
                if (folded != null) {
                    b.addPush(JavaKind.Object, folded);
                } else {
                    b.addPush(JavaKind.Object, new CallSiteTargetNode(MacroParams.of(b, targetMethod, callSite)));
                }
                return true;
            }

            @Override
            public boolean inlineOnly() {
                return true;
            }
        };
        plugins.register(plugin, ConstantCallSite.class, "getTarget", Receiver.class);
        plugins.register(plugin, MutableCallSite.class, "getTarget", Receiver.class);
        plugins.register(plugin, VolatileCallSite.class, "getTarget", Receiver.class);
    }

    private static void registerReflectionPlugins(InvocationPlugins plugins, Replacements replacements, GraalHotSpotVMConfig config) {
        Registration r = new Registration(plugins, reflectionClass, replacements);
        r.register0("getCallerClass", new InvocationPlugin() {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver) {
                b.addPush(JavaKind.Object, new HotSpotReflectionGetCallerClassNode(MacroParams.of(b, targetMethod)));
                return true;
            }

            @Override
            public boolean inlineOnly() {
                return true;
            }
        });
        r.register1("getClassAccessFlags", Class.class, new InvocationPlugin() {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode arg) {
                try (HotSpotInvocationPluginHelper helper = new HotSpotInvocationPluginHelper(b, targetMethod, config)) {
                    ValueNode klass = helper.readKlassFromClass(b.nullCheckedValue(arg));
                    // Primitive Class case
                    ValueNode klassNonNull = helper.emitNullReturnGuard(klass, ConstantNode.forInt(Modifier.ABSTRACT | Modifier.FINAL | Modifier.PUBLIC), GraalDirectives.UNLIKELY_PROBABILITY);
                    // Return (Klass::_access_flags & jvmAccWrittenFlags)
                    ValueNode accessFlags = helper.readKlassAccessFlags(klassNonNull);
                    helper.emitFinalReturn(JavaKind.Int, new AndNode(accessFlags, ConstantNode.forInt(config.jvmAccWrittenFlags)));
                }
                return true;
            }
        });
    }

    private static void registerUnsafePlugins(InvocationPlugins plugins, GraalHotSpotVMConfig config, Replacements replacements) {
        Registration r;
        if (JavaVersionUtil.JAVA_SPEC <= 8) {
            r = new Registration(plugins, Unsafe.class, replacements);
        } else {
            r = new Registration(plugins, "jdk.internal.misc.Unsafe", replacements);
        }
        r.register6(HotSpotBackend.copyMemoryName, Receiver.class, Object.class, long.class, Object.class,
                        long.class, long.class, new InvocationPlugin() {
                            @Override
                            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode srcBase, ValueNode srcOffset, ValueNode destBase,
                                            ValueNode destOffset, ValueNode bytes) {
                                b.add(new UnsafeCopyMemoryNode(config.doingUnsafeAccessOffset != Integer.MAX_VALUE, receiver.get(), srcBase, srcOffset, destBase, destOffset, bytes));
                                return true;
                            }
                        });

        r.register2("allocateInstance", Receiver.class, Class.class, new InvocationPlugin() {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver unsafe, ValueNode clazz) {
                /* Emits a null-check for the otherwise unused receiver. */
                unsafe.get();
                /*
                 * Note that the provided clazz might not be initialized. The HotSpot lowering
                 * snippet for DynamicNewInstanceNode performs the necessary class initialization
                 * check. Such a DynamicNewInstanceNode is also never constant folded to a
                 * NewInstanceNode.
                 */
                DynamicNewInstanceNode.createAndPush(b, clazz);
                return true;
            }
        });
    }

    private static void registerSystemPlugins(InvocationPlugins plugins) {
        Registration r = new Registration(plugins, System.class);
        r.register0("currentTimeMillis", new ForeignCallPlugin(HotSpotHostForeignCallsProvider.JAVA_TIME_MILLIS));
        r.register0("nanoTime", new ForeignCallPlugin(HotSpotHostForeignCallsProvider.JAVA_TIME_NANOS));
        r.register1("identityHashCode", Object.class, new InvocationPlugin() {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode object) {
                b.addPush(JavaKind.Int, new HotSpotIdentityHashCodeNode(object, b.bci()));
                return true;
            }

            @Override
            public boolean inlineOnly() {
                return true;
            }
        });
        ArrayCopySnippets.registerSystemArraycopyPlugin(r);
    }

    private static void registerArrayPlugins(InvocationPlugins plugins, Replacements replacements, GraalHotSpotVMConfig config) {
        Registration r = new Registration(plugins, Array.class, replacements);
        r.setAllowOverwrite(true);
        r.register2("newInstance", Class.class, int.class, new InvocationPlugin() {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode componentType, ValueNode length) {
                try (HotSpotInvocationPluginHelper helper = new HotSpotInvocationPluginHelper(b, targetMethod, config)) {
                    // If (componentType == null) then deopt
                    ValueNode nonNullComponentType = b.nullCheckedValue(componentType);
                    // Read Class.array_klass
                    ValueNode arrayClass = helper.loadArrayKlass(nonNullComponentType);
                    // Take the fallback path is the array klass is null
                    helper.doFallbackIf(IsNullNode.create(arrayClass), GraalDirectives.UNLIKELY_PROBABILITY);
                    // Otherwise perform the array allocation
                    helper.emitFinalReturn(JavaKind.Object, new DynamicNewArrayNode(nonNullComponentType, length,
                                    true));
                }
                return true;
            }
        });
    }

    private static void registerStringPlugins(InvocationPlugins plugins, Replacements replacements, WordTypes wordTypes, ArrayCopyForeignCalls foreignCalls, GraalHotSpotVMConfig vmConfig) {
        if (JavaVersionUtil.JAVA_SPEC > 8) {
            final Registration utf16r = new Registration(plugins, "java.lang.StringUTF16", replacements);
            utf16r.register3("toBytes", char[].class, int.class, int.class, new InvocationPlugin() {
                private static final int MAX_LENGTH = Integer.MAX_VALUE >> 1;

                @Override
                public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode value, ValueNode srcBegin, ValueNode length) {
                    try (HotSpotInvocationPluginHelper helper = new HotSpotInvocationPluginHelper(b, targetMethod, vmConfig)) {
                        helper.intrinsicRangeCheck(srcBegin, Condition.LT, ConstantNode.forInt(0));
                        helper.intrinsicRangeCheck(length, Condition.LT, ConstantNode.forInt(0));
                        helper.intrinsicRangeCheck(length, Condition.GT, ConstantNode.forInt(MAX_LENGTH));
                        ValueNode valueLength = b.add(new ArrayLengthNode(value));
                        ValueNode limit = b.add(new SubNode(valueLength, length));
                        helper.intrinsicRangeCheck(srcBegin, Condition.GT, limit);
                        ValueNode newArray = b.add(new NewArrayNode(b.getMetaAccess().lookupJavaType(Byte.TYPE), b.add(new LeftShiftNode(length, ConstantNode.forInt(1))), false));
                        b.addPush(JavaKind.Object, newArray);
                        // The stateAfter should include the value pushed, so push it first and then
                        // perform the call that fills in the array.
                        b.add(new ArrayCopyCallNode(foreignCalls, wordTypes, value, srcBegin, newArray, ConstantNode.forInt(0), length, JavaKind.Char, LocationIdentity.init(), false, true, true,
                                        vmConfig.heapWordSize));
                    }
                    return true;
                }
            });
            utf16r.register5("getChars", byte[].class, int.class, int.class, char[].class, int.class, new InvocationPlugin() {
                @Override
                public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode value, ValueNode srcBegin, ValueNode srcEnd, ValueNode dst,
                                ValueNode dstBegin) {
                    try (HotSpotInvocationPluginHelper helper = new HotSpotInvocationPluginHelper(b, targetMethod, vmConfig)) {
                        ValueNode length = helper.sub(srcEnd, srcBegin);
                        helper.intrinsicRangeCheck(srcBegin, Condition.LT, ConstantNode.forInt(0));
                        helper.intrinsicRangeCheck(length, Condition.LT, ConstantNode.forInt(0));
                        ValueNode srcLimit = helper.sub(helper.shr(helper.length(value), 1), length);
                        helper.intrinsicRangeCheck(srcBegin, Condition.GT, srcLimit);
                        ValueNode limit = helper.sub(helper.length(dst), length);
                        helper.intrinsicRangeCheck(dstBegin, Condition.GT, limit);
                        b.add(new ArrayCopyCallNode(foreignCalls, wordTypes, value, srcBegin, dst, dstBegin, length, JavaKind.Char, JavaKind.Byte, JavaKind.Char, false, true, true,
                                        vmConfig.heapWordSize));
                    }
                    return true;
                }
            });
        }
    }

    private static void registerThreadPlugins(InvocationPlugins plugins, GraalHotSpotVMConfig config, Replacements replacements) {
        Registration r = new Registration(plugins, Thread.class, replacements);
        r.register0("currentThread", new InvocationPlugin() {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver) {
                try (HotSpotInvocationPluginHelper helper = new HotSpotInvocationPluginHelper(b, targetMethod, config)) {
                    ValueNode value = helper.readCurrentThreadObject();
                    b.push(JavaKind.Object, value);
                }
                return true;
            }
        });

        if (config.osThreadInterruptedOffset != Integer.MAX_VALUE) {
            // This substitution is no longer in used when threadObj is a handle
            assert !config.threadObjectFieldIsHandle;
            r.register2("isInterrupted", Receiver.class, boolean.class, new InvocationPlugin() {
                @Override
                public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode clearInterrupted) {
                    try (HotSpotInvocationPluginHelper helper = new HotSpotInvocationPluginHelper(b, targetMethod, config)) {
                        ValueNode receiverThreadObject = receiver.get();
                        CurrentJavaThreadNode thread = b.add(new CurrentJavaThreadNode(helper.getWordKind()));
                        ValueNode currentThreadObject = helper.readCurrentThreadObject(thread);

                        // if (this != Thread.currentThread()) do fallback
                        helper.doFallbackIf(receiverThreadObject, NE, currentThreadObject, GraalDirectives.UNLIKELY_PROBABILITY);
                        ValueNode osThread = helper.readOsThread(thread);
                        ValueNode interrupted = helper.readOsThreadInterrupted(osThread);

                        // if (thread._osthread._isinterrupted == 0) return false
                        helper.emitReturnIf(interrupted, EQ, ConstantNode.forInt(0), ConstantNode.forBoolean(false), GraalDirectives.LIKELY_PROBABILITY);

                        // if (clearInterrupted) fallback to invoke
                        helper.doFallbackIf(clearInterrupted, EQ, ConstantNode.forBoolean(true), GraalDirectives.UNLIKELY_PROBABILITY);

                        // return interrupted == 0 ? false : true
                        LogicNode test = helper.createCompare(interrupted, CanonicalCondition.EQ, ConstantNode.forInt(0));
                        helper.emitFinalReturn(JavaKind.Boolean, ConditionalNode.create(test, ConstantNode.forBoolean(false), ConstantNode.forBoolean(true), NodeView.DEFAULT));
                    }
                    return true;
                }
            });
        }

    }

    public static final String reflectionClass;

    static {
        if (JavaVersionUtil.JAVA_SPEC <= 8) {
            reflectionClass = "sun.reflect.Reflection";
        } else {
            reflectionClass = "jdk.internal.reflect.Reflection";
        }
    }

    public static String lookupIntrinsicName(GraalHotSpotVMConfig config, String className, String name1, String name2) {
        return selectIntrinsicName(config, className, name1, name2).getLeft();
    }

    /**
     * Returns a pair of Strings where the left one represents the matched intrinsic name and the
     * right one represents the mismatched intrinsic name.
     */
    public static Pair<String, String> selectIntrinsicName(GraalHotSpotVMConfig config, String className, String name1, String name2) {
        boolean foundName1 = false;
        boolean foundName2 = false;
        for (VMIntrinsicMethod intrinsic : config.getStore().getIntrinsics()) {
            if (className.equals(intrinsic.declaringClass)) {
                if (name1.equals(intrinsic.name)) {
                    foundName1 = true;
                } else if (name2.equals(intrinsic.name)) {
                    foundName2 = true;
                }
            }
        }
        if (foundName1 && !foundName2) {
            return Pair.create(name1, name2);
        } else if (foundName2 && !foundName1) {
            return Pair.create(name2, name1);
        }
        throw GraalError.shouldNotReachHere();
    }

    public static boolean isIntrinsicName(GraalHotSpotVMConfig config, String className, String name) {
        for (VMIntrinsicMethod intrinsic : config.getStore().getIntrinsics()) {
            if (className.equals(intrinsic.declaringClass)) {
                if (name.equals(intrinsic.name)) {
                    return true;
                }
            }
        }
        return false;
    }

    // Fully qualified name is a workaround for JDK-8056066
    public static class AESCryptPlugin implements org.graalvm.compiler.nodes.graphbuilderconf.InvocationPlugin {
        private final boolean doEncrypt;

        public static ResolvedJavaType getType(ResolvedJavaType context, String typeName) {
            try {
                UnresolvedJavaType unresolved = UnresolvedJavaType.create(typeName);
                return unresolved.resolve(context);
            } catch (LinkageError e) {
                throw new GraalError(e);
            }
        }

        static ResolvedJavaType aesCryptType(ResolvedJavaType context) {
            return getType(context, "Lcom/sun/crypto/provider/AESCrypt;");
        }

        AESCryptPlugin(boolean doEncrypt) {
            this.doEncrypt = doEncrypt;
        }

        /**
         * The AES block size is a constant 128 bits as defined by the
         * <a href="http://nvlpubs.nist.gov/nistpubs/FIPS/NIST.FIPS.197.pdf">standard<a/>.
         */
        static final int AES_BLOCK_SIZE_IN_BYTES = 16;

        @Override
        public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode in, ValueNode inOffset, ValueNode out, ValueNode outOffset) {
            try (InvocationPluginHelper helper = new InvocationPluginHelper(b, targetMethod)) {
                ValueNode nonNullReceiver = receiver.get();
                ValueNode nonNullIn = b.nullCheckedValue(in);
                ValueNode nonNullOut = b.nullCheckedValue(out);

                ConstantNode zero = ConstantNode.forInt(0);
                // if (inOffset < 0) then deopt
                helper.intrinsicRangeCheck(inOffset, LT, zero);
                // if (in.length - AES_BLOCK_SIZE_IN_BYTES < inOffset) then deopt
                ValueNode inLength = helper.length(nonNullIn);
                helper.intrinsicRangeCheck(helper.sub(inLength, ConstantNode.forInt(AES_BLOCK_SIZE_IN_BYTES)), LT, inOffset);
                // if (outOffset < 0) then deopt
                helper.intrinsicRangeCheck(outOffset, LT, zero);
                // if (out.length - AES_BLOCK_SIZE_IN_BYTES < outOffset) then deopt
                ValueNode outLength = helper.length(nonNullOut);
                helper.intrinsicRangeCheck(helper.sub(outLength, ConstantNode.forInt(AES_BLOCK_SIZE_IN_BYTES)), LT, outOffset);

                // Read AESCrypt.K from receiver
                ResolvedJavaField kField = helper.getField(aesCryptType(targetMethod.getDeclaringClass()), "K");
                ValueNode k = b.nullCheckedValue(helper.loadField(nonNullReceiver, kField));
                // Compute pointers to the array bodies
                ValueNode kAddr = helper.arrayStart(k, JavaKind.Int);
                ValueNode inAddr = helper.arrayElementPointer(nonNullIn, JavaKind.Byte, inOffset);
                ValueNode outAddr = helper.arrayElementPointer(nonNullOut, JavaKind.Byte, outOffset);
                HotSpotForeignCallDescriptor descriptor = doEncrypt ? AESCRYPT_ENCRYPTBLOCK : AESCRYPT_DECRYPTBLOCK;
                b.add(new ForeignCallNode(descriptor, inAddr, outAddr, kAddr));
            }
            return true;
        }
    }

    public static class CipherBlockChainingCryptPlugin implements InvocationPlugin {
        private final boolean doEncrypt;

        public static ResolvedJavaType getType(ResolvedJavaType context, String typeName) {
            try {
                UnresolvedJavaType unresolved = UnresolvedJavaType.create(typeName);
                return unresolved.resolve(context);
            } catch (LinkageError e) {
                throw new GraalError(e);
            }
        }

        static ResolvedJavaType aesCryptType(ResolvedJavaType context) {
            return getType(context, "Lcom/sun/crypto/provider/AESCrypt;");
        }

        static ResolvedJavaType feedbackCipherType(ResolvedJavaType context) {
            return getType(context, "Lcom/sun/crypto/provider/FeedbackCipher;");
        }

        CipherBlockChainingCryptPlugin(boolean doEncrypt) {
            this.doEncrypt = doEncrypt;
        }

        @Override
        public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode in, ValueNode inOffset, ValueNode inLength, ValueNode out, ValueNode outOffset) {
            try (InvocationPluginHelper helper = new InvocationPluginHelper(b, targetMethod)) {
                ValueNode nonNullReceiver = receiver.get();
                // Read FeedbackCipher.embeddedCipher
                ResolvedJavaField embeddedCipherField = helper.getField(feedbackCipherType(targetMethod.getDeclaringClass()), "embeddedCipher");
                ValueNode embeddedCipher = helper.loadField(nonNullReceiver, embeddedCipherField);

                // Use the fallback path if the embeddedCipher is not an instance of AESCrypt
                LogicNode typeCheck = InstanceOfNode.create(TypeReference.create(b.getAssumptions(), aesCryptType(targetMethod.getDeclaringClass())), embeddedCipher);
                helper.doFallbackIfNot(typeCheck, GraalDirectives.UNLIKELY_PROBABILITY);

                ValueNode nonNullIn = b.nullCheckedValue(in);
                ValueNode nonNullOut = b.nullCheckedValue(out);

                ConstantNode zero = ConstantNode.forInt(0);
                // if (inOffset < 0) then deopt
                helper.intrinsicRangeCheck(inOffset, LT, zero);
                // if (in.length - AES_BLOCK_SIZE_IN_BYTES < inOffset) then deopt
                helper.intrinsicRangeCheck(helper.sub(inLength, ConstantNode.forInt(AES_BLOCK_SIZE_IN_BYTES)), LT, inOffset);
                // if (outOffset < 0) then deopt
                helper.intrinsicRangeCheck(outOffset, LT, zero);
                // if (out.length - AES_BLOCK_SIZE_IN_BYTES < outOffset) then deopt
                ValueNode outLength = helper.length(nonNullOut);
                helper.intrinsicRangeCheck(helper.sub(outLength, ConstantNode.forInt(AES_BLOCK_SIZE_IN_BYTES)), LT, outOffset);

                // Read AESCrypt.K
                ResolvedJavaField kField = helper.getField(aesCryptType(targetMethod.getDeclaringClass()), "K");
                ValueNode k = b.nullCheckedValue(helper.loadField(embeddedCipher, kField));

                // Read CipherBlockChaining.r
                ResolvedJavaField rField = helper.getField(targetMethod.getDeclaringClass(), "r");
                ValueNode r = b.nullCheckedValue(helper.loadField(nonNullReceiver, rField));

                // Compute pointers into arrays
                ValueNode kAddr = helper.arrayStart(k, JavaKind.Int);
                ValueNode rAddr = helper.arrayStart(r, JavaKind.Byte);
                ValueNode inAddr = helper.arrayElementPointer(nonNullIn, JavaKind.Byte, inOffset);
                ValueNode outAddr = helper.arrayElementPointer(nonNullOut, JavaKind.Byte, outOffset);
                HotSpotForeignCallDescriptor descriptor = doEncrypt ? CIPHER_BLOCK_CHAINING_ENCRYPT_AESCRYPT : CIPHER_BLOCK_CHAINING_DECRYPT_AESCRYPT;
                ForeignCallNode call = b.add(new ForeignCallNode(descriptor, inAddr, outAddr, kAddr, rAddr, inLength));
                helper.emitFinalReturn(JavaKind.Int, call);
            }
            return true;
        }
    }

    private static void registerAESPlugins(InvocationPlugins plugins, GraalHotSpotVMConfig config, Replacements replacements) {
        if (config.useAESIntrinsics) {
            assert config.aescryptEncryptBlockStub != 0L;
            assert config.aescryptDecryptBlockStub != 0L;
            assert config.cipherBlockChainingEncryptAESCryptStub != 0L;
            assert config.cipherBlockChainingDecryptAESCryptStub != 0L;

            Registration r = new Registration(plugins, "com.sun.crypto.provider.CipherBlockChaining", replacements);

            Pair<String, String> cbcEncryptName = selectIntrinsicName(config, "com/sun/crypto/provider/CipherBlockChaining", "implEncrypt", "encrypt");
            try {
                r.registerOptional6(cbcEncryptName.getLeft(), Receiver.class, byte[].class, int.class, int.class, byte[].class, int.class, new CipherBlockChainingCryptPlugin(true));
            } catch (NoSuchMethodError e3) {
                throw new GraalError(e3, "Found method named '%s' instead of '%s' in class '%s'. This is most likely because the JVMCI JDK in %s was built on an incompatible base JDK.",
                                cbcEncryptName.getRight(), cbcEncryptName.getLeft(), r.getDeclaringType().getTypeName(), Services.getSavedProperties().get("java.home"));
            }

            Pair<String, String> cbcDecryptName = selectIntrinsicName(config, "com/sun/crypto/provider/CipherBlockChaining", "implDecrypt", "decrypt");
            try {
                r.registerOptional6(cbcDecryptName.getLeft(), Receiver.class, byte[].class, int.class, int.class, byte[].class, int.class, new CipherBlockChainingCryptPlugin(false));
            } catch (NoSuchMethodError e2) {
                throw new GraalError(e2, "Found method named '%s' instead of '%s' in class '%s'. This is most likely because the JVMCI JDK in %s was built on an incompatible base JDK.",
                                cbcDecryptName.getRight(), cbcDecryptName.getLeft(), r.getDeclaringType().getTypeName(), Services.getSavedProperties().get("java.home"));
            }

            r = new Registration(plugins, "com.sun.crypto.provider.AESCrypt", replacements);

            Pair<String, String> aesEncryptName = selectIntrinsicName(config, "com/sun/crypto/provider/AESCrypt", "implEncryptBlock", "encryptBlock");
            try {
                r.registerOptional5(aesEncryptName.getLeft(), Receiver.class, byte[].class, int.class, byte[].class, int.class, new AESCryptPlugin(true));
            } catch (NoSuchMethodError e1) {
                throw new GraalError(e1, "Found method named '%s' instead of '%s' in class '%s'. This is most likely because the JVMCI JDK in %s was built on an incompatible base JDK.",
                                aesEncryptName.getRight(), aesEncryptName.getLeft(), r.getDeclaringType().getTypeName(), Services.getSavedProperties().get("java.home"));
            }

            Pair<String, String> aesDecryptName = selectIntrinsicName(config, "com/sun/crypto/provider/AESCrypt", "implDecryptBlock", "decryptBlock");
            try {
                r.registerOptional5(aesDecryptName.getLeft(), Receiver.class, byte[].class, int.class, byte[].class, int.class, new AESCryptPlugin(false));
            } catch (NoSuchMethodError e) {
                throw new GraalError(e, "Found method named '%s' instead of '%s' in class '%s'. This is most likely because the JVMCI JDK in %s was built on an incompatible base JDK.",
                                aesDecryptName.getRight(), aesDecryptName.getLeft(), r.getDeclaringType().getTypeName(), Services.getSavedProperties().get("java.home"));
            }
        }
    }

    private static void registerBigIntegerPlugins(InvocationPlugins plugins, GraalHotSpotVMConfig config, Replacements replacements) {
        Registration r = new Registration(plugins, BigInteger.class, replacements);
        assert !config.useMultiplyToLenIntrinsic() || config.multiplyToLen != 0L;
        if (JavaVersionUtil.JAVA_SPEC <= 8) {
            r.registerConditionalMethodSubstitution(config.useMultiplyToLenIntrinsic(), BigIntegerSubstitutions.class, "multiplyToLen", "multiplyToLenStatic", int[].class, int.class, int[].class,
                            int.class, int[].class);
        } else {
            r.registerConditionalMethodSubstitution(config.useMultiplyToLenIntrinsic(), BigIntegerSubstitutions.class, "implMultiplyToLen", "multiplyToLenStatic", int[].class, int.class, int[].class,
                            int.class, int[].class);
        }
        if (config.useMulAddIntrinsic()) {
            r.register5("implMulAdd", int[].class, int[].class, int.class, int.class, int.class, new InvocationPlugin() {
                @Override
                public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode out, ValueNode in, ValueNode offset, ValueNode len, ValueNode k) {
                    try (InvocationPluginHelper helper = new InvocationPluginHelper(b, targetMethod)) {
                        ValueNode outNonNull = b.nullCheckedValue(out);
                        ValueNode outNonNullLength = b.add(new ArrayLengthNode(outNonNull));
                        ValueNode newOffset = new SubNode(outNonNullLength, offset);
                        ForeignCallNode call = new ForeignCallNode(HotSpotBackend.MUL_ADD, helper.arrayStart(outNonNull, JavaKind.Int), helper.arrayStart(in, JavaKind.Int), newOffset, len, k);
                        b.addPush(JavaKind.Int, call);
                    }
                    return true;
                }
            });
        }
        if (config.useMontgomeryMultiplyIntrinsic()) {
            /*
             * static int[] implMontgomeryMultiply(int[] a, int[] b, int[] n, int len, long inv,
             * int[] product)
             */
            r.register6("implMontgomeryMultiply", int[].class, int[].class, int[].class, int.class, long.class, int[].class, new InvocationPlugin() {

                @Override
                public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode a, ValueNode bObject, ValueNode n, ValueNode len, ValueNode inv,
                                ValueNode product) {
                    try (InvocationPluginHelper helper = new InvocationPluginHelper(b, targetMethod)) {
                        // The stub doesn't return the right value for the intrinsic so push it here
                        // and the proper after FrameState will be put on ForeignCallNode by add.
                        b.addPush(JavaKind.Object, product);
                        b.add(new ForeignCallNode(HotSpotBackend.MONTGOMERY_MULTIPLY, helper.arrayStart(a, JavaKind.Int), helper.arrayStart(bObject, JavaKind.Int),
                                        helper.arrayStart(n, JavaKind.Int), len, inv, helper.arrayStart(product, JavaKind.Int)));
                    }
                    return true;
                }
            });
        }
        if (config.useMontgomerySquareIntrinsic()) {
            /*
             * static int[] implMontgomerySquare(int[] a, int[] n, int len, long inv, int[] product)
             */
            r.register5("implMontgomerySquare", int[].class, int[].class, int.class, long.class, int[].class, new InvocationPlugin() {
                @Override
                public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode a, ValueNode n, ValueNode len, ValueNode inv, ValueNode product) {
                    try (InvocationPluginHelper helper = new InvocationPluginHelper(b, targetMethod)) {
                        // The stub doesn't return the right value for the intrinsic so push it here
                        // and the proper after FrameState will be put on ForeignCallNode by add.
                        b.addPush(JavaKind.Object, product);
                        b.add(new ForeignCallNode(HotSpotBackend.MONTGOMERY_SQUARE, helper.arrayStart(a, JavaKind.Int), helper.arrayStart(n, JavaKind.Int), len, inv,
                                        helper.arrayStart(product, JavaKind.Int)));
                    }
                    return true;
                }
            });
        }
        if (config.useSquareToLenIntrinsic()) {
            /*
             * static int[] implSquareToLen(int[] x, int len, int[] z, int zLen)
             */
            r.register4("implSquareToLen", int[].class, int.class, int[].class, int.class, new InvocationPlugin() {
                @Override
                public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode x, ValueNode len, ValueNode z, ValueNode zlen) {
                    try (InvocationPluginHelper helper = new InvocationPluginHelper(b, targetMethod)) {
                        // The stub doesn't return the right value for the intrinsic so push it here
                        // and the proper after FrameState will be put on ForeignCallNode by add.
                        b.addPush(JavaKind.Object, z);
                        b.add(new ForeignCallNode(HotSpotBackend.SQUARE_TO_LEN, helper.arrayStart(x, JavaKind.Int), len, helper.arrayStart(z, JavaKind.Int), zlen));
                    }
                    return true;
                }
            });
        }
    }

    static class SHAInvocationPlugin implements InvocationPlugin {
        private final ForeignCallDescriptor descriptor;

        SHAInvocationPlugin(ForeignCallDescriptor descriptor) {
            this.descriptor = descriptor;
        }

        @Override
        public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode buf, ValueNode ofs) {
            ValueNode realReceiver = b.add(new PiNode(receiver.get(), targetMethod.getDeclaringClass(), false, true));
            JavaKind wordKind = JavaKind.Long;
            int stateOffset = HotSpotReplacementsUtil.getFieldOffset(targetMethod.getDeclaringClass(), "state");
            ValueNode state = b.add(new RawLoadNode(realReceiver, b.add(ConstantNode.forIntegerKind(wordKind, stateOffset)), JavaKind.Object, LocationIdentity.any()));
            int intArrayBaseOffset = b.getMetaAccess().getArrayBaseOffset(JavaKind.Int);
            int byteArrayBaseOffset = b.getMetaAccess().getArrayBaseOffset(JavaKind.Byte);
            ValueNode bufAddr = b.add(new ComputeObjectAddressNode(buf, new AddNode(ConstantNode.forInt(byteArrayBaseOffset), ofs)));
            ValueNode stateAddr = b.add(new ComputeObjectAddressNode(state, ConstantNode.forInt(intArrayBaseOffset)));
            b.add(new ForeignCallNode(descriptor, bufAddr, stateAddr));
            return true;
        }

    }

    private static void registerSHAPlugins(InvocationPlugins plugins, GraalHotSpotVMConfig config, Replacements replacements) {
        boolean useSha1 = config.useSHA1Intrinsics();
        boolean useSha256 = config.useSHA256Intrinsics();
        boolean useSha512 = config.useSHA512Intrinsics();

        if (isIntrinsicName(config, "sun/security/provider/DigestBase", "implCompressMultiBlock0") && (useSha1 || useSha256 || useSha512)) {
            Registration r = new Registration(plugins, "sun.security.provider.DigestBase", replacements);
            r.registerMethodSubstitution(DigestBaseSubstitutions.class, "implCompressMultiBlock0", Receiver.class, byte[].class, int.class, int.class);
        }

        Pair<String, String> implCompressName = selectIntrinsicName(config, "sun/security/provider/SHA", "implCompress", "implCompress0");
        if (useSha1) {
            assert config.sha1ImplCompress != 0L;
            Registration r = new Registration(plugins, "sun.security.provider.SHA", replacements);
            InvocationPlugin plugin = new SHAInvocationPlugin(HotSpotBackend.SHA_IMPL_COMPRESS);
            try {
                r.register3(implCompressName.getLeft(), Receiver.class, byte[].class, int.class, plugin);
            } catch (NoSuchMethodError e) {
                throw new GraalError(e, "Found method named '%s' instead of '%s' in class '%s'. This is most likely because the JVMCI JDK in %s was built on an incompatible base JDK.",
                                implCompressName.getRight(), implCompressName.getLeft(), r.getDeclaringType().getTypeName(), Services.getSavedProperties().get("java.home"));
            }
        }
        if (useSha256) {
            assert config.sha256ImplCompress != 0L;
            Registration r = new Registration(plugins, "sun.security.provider.SHA2", replacements);
            InvocationPlugin plugin = new SHAInvocationPlugin(HotSpotBackend.SHA2_IMPL_COMPRESS);
            try {
                r.register3(implCompressName.getLeft(), Receiver.class, byte[].class, int.class, plugin);
            } catch (NoSuchMethodError e) {
                throw new GraalError(e, "Found method named '%s' instead of '%s' in class '%s'. This is most likely because the JVMCI JDK in %s was built on an incompatible base JDK.",
                                implCompressName.getRight(), implCompressName.getLeft(), r.getDeclaringType().getTypeName(), Services.getSavedProperties().get("java.home"));
            }
        }
        if (useSha512) {
            assert config.sha512ImplCompress != 0L;
            Registration r = new Registration(plugins, "sun.security.provider.SHA5", replacements);
            InvocationPlugin plugin = new SHAInvocationPlugin(HotSpotBackend.SHA5_IMPL_COMPRESS);
            try {
                r.register3(implCompressName.getLeft(), Receiver.class, byte[].class, int.class, plugin);
            } catch (NoSuchMethodError e) {
                throw new GraalError(e, "Found method named '%s' instead of '%s' in class '%s'. This is most likely because the JVMCI JDK in %s was built on an incompatible base JDK.",
                                implCompressName.getRight(), implCompressName.getLeft(), r.getDeclaringType().getTypeName(), Services.getSavedProperties().get("java.home"));
            }
        }
    }

    private static void registerGHASHPlugins(InvocationPlugins plugins, GraalHotSpotVMConfig config, MetaAccessProvider metaAccess) {
        if (config.useGHASHIntrinsics()) {
            assert config.ghashProcessBlocks != 0L;
            Registration r = new Registration(plugins, "com.sun.crypto.provider.GHASH");
            r.register5("processBlocks",
                            byte[].class,
                            int.class,
                            int.class,
                            long[].class,
                            long[].class,
                            new InvocationPlugin() {
                                @Override
                                public boolean apply(GraphBuilderContext b,
                                                ResolvedJavaMethod targetMethod,
                                                Receiver receiver,
                                                ValueNode data,
                                                ValueNode inOffset,
                                                ValueNode blocks,
                                                ValueNode state,
                                                ValueNode hashSubkey) {
                                    int longArrayBaseOffset = metaAccess.getArrayBaseOffset(JavaKind.Long);
                                    int byteArrayBaseOffset = metaAccess.getArrayBaseOffset(JavaKind.Byte);
                                    ValueNode dataOffset = AddNode.create(ConstantNode.forInt(byteArrayBaseOffset), inOffset, NodeView.DEFAULT);
                                    ComputeObjectAddressNode dataAddress = b.add(new ComputeObjectAddressNode(data, dataOffset));
                                    ComputeObjectAddressNode stateAddress = b.add(new ComputeObjectAddressNode(state, ConstantNode.forInt(longArrayBaseOffset)));
                                    ComputeObjectAddressNode hashSubkeyAddress = b.add(new ComputeObjectAddressNode(hashSubkey, ConstantNode.forInt(longArrayBaseOffset)));
                                    b.add(new ForeignCallNode(GHASH_PROCESS_BLOCKS, stateAddress, hashSubkeyAddress, dataAddress, blocks));
                                    return true;
                                }
                            });
        }
    }

    static class CounterModeCryptPlugin implements InvocationPlugin {
        public static ResolvedJavaType getType(ResolvedJavaType context, String typeName) {
            try {
                UnresolvedJavaType unresolved = UnresolvedJavaType.create(typeName);
                return unresolved.resolve(context);
            } catch (LinkageError e) {
                throw new GraalError(e);
            }
        }

        static ResolvedJavaType aesCryptType(ResolvedJavaType context) {
            return getType(context, "Lcom/sun/crypto/provider/AESCrypt;");
        }

        static ResolvedJavaType feedbackCipherType(ResolvedJavaType context) {
            return getType(context, "Lcom/sun/crypto/provider/FeedbackCipher;");
        }

        @Override
        public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode in, ValueNode inOffset, ValueNode len, ValueNode out, ValueNode outOffset) {
            try (InvocationPluginHelper helper = new InvocationPluginHelper(b, targetMethod)) {
                ValueNode nonNullReceiver = receiver.get();

                // Read FeedbackCipher.embeddedCipher
                ResolvedJavaField embeddedCipherField = helper.getField(feedbackCipherType(targetMethod.getDeclaringClass()), "embeddedCipher");
                ValueNode embeddedCipher = helper.loadField(nonNullReceiver, embeddedCipherField);

                // Use the fallback path if the embeddedCipher is not an instance of AESCrypt
                LogicNode typeCheck = InstanceOfNode.create(TypeReference.create(b.getAssumptions(), aesCryptType(targetMethod.getDeclaringClass())), embeddedCipher);
                helper.doFallbackIfNot(typeCheck, GraalDirectives.UNLIKELY_PROBABILITY);

                // Compute pointers to array bodies
                ValueNode nonNullIn = b.nullCheckedValue(in);
                ValueNode nonNullOut = b.nullCheckedValue(out);
                ValueNode inAddr = helper.arrayElementPointer(nonNullIn, JavaKind.Byte, inOffset);
                ValueNode outAddr = helper.arrayElementPointer(nonNullOut, JavaKind.Byte, outOffset);

                // Read AESCrypt.K
                ResolvedJavaField kField = helper.getField(aesCryptType(targetMethod.getDeclaringClass()), "K");
                ValueNode k = b.nullCheckedValue(helper.loadField(embeddedCipher, kField));

                // Read CounterModeCrypt.counter
                ResolvedJavaField counterField = helper.getField(targetMethod.getDeclaringClass(), "counter");
                ValueNode counter = helper.loadField(nonNullReceiver, counterField);
                ValueNode counterAddr = helper.arrayStart(counter, JavaKind.Byte);

                // Read CounterModeCrypt.encryptedCounter
                ResolvedJavaField encryptedCounterField = helper.getField(targetMethod.getDeclaringClass(), "encryptedCounter");
                ValueNode encryptedCounter = helper.loadField(nonNullReceiver, encryptedCounterField);
                ValueNode encryptedCounterAddr = helper.arrayStart(encryptedCounter, JavaKind.Byte);
                ValueNode kAddr = helper.arrayStart(k, JavaKind.Int);

                // Compute address of CounterModeCrypt.used field
                ValueNode usedPtr = b.add(new ComputeObjectAddressNode(nonNullReceiver, helper.asWord(helper.getFieldOffset(targetMethod.getDeclaringClass(), "used"))));
                ForeignCallNode call = b.add(new ForeignCallNode(COUNTERMODE_IMPL_CRYPT, inAddr, outAddr, kAddr, counterAddr, len, encryptedCounterAddr, usedPtr));
                helper.emitFinalReturn(JavaKind.Int, call);
            }
            return true;
        }
    }

    private static void registerCounterModePlugins(InvocationPlugins plugins, GraalHotSpotVMConfig config, Replacements replacements) {
        if (isIntrinsicName(config, "com/sun/crypto/provider/CounterMode", "implCrypt") && config.useAESCTRIntrinsics) {
            assert config.counterModeAESCrypt != 0L;
            Registration r = new Registration(plugins, "com.sun.crypto.provider.CounterMode", replacements);
            r.register6("implCrypt", Receiver.class, byte[].class, int.class, int.class, byte[].class,
                            int.class, new CounterModeCryptPlugin());
        }
    }

    private static void registerBase64Plugins(InvocationPlugins plugins, GraalHotSpotVMConfig config, MetaAccessProvider metaAccess) {
        if (config.useBase64Intrinsics()) {
            Registration r = new Registration(plugins, "java.util.Base64$Encoder");
            r.register7("encodeBlock",
                            Receiver.class,
                            byte[].class,
                            int.class,
                            int.class,
                            byte[].class,
                            int.class,
                            boolean.class,
                            new InvocationPlugin() {
                                @Override
                                public boolean apply(GraphBuilderContext b,
                                                ResolvedJavaMethod targetMethod,
                                                Receiver receiver,
                                                ValueNode src,
                                                ValueNode sp,
                                                ValueNode sl,
                                                ValueNode dst,
                                                ValueNode dp,
                                                ValueNode isURL) {
                                    int byteArrayBaseOffset = metaAccess.getArrayBaseOffset(JavaKind.Byte);
                                    ComputeObjectAddressNode srcAddress = b.add(new ComputeObjectAddressNode(src, ConstantNode.forInt(byteArrayBaseOffset)));
                                    ComputeObjectAddressNode dstAddress = b.add(new ComputeObjectAddressNode(dst, ConstantNode.forInt(byteArrayBaseOffset)));
                                    b.add(new ForeignCallNode(BASE64_ENCODE_BLOCK, srcAddress, sp, sl, dstAddress, dp, isURL));
                                    return true;
                                }
                            });
        }
    }

    private static void registerCRC32Plugins(InvocationPlugins plugins, GraalHotSpotVMConfig config, Replacements replacements) {
        if (config.useCRC32Intrinsics) {
            Registration r = new Registration(plugins, CRC32.class, replacements);
            r.register2("update", int.class, int.class, new InvocationPlugin() {
                @Override
                public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode crc, ValueNode arg) {
                    final ValueNode crcTableRawAddress = ConstantNode.forLong(config.crcTableAddress);
                    ValueNode c = new XorNode(crc, ConstantNode.forInt(-1));
                    ValueNode index = new AndNode(new XorNode(arg, c), ConstantNode.forInt(0xff));
                    ValueNode offset = new LeftShiftNode(index, ConstantNode.forInt(2));
                    AddressNode address = new OffsetAddressNode(crcTableRawAddress, new SignExtendNode(offset, 32, 64));
                    ValueNode result = b.add(new JavaReadNode(JavaKind.Int, address, CRC_TABLE_LOCATION, BarrierType.NONE, false));
                    result = new XorNode(result, new UnsignedRightShiftNode(c, ConstantNode.forInt(8)));
                    b.addPush(JavaKind.Int, new XorNode(result, ConstantNode.forInt(-1)));
                    return true;
                }
            });
            String updateBytesName = JavaVersionUtil.JAVA_SPEC <= 8 ? "updateBytes" : "updateBytes0";
            r.register4(updateBytesName, int.class, byte[].class, int.class, int.class, new InvocationPlugin() {
                @Override
                public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode crc, ValueNode buf, ValueNode off, ValueNode len) {
                    int byteArrayBaseOffset = b.getMetaAccess().getArrayBaseOffset(JavaKind.Byte);
                    ValueNode bufAddr = b.add(new ComputeObjectAddressNode(buf, new AddNode(ConstantNode.forInt(byteArrayBaseOffset), off)));
                    b.addPush(JavaKind.Int, new ForeignCallNode(UPDATE_BYTES_CRC32, crc, bufAddr, len));
                    return true;
                }
            });
            String updateByteBufferName = JavaVersionUtil.JAVA_SPEC <= 8 ? "updateByteBuffer" : "updateByteBuffer0";
            r.register4(updateByteBufferName, int.class, long.class, int.class, int.class, new InvocationPlugin() {
                @Override
                public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode crc, ValueNode addr, ValueNode off, ValueNode len) {
                    ValueNode bufAddr = b.add(new AddNode(addr, new SignExtendNode(off, 32, 64)));
                    b.addPush(JavaKind.Int, new ForeignCallNode(UPDATE_BYTES_CRC32, crc, bufAddr, len));
                    return true;
                }
            });
        }
    }

    private static void registerCRC32CPlugins(InvocationPlugins plugins, GraalHotSpotVMConfig config, Replacements replacements) {
        if (JavaVersionUtil.JAVA_SPEC > 8 && config.useCRC32CIntrinsics) {
            Registration r = new Registration(plugins, "java.util.zip.CRC32C", replacements);
            r.register4("updateBytes", int.class, byte[].class, int.class, int.class, new InvocationPlugin() {
                @Override
                public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode crc, ValueNode buf, ValueNode off, ValueNode end) {
                    int byteArrayBaseOffset = b.getMetaAccess().getArrayBaseOffset(JavaKind.Byte);
                    ValueNode bufAddr = b.add(new ComputeObjectAddressNode(buf, new AddNode(ConstantNode.forInt(byteArrayBaseOffset), off)));
                    b.addPush(JavaKind.Int, new ForeignCallNode(UPDATE_BYTES_CRC32C, crc, bufAddr, new SubNode(end, off)));
                    return true;
                }
            });
            r.register4("updateDirectByteBuffer", int.class, long.class, int.class, int.class, new InvocationPlugin() {
                @Override
                public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode crc, ValueNode addr, ValueNode off, ValueNode end) {
                    ValueNode bufAddr = b.add(new AddNode(addr, new SignExtendNode(off, 32, 64)));
                    b.addPush(JavaKind.Int, new ForeignCallNode(UPDATE_BYTES_CRC32C, crc, bufAddr, new SubNode(end, off)));
                    return true;
                }
            });
        }
    }

    private static void registerArraysSupportPlugins(InvocationPlugins plugins, GraalHotSpotVMConfig config, Replacements replacements) {
        if (JavaVersionUtil.JAVA_SPEC > 8 && config.useVectorizedMismatchIntrinsic) {
            Registration r = new Registration(plugins, "jdk.internal.util.ArraysSupport", replacements);
            r.register6("vectorizedMismatch", Object.class, long.class, Object.class, long.class,
                            int.class, int.class, new InvocationPlugin() {
                                @Override
                                public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode a, ValueNode aOffset, ValueNode bObject, ValueNode bOffset,
                                                ValueNode length, ValueNode log2ArrayIndexScale) {
                                    ValueNode aAddr = b.add(new ComputeObjectAddressNode(a, aOffset));
                                    ValueNode bAddr = b.add(new ComputeObjectAddressNode(bObject, bOffset));
                                    b.addPush(JavaKind.Int, new ForeignCallNode(HotSpotBackend.VECTORIZED_MISMATCH, aAddr, bAddr, length, log2ArrayIndexScale));
                                    return true;
                                }
                            });

        }
    }

    private static void registerReferencePlugins(InvocationPlugins plugins, Replacements replacements) {
        if (JavaVersionUtil.JAVA_SPEC >= 16) {
            Registration r = new Registration(plugins, Reference.class, replacements);
            r.register2("refersTo0", Receiver.class, Object.class, new InvocationPlugin() {
                @Override
                public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode o) {
                    ValueNode offset = b.add(ConstantNode.forLong(HotSpotReplacementsUtil.referentOffset(b.getMetaAccess())));
                    AddressNode address = b.add(new OffsetAddressNode(receiver.get(), offset));
                    FieldLocationIdentity locationIdentity = new FieldLocationIdentity(HotSpotReplacementsUtil.referentField(b.getMetaAccess()));
                    JavaReadNode read = b.add(new JavaReadNode(StampFactory.object(), JavaKind.Object, address, locationIdentity, BarrierType.WEAK_FIELD, true));
                    LogicNode objectEquals = b.add(ObjectEqualsNode.create(b.getConstantReflection(), b.getMetaAccess(), b.getOptions(), read, o, NodeView.DEFAULT));
                    b.addPush(JavaKind.Boolean, ConditionalNode.create(objectEquals, b.add(forBoolean(true)), b.add(forBoolean(false)), NodeView.DEFAULT));
                    return true;
                }

                @Override
                public boolean inlineOnly() {
                    return true;
                }
            });
            r = new Registration(plugins, PhantomReference.class, replacements);
            r.register2("refersTo0", Receiver.class, Object.class, new InvocationPlugin() {
                @Override
                public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode o) {
                    ValueNode offset = b.add(ConstantNode.forLong(HotSpotReplacementsUtil.referentOffset(b.getMetaAccess())));
                    AddressNode address = b.add(new OffsetAddressNode(receiver.get(), offset));
                    FieldLocationIdentity locationIdentity = new FieldLocationIdentity(HotSpotReplacementsUtil.referentField(b.getMetaAccess()));
                    JavaReadNode read = b.add(new JavaReadNode(StampFactory.object(), JavaKind.Object, address, locationIdentity, BarrierType.PHANTOM_FIELD, true));
                    LogicNode objectEquals = b.add(ObjectEqualsNode.create(b.getConstantReflection(), b.getMetaAccess(), b.getOptions(), read, o, NodeView.DEFAULT));
                    b.addPush(JavaKind.Boolean, ConditionalNode.create(objectEquals, b.add(forBoolean(true)), b.add(forBoolean(false)), NodeView.DEFAULT));
                    return true;
                }

                @Override
                public boolean inlineOnly() {
                    return true;
                }
            });
        }
    }
}
