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

package org.jetbrains.kotlin.idea.intentions

import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.refactoring.rename.RenameProcessor
import com.intellij.usageView.UsageInfo
import org.jetbrains.kotlin.descriptors.ParameterDescriptor
import org.jetbrains.kotlin.descriptors.impl.AnonymousFunctionDescriptor
import org.jetbrains.kotlin.idea.caches.resolve.analyze
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.psiUtil.anyDescendantOfType
import org.jetbrains.kotlin.psi.psiUtil.endOffset
import org.jetbrains.kotlin.psi.psiUtil.getParentOfType
import org.jetbrains.kotlin.resolve.DescriptorToSourceUtils
import org.jetbrains.kotlin.resolve.lazy.BodyResolveMode

class ReplaceExplicitFunctionLiteralParamWithItIntention() : PsiElementBaseIntentionAction() {
    override fun getFamilyName() = "Replace explicit lambda parameter with 'it'"

    override fun isAvailable(project: Project, editor: Editor, element: PsiElement): Boolean {
        val functionLiteral = targetFunctionLiteral(element, editor.caretModel.offset) ?: return false

        val parameter = functionLiteral.valueParameters.singleOrNull() ?: return false
        if (parameter.typeReference != null) return false

        if (functionLiteral.anyDescendantOfType<KtFunctionLiteral>() { literal ->
            literal !== functionLiteral &&
            !literal.hasParameterSpecification() &&
            literal.anyDescendantOfType<KtSimpleNameExpression> { nameExpr ->
                nameExpr.getReferencedName() == element.text
            }
        } ) return false

        text = "Replace explicit parameter '${parameter.name}' with 'it'"
        return true
    }

    override fun invoke(project: Project, editor: Editor, element: PsiElement) {
        val caretOffset = editor.caretModel.offset
        val functionLiteral = targetFunctionLiteral(element, editor.caretModel.offset)!!
        val cursorInParameterList = functionLiteral.valueParameterList!!.textRange.containsOffset(caretOffset)
        ParamRenamingProcessor(editor, functionLiteral, cursorInParameterList).run()
    }

    private fun targetFunctionLiteral(element: PsiElement, caretOffset: Int): KtFunctionLiteral? {
        val expression = element.getParentOfType<KtNameReferenceExpression>(true)
        if (expression != null) {
            val target = expression.mainReference.resolveToDescriptors(expression.analyze(BodyResolveMode.PARTIAL))
                                 .singleOrNull() as? ParameterDescriptor ?: return null
            val functionDescriptor = target.containingDeclaration as? AnonymousFunctionDescriptor ?: return null
            return DescriptorToSourceUtils.descriptorToDeclaration(functionDescriptor) as? KtFunctionLiteral
        }

        val functionLiteral = element.getParentOfType<KtFunctionLiteral>(true) ?: return null
        val arrow = functionLiteral.arrow ?: return null
        if (caretOffset > arrow.endOffset) return null
        return functionLiteral
    }

    private class ParamRenamingProcessor(
            val editor: Editor,
            val functionLiteral: KtFunctionLiteral,
            val cursorWasInParameterList: Boolean
    ) : RenameProcessor(editor.project,
                        functionLiteral.valueParameters.single(),
                        "it",
                        false,
                        false
    ) {
        override fun performRefactoring(usages: Array<out UsageInfo>) {
            super.performRefactoring(usages)

            functionLiteral.deleteChildRange(functionLiteral.valueParameterList, functionLiteral.arrow!!)

            if (cursorWasInParameterList) {
                editor.caretModel.moveToOffset(functionLiteral.bodyExpression!!.textOffset)
            }

            val project = functionLiteral.project
            PsiDocumentManager.getInstance(project).doPostponedOperationsAndUnblockDocument(editor.document)
            CodeStyleManager.getInstance(project).adjustLineIndent(functionLiteral.containingFile, functionLiteral.textRange)

        }
    }
}
