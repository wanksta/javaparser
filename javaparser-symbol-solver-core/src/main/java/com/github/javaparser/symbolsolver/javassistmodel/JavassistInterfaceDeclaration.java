/*
 * Copyright (C) 2015-2016 Federico Tomassetti
 * Copyright (C) 2017-2019 The JavaParser Team.
 *
 * This file is part of JavaParser.
 *
 * JavaParser can be used either under the terms of
 * a) the GNU Lesser General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 * b) the terms of the Apache License
 *
 * You should have received a copy of both licenses in LICENCE.LGPL and
 * LICENCE.APACHE. Please refer to those files for details.
 *
 * JavaParser is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 */

package com.github.javaparser.symbolsolver.javassistmodel;

import com.github.javaparser.ast.AccessSpecifier;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.resolution.MethodUsage;
import com.github.javaparser.resolution.UnsolvedSymbolException;
import com.github.javaparser.resolution.declarations.*;
import com.github.javaparser.resolution.types.ResolvedReferenceType;
import com.github.javaparser.resolution.types.ResolvedType;
import com.github.javaparser.symbolsolver.core.resolution.Context;
import com.github.javaparser.symbolsolver.core.resolution.MethodUsageResolutionCapability;
import com.github.javaparser.symbolsolver.logic.AbstractTypeDeclaration;
import com.github.javaparser.symbolsolver.logic.MethodResolutionCapability;
import com.github.javaparser.symbolsolver.model.resolution.SymbolReference;
import com.github.javaparser.symbolsolver.model.resolution.TypeSolver;
import com.github.javaparser.symbolsolver.model.typesystem.ReferenceTypeImpl;
import com.github.javaparser.symbolsolver.resolution.MethodResolutionLogic;
import com.github.javaparser.symbolsolver.resolution.SymbolSolver;
import javassist.CtClass;
import javassist.CtField;
import javassist.CtMethod;
import javassist.NotFoundException;
import javassist.bytecode.AccessFlag;
import javassist.bytecode.SyntheticAttribute;

import java.lang.reflect.Modifier;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * @author Federico Tomassetti
 */
