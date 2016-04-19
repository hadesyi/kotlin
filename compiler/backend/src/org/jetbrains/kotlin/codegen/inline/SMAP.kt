/*
 * Copyright 2010-2014 JetBrains s.r.o.
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

package org.jetbrains.kotlin.codegen.inline

import gnu.trove.TIntIntHashMap
import org.jetbrains.kotlin.codegen.ClassBuilder
import org.jetbrains.kotlin.codegen.SourceInfo
import org.jetbrains.kotlin.codegen.inline2.LineNumberToMap
import org.jetbrains.org.objectweb.asm.Label
import org.jetbrains.org.objectweb.asm.MethodVisitor
import java.util.*

//TODO join parameter
class SMAPBuilder(
        val source: String,
        val path: String,
        val fileMappings: List<FileMapping>
) {
    private val header = "SMAP\n$source\nKotlin\n*S Kotlin"

    fun build(): String? {
        var realMappings = fileMappings.filter {
            val mappings = it.lineMappings
            mappings.isNotEmpty() && mappings.first() != RangeMapping.SKIP
        }

        if (realMappings.isEmpty()) {
            return null
        }

        val fileIds = "*F" + realMappings.mapIndexed { id, file -> "\n${file.toSMAPFile(id + 1)}" }.joinToString("")
        val lineMappings = "*L" + realMappings.joinToString("") { it.toSMAPMapping() }

        return "$header\n$fileIds\n$lineMappings\n*E\n"
    }

    private fun RangeMapping.toSMAP(fileId: Int): String {
        return if (range == 1) "$source#$fileId:$dest" else "$source#$fileId,$range:$dest"
    }

    private fun FileMapping.toSMAPFile(id: Int): String {
        this.id = id
        return "+ $id $name\n$path"
    }

    //TODO inline
    private fun FileMapping.toSMAPMapping(): String {
        return lineMappings.joinToString("") { "\n${it.toSMAP(id)}" }
    }
}

open class NestedSourceMapper(
        parent: SourceMapper, val ranges: List<RangeMapping>, sourceInfo: SourceInfo
) : DefaultSourceMapper(sourceInfo, parent) {
    override fun visitLineNumber(iv: MethodVisitor, lineNumber: Int, start: Label) {
        val index = ranges.binarySearch(RangeMapping(lineNumber, lineNumber, 1), Comparator {
            value, key ->
            if (key.dest in value) 0 else RangeMapping.Comparator.compare(value, key)
        })
        if (index < 0) {
            parent!!.visitSource(sourceInfo.source,  sourceInfo.pathOrCleanFQN)
            parent!!.visitLineNumber(iv, lineNumber, start)
        }
        else {
            val rangeMapping = ranges[index]
            parent!!.visitSource(rangeMapping.parent!!.name, rangeMapping.parent!!.path)
            parent!!.visitLineNumber(iv, rangeMapping.mapDestToSource(lineNumber), start)
        }
    }
}

open class InlineLambdaSourceMapper(
        parent: SourceMapper, smap: SMAPAndMethodNode
) : NestedSourceMapper(parent, smap.ranges, smap.classSMAP.sourceInfo) {
    override fun visitSource(name: String, path: String) {
        super.visitSource(name, path)
        if (isOriginalVisited()) {
            parent!!.visitOrigin()
        }
    }

    override fun visitOrigin() {
        super.visitOrigin()
        parent!!.visitOrigin()
    }

    private fun isOriginalVisited(): Boolean {
        return lastVisited == origin
    }

    override fun visitLineNumber(iv: MethodVisitor, lineNumber: Int, start: Label) {
        val index = ranges.binarySearch(RangeMapping(lineNumber, lineNumber, 1), Comparator {
            value, key ->
            if (key.dest in value) 0 else RangeMapping.Comparator.compare(value, key)
        })
        if (index >= 0) {
            val mapping = ranges[index].parent!!
            if (mapping.path == origin.path && mapping.name == origin.name) {
                parent!!.visitOrigin()
                parent!!.visitLineNumber(iv, lineNumber, start)
                return
            }
        }
        super.visitLineNumber(iv, lineNumber, start)
    }
}

interface SourceMapper {
    val resultMappings: List<FileMapping>
    val parent: SourceMapper?
        get() = null

    open fun visitSource(name: String, path: String) {
        throw UnsupportedOperationException("fail")
    }

    open fun visitOrigin() {
        throw UnsupportedOperationException("fail")
    }

    open fun visitLineNumber(iv: MethodVisitor, lineNumber: Int, start: Label) {
        throw UnsupportedOperationException("fail")
    }

    open fun visitLineNumber(iv: MethodVisitor, destLineNumber: Int, start: Label, source: LineNumberToMap): Int {
        throw UnsupportedOperationException("fail")
    }

    open fun endMapping() {
        parent?.visitOrigin()
    }

    companion object {
        fun flushToClassBuilder(mapper: SourceMapper, v: ClassBuilder) {
            for (fileMapping in mapper.resultMappings) {
                v.addSMAP(fileMapping)
            }
        }

        fun createFromSmap(smap: SMAP): SourceMapper {
            val maxUsedIndex = smap.fileMappings.maxBy { it.lineMappings.maxBy { it.maxDest }!!.maxDest }!!.lineMappings.maxBy { it.maxDest }!!.maxDest
            val sourceMapper = org.jetbrains.kotlin.codegen.inline2.DefaultSourceMapper(smap.sourceInfo, maxUsedIndex)
            smap.fileMappings.asSequence()
                    //default one mapped through sourceInfo
                    .filterNot { it == smap.default }
                    .forEach { fileMapping ->
                        sourceMapper.visitSource(fileMapping.name, fileMapping.path)
                        fileMapping.lineMappings.forEach {
                            sourceMapper.lastVisited!!.mapNewInterval(it.source, it.dest, it.range)
                        }
                    }

            return sourceMapper
        }
    }
}

object IdenticalSourceMapper : SourceMapper {
    override val resultMappings: List<FileMapping>
        get() = emptyList()

    override val parent: SourceMapper?
        get() = null

    override fun visitSource(name: String, path: String) {}

    override fun visitOrigin() {}

    override fun visitLineNumber(iv: MethodVisitor, lineNumber: Int, start: Label) {
        iv.visitLineNumber(lineNumber, start)
    }
}

open class DefaultSourceMapper(val sourceInfo: SourceInfo, override val parent: SourceMapper?): SourceMapper {
    protected var maxUsedValue: Int = sourceInfo.linesInFile
    var lastVisited: RawFileMapping? = null
    private var lastMappedWithChanges: RawFileMapping? = null
    private var fileMappings: LinkedHashMap<String, RawFileMapping> = linkedMapOf()
    protected val origin: RawFileMapping

    init {
        val name = sourceInfo.source
        val path = sourceInfo.pathOrCleanFQN
        origin = RawFileMapping(name, path)
        origin.initRange(1, maxUsedValue)
        fileMappings.put(createKey(name, path), origin)
        lastVisited = origin
    }

    private fun createKey(name: String, path: String) = "$name#$path"

    override val resultMappings: List<FileMapping>
        get() = fileMappings.values.map { it.toFileMapping() }

    override fun visitSource(name: String, path: String) {
        lastVisited = fileMappings.getOrPut(createKey(name, path)) { RawFileMapping(name, path) }
    }

    override fun visitOrigin() {
        lastVisited = origin
    }

    override fun visitLineNumber(iv: MethodVisitor, lineNumber: Int, start: Label) {
        if (lineNumber < 0) {
            //no source information, so just skip this linenumber
            return
        }
        val mappedLineIndex = createMapping(lineNumber)
        iv.visitLineNumber(mappedLineIndex, start)
    }

    protected fun createMapping(lineNumber: Int): Int {
        val fileMapping = lastVisited!!
        val mappedLineIndex = fileMapping.mapLine(lineNumber, maxUsedValue, lastMappedWithChanges == lastVisited)
        if (mappedLineIndex > maxUsedValue) {
            lastMappedWithChanges = fileMapping
            maxUsedValue = mappedLineIndex
        }
        return mappedLineIndex
    }
}

class SMAP(val fileMappings: List<FileMapping>) {
    init {
        assert(fileMappings.isNotEmpty()) { "File Mappings shouldn't be empty" }
    }

    val default: FileMapping
        get() = fileMappings.first()

    val intervals = fileMappings.flatMap { it.lineMappings }.sortedWith(RangeMapping.Comparator)

    val sourceInfo: SourceInfo
    init {
        val defaultMapping = default.lineMappings.first()
        sourceInfo = SourceInfo(default.name, default.path, defaultMapping.source + defaultMapping.range - 1)
    }

    companion object {
        val FILE_SECTION = "*F"
        val LINE_SECTION = "*L"
        val END = "*E"
    }
}

class RawFileMapping(val name: String, val path: String) {
    private val lineMappings = TIntIntHashMap()
    private val rangeMappings = arrayListOf<RangeMapping>()

    private var lastMappedWithNewIndex = -1000

    fun toFileMapping() =
            FileMapping(name, path).apply {
                for (range in rangeMappings) {
                    addRangeMapping(range)
                }
            }

    fun initRange(start: Int, end: Int) {
        assert(lineMappings.isEmpty) { "initRange should only be called for empty mapping" }
        for (index in start..end) {
            lineMappings.put(index, index)
        }
        rangeMappings.add(RangeMapping(start, start, end - start + 1))
        lastMappedWithNewIndex = end
    }

    fun mapLine(source: Int, currentIndex: Int, isLastMapped: Boolean): Int {
        var dest = lineMappings[source]
        if (dest == 0) { // line numbers are 1-based, so 0 is ok to indicate missing value
            val rangeMapping: RangeMapping
            if (rangeMappings.isNotEmpty() && isLastMapped && couldFoldInRange(lastMappedWithNewIndex, source)) {
                rangeMapping = rangeMappings.last()
                rangeMapping.range += source - lastMappedWithNewIndex
                dest = lineMappings[lastMappedWithNewIndex] + source - lastMappedWithNewIndex
            }
            else {
                dest = currentIndex + 1
                rangeMapping = RangeMapping(source, dest)
                rangeMappings.add(rangeMapping)
            }

            lineMappings.put(source, dest)
            lastMappedWithNewIndex = source
        }

        return dest
    }

    fun mapNewInterval(source: Int, dest: Int, range: Int) {
        val rangeMapping = RangeMapping(source, dest, range)
        rangeMappings.add(rangeMapping)

        (source..(source + range - 1)).forEach {
            lineMappings.put(source, dest)
        }
    }

    private fun couldFoldInRange(first: Int, second: Int): Boolean {
        //TODO
        val delta = second - first
        return delta > 0 && delta <= 10
    }
}

open class FileMapping(val name: String, val path: String) {
    val lineMappings = arrayListOf<RangeMapping>()
    var id = -1

    fun addRangeMapping(lineMapping: RangeMapping) {
        lineMappings.add(lineMapping)
        lineMapping.parent = this
    }

    object SKIP : FileMapping("no-source-info", "no-source-info") {
        init {
            addRangeMapping(RangeMapping.SKIP)
        }
    }
}

//TODO comparable
data class RangeMapping(val source: Int, val dest: Int, var range: Int = 1) {
    var parent: FileMapping? = null
    private val skip = source == -1 && dest == -1

    val maxDest: Int
        get() = dest + range - 1

    operator fun contains(destLine: Int): Boolean {
        return if (skip) true else dest <= destLine && destLine < dest + range
    }

    fun mapDestToSource(destLine: Int): Int {
        return if (skip) -1 else source + (destLine - dest)
    }

    fun mapSourceToDest(sourceLine: Int): Int {
        return if (skip) -1 else dest + (sourceLine - source)
    }

    object Comparator : java.util.Comparator<RangeMapping> {
        override fun compare(o1: RangeMapping, o2: RangeMapping): Int {
            if (o1 == o2) return 0

            val res = o1.dest - o2.dest
            if (res == 0) {
                return o1.range - o2.range
            }
            else {
                return res
            }
        }
    }

    companion object {
        val SKIP = RangeMapping(-1, -1, 1)
    }
}
