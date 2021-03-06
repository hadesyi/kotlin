/*
 * Copyright 2010-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.descriptors;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.ReadOnly;
import org.jetbrains.kotlin.resolve.scopes.MemberScope;
import org.jetbrains.kotlin.types.KotlinType;
import org.jetbrains.kotlin.types.TypeProjection;
import org.jetbrains.kotlin.types.TypeSubstitution;
import org.jetbrains.kotlin.types.TypeSubstitutor;

import java.util.Collection;
import java.util.List;

public interface ClassDescriptor extends ClassifierDescriptorWithTypeParameters, MemberDescriptor, ClassOrPackageFragmentDescriptor {
    @NotNull
    MemberScope getMemberScope(@NotNull List<? extends TypeProjection> typeArguments);

    @NotNull
    MemberScope getMemberScope(@NotNull TypeSubstitution typeSubstitution);

    @NotNull
    MemberScope getUnsubstitutedMemberScope();

    @NotNull
    MemberScope getUnsubstitutedInnerClassesScope();

    @NotNull
    MemberScope getStaticScope();

    @NotNull
    @ReadOnly
    Collection<ConstructorDescriptor> getConstructors();

    @Override
    @NotNull
    DeclarationDescriptor getContainingDeclaration();

    /**
     * @return type A&lt;T&gt; for the class A&lt;T&gt;
     */
    @NotNull
    @Override
    KotlinType getDefaultType();

    @NotNull
    @Override
    ClassDescriptor substitute(@NotNull TypeSubstitutor substitutor);

    /**
     * @return nested object declared as 'companion' if one is present.
     */
    @Nullable
    ClassDescriptor getCompanionObjectDescriptor();

    @NotNull
    ClassKind getKind();

    @Override
    @NotNull
    Modality getModality();

    @Override
    @NotNull
    Visibility getVisibility();

    boolean isCompanionObject();

    boolean isData();

    @NotNull
    ReceiverParameterDescriptor getThisAsReceiverParameter();

    @Nullable
    ConstructorDescriptor getUnsubstitutedPrimaryConstructor();

    /**
     * It may differ from 'typeConstructor.parameters' in current class is inner, 'typeConstructor.parameters' contains
     * captured parameters from outer declaration.
     * @return list of type parameters actually declared type parameters in current class
     */
    @Override
    @ReadOnly
    @NotNull
    List<TypeParameterDescriptor> getDeclaredTypeParameters();

    @NotNull
    @Override
    ClassDescriptor getOriginal();
}
