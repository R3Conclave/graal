/*
 * Copyright (c) 2021, 2022, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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

package com.oracle.truffle.espresso.analysis.hierarchy;

import com.oracle.truffle.espresso.impl.Method;
import com.oracle.truffle.espresso.impl.ObjectKlass;

/**
 * An implementation of {@link ClassHierarchyOracle} which simply checks {@code final} modifier of a
 * class.
 */
public class NoOpClassHierarchyOracle implements ClassHierarchyOracle {
    protected static final AssumptionGuardedValue<ObjectKlass> NotSingleImplementor = AssumptionGuardedValue.createInvalid();

    @Override
    public ClassHierarchyAssumption createAssumptionForNewKlass(ObjectKlass newKlass) {
        if (newKlass.isFinalFlagSet()) {
            return ClassHierarchyAssumptionImpl.AlwaysValid;
        }
        return ClassHierarchyAssumptionImpl.NeverValid;
    }

    @Override
    public ClassHierarchyAssumption isLeafKlass(ObjectKlass klass) {
        if (klass.isFinalFlagSet()) {
            return ClassHierarchyAssumptionImpl.AlwaysValid;
        }
        return ClassHierarchyAssumptionImpl.NeverValid;
    }

    @Override
    public ClassHierarchyAssumption hasNoImplementors(ObjectKlass klass) {
        return ClassHierarchyAssumptionImpl.NeverValid;
    }

    @Override
    public SingleImplementor initializeImplementorForNewKlass(ObjectKlass klass) {
        return SingleImplementor.MultipleImplementors;
    }

    @Override
    public AssumptionGuardedValue<ObjectKlass> readSingleImplementor(ObjectKlass klass) {
        return NotSingleImplementor;
    }

    @Override
    public ClassHierarchyAssumption createLeafAssumptionForNewMethod(Method newMethod) {
        if (newMethod.isAbstract()) {
            return ClassHierarchyAssumptionImpl.NeverValid;
        }
        if (newMethod.isStatic() || newMethod.isPrivate() || newMethod.isFinalFlagSet()) {
            return ClassHierarchyAssumptionImpl.AlwaysValid;
        }
        return ClassHierarchyAssumptionImpl.NeverValid;
    }

    @Override
    public ClassHierarchyAssumption isLeafMethod(Method method) {
        if (method.isStatic() || method.isPrivate() || method.isFinalFlagSet()) {
            return ClassHierarchyAssumptionImpl.AlwaysValid;
        }
        return ClassHierarchyAssumptionImpl.NeverValid;
    }
}