public class JavassistInterfaceDeclaration extends AbstractTypeDeclaration
        implements ResolvedInterfaceDeclaration, MethodResolutionCapability, MethodUsageResolutionCapability {

    private CtClass ctClass;
    private TypeSolver typeSolver;
    private JavassistTypeDeclarationAdapter javassistTypeDeclarationAdapter;

    @Override
    public String toString() {
        return "JavassistInterfaceDeclaration{" +
                "ctClass=" + ctClass.getName() +
                ", typeSolver=" + typeSolver +
                '}';
    }

    public JavassistInterfaceDeclaration(CtClass ctClass, TypeSolver typeSolver) {
        if (!ctClass.isInterface()) {
            throw new IllegalArgumentException("Not an interface: " + ctClass.getName());
        }
        this.ctClass = ctClass;
        this.typeSolver = typeSolver;
        this.javassistTypeDeclarationAdapter = new JavassistTypeDeclarationAdapter(ctClass, typeSolver);
    }

    @Override
    public List<ResolvedReferenceType> getInterfacesExtended() {
        return Arrays.stream(ctClass.getClassFile().getInterfaces()).map(i -> typeSolver.solveType(i))
                .map(i -> new ReferenceTypeImpl(i, typeSolver)).collect(Collectors.toList());
    }

    @Override
    public String getPackageName() {
        return ctClass.getPackageName();
    }

    @Override
    public String getClassName() {
        String className = ctClass.getName().replace('$', '.');
        if (getPackageName() != null) {
            return className.substring(getPackageName().length() + 1);
        }
        return className;
    }

    @Override
    public String getQualifiedName() {
        return ctClass.getName().replace('$', '.');
    }

    @Deprecated
    public Optional<MethodUsage> solveMethodAsUsage(String name, List<ResolvedType> argumentsTypes,
                                                    Context invokationContext, List<ResolvedType> typeParameterValues) {

        return JavassistUtils.getMethodUsage(ctClass, name, argumentsTypes, typeSolver, getTypeParameters(), typeParameterValues);
    }

    @Override
    @Deprecated
    public SymbolReference<ResolvedMethodDeclaration> solveMethod(String name, List<ResolvedType> argumentsTypes, boolean staticOnly) {
        List<ResolvedMethodDeclaration> candidates = new ArrayList<>();
        Predicate<CtMethod> staticOnlyCheck = m -> !staticOnly || (staticOnly && Modifier.isStatic(m.getModifiers()));
        for (CtMethod method : ctClass.getDeclaredMethods()) {
            boolean isSynthetic = method.getMethodInfo().getAttribute(SyntheticAttribute.tag) != null;
            boolean isNotBridge =  (method.getMethodInfo().getAccessFlags() & AccessFlag.BRIDGE) == 0;
            if (method.getName().equals(name) && !isSynthetic && isNotBridge && staticOnlyCheck.test(method)) {
                candidates.add(new JavassistMethodDeclaration(method, typeSolver));
            }
        }

        try {
            CtClass superClass = ctClass.getSuperclass();
            if (superClass != null) {
                SymbolReference<ResolvedMethodDeclaration> ref = new JavassistClassDeclaration(superClass, typeSolver).solveMethod(name, argumentsTypes, staticOnly);
                if (ref.isSolved()) {
                    candidates.add(ref.getCorrespondingDeclaration());
                }
            }
        } catch (NotFoundException e) {
            throw new RuntimeException(e);
        }

        try {
            for (CtClass interfaze : ctClass.getInterfaces()) {
                SymbolReference<ResolvedMethodDeclaration> ref = new JavassistInterfaceDeclaration(interfaze, typeSolver).solveMethod(name, argumentsTypes, staticOnly);
                if (ref.isSolved()) {
                    candidates.add(ref.getCorrespondingDeclaration());
                }
            }
        } catch (NotFoundException e) {
            throw new RuntimeException(e);
        }

        return MethodResolutionLogic.findMostApplicable(candidates, name, argumentsTypes, typeSolver);
    }

    @Override
    public boolean isAssignableBy(ResolvedType type) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<ResolvedFieldDeclaration> getAllFields() {
      return javassistTypeDeclarationAdapter.getDeclaredFields();
    }

    @Override
    public boolean isAssignableBy(ResolvedReferenceTypeDeclaration other) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<ResolvedReferenceType> getAncestors(boolean acceptIncompleteList) {
        List<ResolvedReferenceType> ancestors = new ArrayList<>();
        try {
            for (CtClass interfaze : ctClass.getInterfaces()) {
                try {
                    ResolvedReferenceType superInterfaze = JavassistFactory.typeUsageFor(interfaze, typeSolver).asReferenceType();
                    ancestors.add(superInterfaze);
                } catch (UnsolvedSymbolException e) {
                    if (!acceptIncompleteList) {
                        // we only throw an exception if we require a complete list; otherwise, we attempt to continue gracefully
                        throw e;
                    }
                }
            }
        } catch (NotFoundException e) {
            throw new RuntimeException(e);
        }
        ancestors = ancestors.stream().filter(a -> !a.getQualifiedName().equals(Object.class.getCanonicalName()))
                .collect(Collectors.toList());
        ancestors.add(new ReferenceTypeImpl(typeSolver.solveType(Object.class.getCanonicalName()), typeSolver));
        return ancestors;
    }

    @Override
    public Set<ResolvedMethodDeclaration> getDeclaredMethods() {
        return Arrays.stream(ctClass.getDeclaredMethods())
                .map(m -> new JavassistMethodDeclaration(m, typeSolver))
                .collect(Collectors.toSet());
    }

    @Override
    public boolean hasDirectlyAnnotation(String canonicalName) {
        return ctClass.hasAnnotation(canonicalName);
    }

    @Override
    public String getName() {
        String[] nameElements = ctClass.getSimpleName().replace('$', '.').split("\\.");
        return nameElements[nameElements.length - 1];
    }

    @Override
    public List<ResolvedTypeParameterDeclaration> getTypeParameters() {
        return javassistTypeDeclarationAdapter.getTypeParameters();
    }

    @Override
    public AccessSpecifier accessSpecifier() {
        return JavassistFactory.modifiersToAccessLevel(ctClass.getModifiers());
    }

    @Override
    public ResolvedInterfaceDeclaration asInterface() {
        return this;
    }


    @Deprecated
    public SymbolReference<? extends ResolvedValueDeclaration> solveSymbol(String name, TypeSolver typeSolver) {
        for (CtField field : ctClass.getDeclaredFields()) {
            if (field.getName().equals(name)) {
                return SymbolReference.solved(new JavassistFieldDeclaration(field, typeSolver));
            }
        }

        String[] interfaceFQNs = getInterfaceFQNs();
        for (String interfaceFQN : interfaceFQNs) {
            SymbolReference<? extends ResolvedValueDeclaration> interfaceRef = solveSymbolForFQN(name, interfaceFQN);
            if (interfaceRef.isSolved()) {
                return interfaceRef;
            }
        }

        return SymbolReference.unsolved(ResolvedValueDeclaration.class);
    }

    private SymbolReference<? extends ResolvedValueDeclaration> solveSymbolForFQN(String symbolName, String fqn) {
        if (fqn == null) {
            return SymbolReference.unsolved(ResolvedValueDeclaration.class);
        }

        ResolvedReferenceTypeDeclaration fqnTypeDeclaration = typeSolver.solveType(fqn);
        return new SymbolSolver(typeSolver).solveSymbolInType(fqnTypeDeclaration, symbolName);
    }

    private String[] getInterfaceFQNs() {
        return ctClass.getClassFile().getInterfaces();
    }

    @Override
    public Optional<ResolvedReferenceTypeDeclaration> containerType() {
        return javassistTypeDeclarationAdapter.containerType();
    }

    @Override
    public Set<ResolvedReferenceTypeDeclaration> internalTypes() {
        try {
            /*
            Get all internal types of the current class and get their corresponding ReferenceTypeDeclaration.
            Finally, return them in a Set.
             */
            return Arrays.stream(ctClass.getDeclaredClasses()).map(itype -> JavassistFactory.toTypeDeclaration(itype, typeSolver)).collect(Collectors.toSet());
        } catch (NotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public ResolvedReferenceTypeDeclaration getInternalType(String name) {
        /*
        The name of the ReferenceTypeDeclaration could be composed on the internal class and the outer class, e.g. A$B. That's why we search the internal type in the ending part.
        In case the name is composed of the internal type only, i.e. f.getName() returns B, it will also works.
         */
        Optional<ResolvedReferenceTypeDeclaration> type =
                this.internalTypes().stream().filter(f -> f.getName().endsWith(name)).findFirst();
        return type.orElseThrow(() ->
                new UnsolvedSymbolException("Internal type not found: " + name));
    }

    @Override
    public boolean hasInternalType(String name) {
        /*
        The name of the ReferenceTypeDeclaration could be composed on the internal class and the outer class, e.g. A$B. That's why we search the internal type in the ending part.
        In case the name is composed of the internal type only, i.e. f.getName() returns B, it will also works.
         */
        return this.internalTypes().stream().anyMatch(f -> f.getName().endsWith(name));
    }

    @Override
    public List<ResolvedConstructorDeclaration> getConstructors() {
        return Collections.emptyList();
    }

    @Override
    public Optional<ClassOrInterfaceDeclaration> toAst() {
        return Optional.empty();
    }
}
