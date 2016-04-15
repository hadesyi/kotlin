/*
 * Copyright 2010-2016 JetBrains s.r.o.
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

package org.jetbrains.kotlin.idea.quickfix.createFromUsage.createTypeParameter

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.search.searches.ReferencesSearch
import org.jetbrains.kotlin.diagnostics.Diagnostic
import org.jetbrains.kotlin.idea.core.addTypeArgument
import org.jetbrains.kotlin.idea.core.addTypeParameter
import org.jetbrains.kotlin.idea.quickfix.KotlinSingleIntentionActionFactoryWithDelegate
import org.jetbrains.kotlin.idea.quickfix.createFromUsage.CreateFromUsageFixBase
import org.jetbrains.kotlin.idea.refactoring.runSynchronouslyWithProgress
import org.jetbrains.kotlin.idea.util.application.runReadAction
import org.jetbrains.kotlin.idea.util.application.runWriteAction
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.getParentOfTypeAndBranch
import org.jetbrains.kotlin.psi.psiUtil.parents

data class CreateTypeParameterData(val name: String, val declaration: KtTypeParameterListOwner)

object CreateTypeParameterByRefActionFactory : KotlinSingleIntentionActionFactoryWithDelegate<KtUserType, CreateTypeParameterData>() {
    class Fix(
            originalElement: KtUserType,
            private val data: CreateTypeParameterData
    ) : CreateFromUsageFixBase<KtUserType>(originalElement) {
        override fun getText() = "Create type parameter '${data.name}'"

        override fun startInWriteAction() = false

        override fun invoke(project: Project, editor: Editor?, file: KtFile) {
            val declaration = data.declaration
            val ktTypes = project.runSynchronouslyWithProgress("Searching ${declaration.name}...", true) {
                runReadAction {
                    if (declaration is KtClass) {
                        ReferencesSearch
                                .search(declaration)
                                .mapNotNull { it.element.getParentOfTypeAndBranch<KtUserType> { referenceExpression } }
                                .toSet()
                    } else emptySet()
                }
            } ?: return

            runWriteAction {
                val psiFactory = KtPsiFactory(project)

                val starTypeArgument = psiFactory.createTypeArgument("*")
                ktTypes.forEach { it.addTypeArgument(starTypeArgument) }

                declaration.addTypeParameter(psiFactory.createTypeParameter(data.name))
            }
        }
    }

    override fun getElementOfInterest(diagnostic: Diagnostic): KtUserType? {
        val ktUserType = diagnostic.psiElement.getParentOfTypeAndBranch<KtUserType> { referenceExpression } ?: return null
        if (ktUserType.qualifier != null) return null
        return ktUserType
    }

    override fun extractFixData(element: KtUserType, diagnostic: Diagnostic): CreateTypeParameterData? {
        val name = element.referencedName ?: return null
        val declaration = element.parents.firstOrNull {
            it is KtProperty || it is KtNamedFunction || it is KtClass
        } as? KtTypeParameterListOwner ?: return null
        return CreateTypeParameterData(name, declaration)
    }

    override fun createFix(originalElement: KtUserType, data: CreateTypeParameterData) = Fix(originalElement, data)
}