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

package org.jetbrains.kotlin.codegen.coroutines

import org.jetbrains.kotlin.codegen.*
import org.jetbrains.kotlin.codegen.optimization.MandatoryMethodTransformer
import org.jetbrains.kotlin.codegen.optimization.SKIP_MANDATORY_TRANSFORMATIONS_ANNOTATION_DESC
import org.jetbrains.kotlin.codegen.optimization.common.OptimizationBasicInterpreter
import org.jetbrains.kotlin.codegen.optimization.common.asSequence
import org.jetbrains.kotlin.codegen.optimization.transformer.MethodTransformer
import org.jetbrains.kotlin.resolve.jvm.AsmTypes
import org.jetbrains.kotlin.resolve.jvm.diagnostics.JvmDeclarationOrigin
import org.jetbrains.org.objectweb.asm.Label
import org.jetbrains.org.objectweb.asm.MethodVisitor
import org.jetbrains.org.objectweb.asm.Opcodes
import org.jetbrains.org.objectweb.asm.Type
import org.jetbrains.org.objectweb.asm.commons.InstructionAdapter
import org.jetbrains.org.objectweb.asm.commons.Method
import org.jetbrains.org.objectweb.asm.tree.*
import org.jetbrains.org.objectweb.asm.tree.analysis.BasicValue

class CoroutineTransformationClassBuilder(private val delegate: ClassBuilder) : DelegatingClassBuilder() {
    override fun getDelegate() = delegate

    override fun newMethod(
            origin: JvmDeclarationOrigin,
            access: Int, name: String,
            desc: String, signature:
            String?,
            exceptions: Array<out String>?
    ) = CoroutineTransformerMethodVisitor(
                delegate.newMethod(origin, access, name, desc, signature, exceptions),
                access, name, desc, signature, exceptions, this)
}

class CoroutineTransformerClassBuilderFactory(delegate: ClassBuilderFactory) : DelegatingClassBuilderFactory(delegate) {
    override fun newClassBuilder(origin: JvmDeclarationOrigin) = CoroutineTransformationClassBuilder(delegate.newClassBuilder(origin))
}

