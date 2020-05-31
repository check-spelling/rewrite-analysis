/*
 * Copyright 2020 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openrewrite.java.refactor;

import lombok.EqualsAndHashCode;
import org.openrewrite.Formatting;
import org.openrewrite.internal.lang.Nullable;
import org.openrewrite.java.MethodMatcher;
import org.openrewrite.java.tree.*;

import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Set;

import static java.util.stream.Collectors.toList;
import static org.openrewrite.Tree.randomId;

@EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
public class RemoveImport extends JavaRefactorVisitor {
    @EqualsAndHashCode.Include
    private final String clazz;

    private final MethodMatcher methodMatcher;

    private final JavaType.Class classType;

    private J.Import namedImport;
    private J.Import starImport;
    private J.Import staticStarImport;

    private final Set<String> referencedTypes = new HashSet<>();
    private final Set<J.Ident> referencedMethods = new HashSet<>();
    private final Set<String> referencedFields = new HashSet<>();
    private final Set<J.Import> staticNamedImports = Collections.newSetFromMap(new IdentityHashMap<>());

    public RemoveImport(String clazz) {
        super("java.RemoveImport", "class.type", clazz);
        this.clazz = clazz;
        this.methodMatcher = new MethodMatcher(clazz + " *(..)");
        this.classType = JavaType.Class.build(clazz);
        setCursoringOn();
    }

    @Override
    public J visitCompilationUnit(J.CompilationUnit cu) {
        J.CompilationUnit c = refactor(cu, super::visitCompilationUnit);
        return staticImportDeletions(classImportDeletions(c));
    }

    @Override
    public J visitImport(J.Import impoort) {
        if (impoort.isStatic()) {
            if (impoort.getQualid().getTarget().printTrimmed().equals(clazz)) {
                if ("*".equals(impoort.getQualid().getSimpleName())) {
                    staticStarImport = impoort;
                } else {
                    staticNamedImports.add(impoort);
                }
            }
        } else {
            if (impoort.getQualid().printTrimmed().equals(clazz)) {
                namedImport = impoort;
            } else if ("*".equals(impoort.getQualid().getSimpleName()) && clazz.startsWith(impoort.getQualid().getTarget().printTrimmed())) {
                starImport = impoort;
            }
        }

        return super.visitImport(impoort);
    }

    @Override
    public J visitTypeName(NameTree name) {
        JavaType.Class asClass = TypeUtils.asClass(name.getType());
        if (asClass != null && asClass.getPackageName().equals(classType.getPackageName()) &&
                getCursor().getPathAsStream().noneMatch(J.Import.class::isInstance)) {
            referencedTypes.add(asClass.getFullyQualifiedName());
        }
        return super.visitTypeName(name);
    }

    @Override
    public J visitIdentifier(J.Ident ident) {
        if (getCursor().getPathAsStream().noneMatch(J.Import.class::isInstance)) {
            referencedFields.add(ident.getSimpleName());
        }
        return super.visitIdentifier(ident);
    }

    @Override
    public J visitMethodInvocation(J.MethodInvocation method) {
        if (methodMatcher.matches(method) && method.getType() != null &&
                method.getType().getDeclaringType().getFullyQualifiedName().equals(clazz)) {
            referencedMethods.add(method.getName());
        }
        return super.visitMethodInvocation(method);
    }

    private J.CompilationUnit classImportDeletions(J.CompilationUnit cu) {
        if (namedImport != null && referencedTypes.stream().noneMatch(t -> t.equals(clazz))) {
            return delete(cu, namedImport);
        } else if (starImport != null && referencedTypes.isEmpty()) {
            return delete(cu, starImport);
        } else if (starImport != null && referencedTypes.size() == 1) {
            return cu.withImports(cu.getImports().stream().map(i -> i == starImport ?
                    new J.Import(randomId(), TreeBuilder.buildName(referencedTypes.iterator().next(),
                            Formatting.format(" ")), false, i.getFormatting()) :
                    i
            ).collect(toList()));
        } else {
            return cu;
        }
    }

    private J.CompilationUnit staticImportDeletions(J.CompilationUnit cu) {
        if (staticStarImport != null) {
            var qualidType = TypeUtils.asClass(staticStarImport.getQualid().getTarget().getType());
            if (referencedMethods.isEmpty() && noFieldReferences(qualidType, null)) {
                cu = delete(cu, staticStarImport);
            }
        }

        for (J.Import staticImport : staticNamedImports) {
            var methodOrField = staticImport.getQualid().getSimpleName();
            var qualidType = TypeUtils.asClass(staticImport.getQualid().getTarget().getType());
            if (referencedMethods.stream().noneMatch(m -> m.getSimpleName().equals(methodOrField)) &&
                    noFieldReferences(qualidType, methodOrField)) {
                cu = delete(cu, staticImport);
            }
        }

        return cu;
    }

    private boolean noFieldReferences(@Nullable JavaType.Class qualidType, @Nullable String fieldName) {
        return qualidType == null || (
                fieldName != null ? !referencedFields.contains(fieldName) :
                        referencedFields.stream().noneMatch(f -> qualidType.getMembers().stream().anyMatch(v -> f.equals(v.getName())) ||
                                qualidType.getVisibleSupertypeMembers().stream().anyMatch(v -> f.equals(v.getName())))
        );
    }

    private J.CompilationUnit delete(J.CompilationUnit cu, J.Import impoort) {
        return cu.withImports(cu.getImports().stream()
                .filter(i -> i != impoort)
                .collect(toList()));
    }
}