class CoroutineTransformerMethodVisitor(
        delegate: MethodVisitor,
        access: Int,
        name: String,
        desc: String,
        signature: String?,
        exceptions: Array<out String>?,
        private val classBuilder: ClassBuilder
) : TransformationMethodVisitor(delegate, access, name, desc, signature, exceptions) {
    override fun performTransformations(methodNode: MethodNode) {
        if (!(methodNode.visibleAnnotations?.removeAll { it.desc == CONTINUATION_METHOD_ANNOTATION_DESC } ?: false)) return

        MandatoryMethodTransformer().transform("fake", methodNode)
        methodNode.visibleAnnotations.add(AnnotationNode(SKIP_MANDATORY_TRANSFORMATIONS_ANNOTATION_DESC))

        val suspensionPoints = collectSuspensionPoints(methodNode)

        spillVariables(suspensionPoints, methodNode)

        val suspensionPointLabels = suspensionPoints.map {
            transformCallAndReturnContinuationLabel(it, methodNode)
        }

        val startLabel = LabelNode()
        val defaultLabel = LabelNode()
        val tableSwitch =
                TableSwitchInsnNode(0,
                                    suspensionPoints.map { it.id }.max()!!,
                                    defaultLabel,
                                    *(arrayOf(startLabel) + suspensionPointLabels))

        methodNode.instructions.apply {
            insertBefore(first,
                         insnListOf(
                                 VarInsnNode(Opcodes.ALOAD, 0),
                                 FieldInsnNode(
                                         Opcodes.GETFIELD, classBuilder.thisName, COROUTINE_LABEL_FIELD_NAME, Type.INT_TYPE.descriptor),
                                 tableSwitch, startLabel))
            insert(last,
                   insnListOf(
                           defaultLabel,
                           // TODO: throw some sensible exception instead of NPE
                           InsnNode(Opcodes.ACONST_NULL),
                           MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "hashCode", "()I", false),
                           InsnNode(Opcodes.RETURN)))
        }

    }

    private fun collectSuspensionPoints(methodNode: MethodNode): List<SuspensionPointDescriptor> {
        val suspensionPoints = mutableListOf<SuspensionPointDescriptor>()

        for (methodInsn in methodNode.instructions.asSequence().filterIsInstance<MethodInsnNode>()) {
            if (methodInsn.owner != SUSPENSION_POINT_MARKER_OWNER) continue

            when (methodInsn.name) {
                SUSPENSION_POINT_MARKER_NAME -> {
                    assert(methodInsn.next is MethodInsnNode) {
                        "Expected method call instruction after suspension point, but ${methodInsn.next} found"
                    }

                    suspensionPoints.add(SuspensionPointDescriptor(suspensionPoints.size + 1, methodInsn.next as MethodInsnNode))
                }

                else -> error("Unexpected suspension point marker kind '${methodInsn.name}'")
            }
        }

        // Drop markers
        suspensionPoints.forEach { methodNode.instructions.remove(it.suspensionCall.previous) }

        return suspensionPoints
    }

    private fun spillVariables(suspensionPoints: List<SuspensionPointDescriptor>, methodNode: MethodNode) {
        val instructions = methodNode.instructions
        val frames = MethodTransformer.analyze("fake", methodNode, OptimizationBasicInterpreter())
        fun AbstractInsnNode.index() = instructions.indexOf(this)

        // We postpone these actions because they change instruction indices that we use when obtaining frames
        val postponedActions = mutableListOf<() -> Unit>()
        val maxVarsCountByType = mutableMapOf<Type, Int>()

        for (suspension in suspensionPoints) {
            val call = suspension.suspensionCall
            assert(frames[call.next.index()].stackSize == Method("fake", call.desc).returnType.size) {
                "Stack should be spilled before suspension call"
            }

            val frame = frames[call.index()]
            val localsCount = frame.locals
            val varsCountByType = mutableMapOf<Type, Int>()
            // 0 - this
            // 1 - continuation argument
            val variablesToSpill =
                    (2 until localsCount).map { Pair(it, frame.getLocal(it)) }.filter { it.second != BasicValue.UNINITIALIZED_VALUE }

            for ((index, value) in variablesToSpill) {
                val type = value.type
                val normalizedType = type.normalize()

                val indexBySort = varsCountByType[normalizedType]?.plus(1) ?: 0
                varsCountByType[normalizedType] = indexBySort

                val fieldName = normalizedType.fieldNameForVar(indexBySort)

                postponedActions.add {
                    instructions.apply {
                        // store variable before suspension call
                        insertBefore(call, VarInsnNode(Opcodes.ALOAD, 0))
                        insertBefore(call, VarInsnNode(type.getOpcode(Opcodes.ILOAD), index))
                        insertBefore(call, coercionInsns(type, normalizedType))
                        insertBefore(call, FieldInsnNode(Opcodes.PUTFIELD, classBuilder.thisName, fieldName, normalizedType.descriptor))

                        // restore variable after suspension call
                        val nextInsnAfterCall = call.next
                        insertBefore(nextInsnAfterCall, VarInsnNode(Opcodes.ALOAD, 0))
                        insertBefore(nextInsnAfterCall,
                                     FieldInsnNode(Opcodes.GETFIELD, classBuilder.thisName, fieldName, normalizedType.descriptor))
                        insertBefore(nextInsnAfterCall, coercionInsns(normalizedType, type))
                        insertBefore(nextInsnAfterCall, VarInsnNode(type.getOpcode(Opcodes.ISTORE), index))
                    }
                }
            }

            varsCountByType.forEach {
                maxVarsCountByType[it.key] = Math.max(maxVarsCountByType[it.key] ?: 0, it.value)
            }
        }

        postponedActions.forEach(Function0<Unit>::invoke)

        maxVarsCountByType.forEach { entry ->
            val (type, maxIndex) = entry
            for (index in 0..maxIndex) {
                classBuilder.newField(
                        JvmDeclarationOrigin.NO_ORIGIN, Opcodes.ACC_PRIVATE or Opcodes.ACC_VOLATILE,
                        type.fieldNameForVar(index), type.descriptor, null, null)
            }
        }
    }

    private fun transformCallAndReturnContinuationLabel(suspension: SuspensionPointDescriptor, methodNode: MethodNode): LabelNode {
        val call = suspension.suspensionCall
        val method = Method(call.name, call.desc)
        val newParameters = method.argumentTypes + CONTINUATION_INTERFACE_ASM_TYPE

        call.desc = Method(method.name, Type.VOID_TYPE, newParameters).descriptor

        val continuationLabel = LabelNode()

        with(methodNode.instructions) {
            // Save state
            insertBefore(call,
                         insnListOf(
                                 VarInsnNode(Opcodes.ALOAD, 0),
                                 *withInstructionAdapter { iconst(suspension.id) }.toArray(),
                                 FieldInsnNode(
                                         Opcodes.PUTFIELD, classBuilder.thisName, COROUTINE_LABEL_FIELD_NAME, Type.INT_TYPE.descriptor)))

            // Pass continuation
            insertBefore(call, VarInsnNode(Opcodes.ALOAD, 0))

            val nextInsnAfterCall = call.next

            // Exit
            insertBefore(nextInsnAfterCall, InsnNode(Opcodes.RETURN))

            // Mark place for continuation
            insertBefore(nextInsnAfterCall, continuationLabel)

            // Check if resumeWithException has been called
            insertBefore(nextInsnAfterCall, withInstructionAdapter {
                load(2, AsmTypes.OBJECT_TYPE)
                dup()
                val noExceptionLabel = Label()
                ifnull(noExceptionLabel)
                athrow()

                mark(noExceptionLabel)
                pop()
            })

            // Load continuation argument just like suspending function returns it
            insertBefore(nextInsnAfterCall, VarInsnNode(Opcodes.ALOAD, 1))
            insertBefore(nextInsnAfterCall, coercionInsns(AsmTypes.OBJECT_TYPE, method.returnType))
        }

        return continuationLabel
    }
}

private fun Type.fieldNameForVar(index: Int) = descriptor.first() + "$" + index

private fun coercionInsns(from: Type, to: Type) = withInstructionAdapter { StackValue.coerce(from, to, this) }

private fun withInstructionAdapter(block: InstructionAdapter.() -> Unit): InsnList {
    val tmpMethodNode = MethodNode()

    InstructionAdapter(tmpMethodNode).apply(block)

    return tmpMethodNode.instructions
}

private fun Type.normalize() =
    when (sort) {
        Type.ARRAY, Type.OBJECT -> AsmTypes.OBJECT_TYPE
        else -> this
    }

private class SuspensionPointDescriptor(val id: Int, val suspensionCall: MethodInsnNode)

private fun insnListOf(vararg insns: AbstractInsnNode) = InsnList().apply { insns.forEach { add(it) } }
