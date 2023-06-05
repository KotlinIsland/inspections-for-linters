package com.github.kotlinisland.inspectionsforlinters


import com.github.kotlinisland.inspectionsforlinters.settings.Config
import com.github.kotlinisland.inspectionsforlinters.settings.InspectionStrategy
import com.github.kotlinisland.inspectionsforlinters.settings.Settings
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.io.FileUtilRt
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.testFramework.utils.editor.saveToDisk
import com.intellij.ui.JBColor
import java.awt.Color
import kotlin.concurrent.thread


class AnExternalAnnotator : ExternalAnnotator<Info, MutableMap<Config, List<Result>>>(), DumbAware {
    override fun collectInformation(file: PsiFile) = Info(file)

    override fun doAnnotate(collectedInfo: Info): MutableMap<Config, List<Result>> {
        val file = collectedInfo.file
        fun doIt(config: Config): List<Result> {
            // TODO: Proper scoping
            if (config.scope.none(file.name::endsWith)) {
                return emptyList()
            }
            val project = file.project

            val fileDocumentManager = FileDocumentManager.getInstance()
//            val document = fileDocumentManager.getDocument(file.virtualFile)!!
            var path = file.virtualFile.path
            when (config.strategy) {
                InspectionStrategy.TempFile -> path = FileUtilRt.createTempFile("amnon_gus", null, true).path
//                InspectionStrategy.TriggerSave -> document.saveToDisk()
                InspectionStrategy.TriggerSave -> return listOf(PreservePrevious)
                InspectionStrategy.OnSave -> if (fileDocumentManager.isFileModified(file.virtualFile)) {
                    return listOf(PreservePrevious)
                }
            }

            // TODO: do this in the config settings? maybe "autodetect" does it on the fly?
            val command = config.exec + if (SystemInfo.isWindows) ".exe" else ""
            val exec = ProjectRootManager.getInstance(project).projectSdk?.findExecutableInSDK(command)?.toString()
                    ?: command
            println(exec)
            val a = GeneralCommandLine(exec, *config.args.toTypedArray(), path).withWorkDirectory(project.basePath).withCharset(Charsets.UTF_8)
            fun MatchGroupCollection.named(value: String) = try {
                this[value]?.value
            } catch (_: IllegalArgumentException) {
                null
            }
            // TODO: collect results for each config
            val process = try {
                CapturingProcessHandler(a).runProcess()
            } catch (e: Exception) {
                return emptyList()
            }
            return process.stdoutLines.also(::println).map { outLine ->
                for ((regex, severity) in config.filters.entries) {
                    val groups = (regex.matchEntire(outLine) ?: continue).groups
                    val (line, lineEnd, column, columnEnd) = listOf("line", "lineEnd", "column", "columnEnd").map { groups.named(it)?.toInt() }
                    val code = groups.named("code")
                    val message = groups.named("message")!! + (code?.let { " [$it]" }.orEmpty())
                    return@map Result(line, lineEnd, column, columnEnd, message, code, severity, config.ignore)
                }
                null
            }.filterNotNull()
        }

        val results = mutableMapOf<Config, List<Result>>()
        Settings.getInstance(file.project).entries.map { thread(start = true) { results[it] = doIt(it) } }.map(Thread::join)
        return results
    }

    private val previous = mutableMapOf<PsiFile, MutableMap<Config, List<RaisedAnnotation>>>()

    override fun apply(file: PsiFile, annotationResult: MutableMap<Config, List<Result>>, holder: AnnotationHolder) {
        val document = PsiDocumentManager.getInstance(file.project).getDocument(file) ?: return
        for ((config, annotationResult) in annotationResult) {

            if (config.strategy != InspectionStrategy.OnSave) {
                for (result in annotationResult) {
                    holder.newAnnotation(result.severity, result.message).apply {
                        if (result.line == null) {
                            fileLevel()
                        } else {
                            val firstLineStart = document.getLineStartOffset(result.line - 1)
                            val firstLineEnd = document.getLineEndOffset(result.line - 1)
                            if (result.column == null) range(TextRange(firstLineStart, firstLineEnd))
                            else {
                                if (result.lineEnd == null) {
                                    val start = firstLineStart + result.column - 1
                                    range(file.findElementAt(start)!!.parent)
                                } else {
                                    val start = firstLineStart + result.column - 1
                                    val lastLineStart = document.getLineStartOffset(result.lineEnd - 1)
                                    range(TextRange.from(start + result.column - 1, lastLineStart + result.columnEnd!! - 1))
                                }
                            }

                        }
                    }
                }
                continue
            }


            if (annotationResult.isNotEmpty() && annotationResult.first() is PreservePrevious) {
                previous[file]?.get(config) ?: return
                for (previous in previous[file]!![config]!!) {
                    if (!previous.element.isValid) continue
                    holder.newAnnotation(previous.result.severity, previous.result.message).apply {
                        previous.range?.let {
                            val line = document.getLineNumber(previous.element.textRange.startOffset)
                            val end = document.getLineEndOffset(line)
                            val start = document.getLineStartOffset(line).let { it + document.getText(TextRange(it, end)).indexOfFirst { !it.isWhitespace() } }
                            range(TextRange(start, end))
                        } ?: range(previous.element)

                        if (previous.element is PsiFile) {
                            fileLevel()
                        }

                        when (previous.result.severity) {
                            UserInfo -> enforcedTextAttributes(UserInfoTextAttributes)
                            Unused -> highlightType(UnusedHighlightType)
                            StaticInfo -> enforcedTextAttributes(StaticInfoTextAttributes)
                        }

                        if (previous.result.line != null && previous.result.code != null) {
                            newFix(SuppressIntention(previous.result)).registerFix()
                        }
                    }.create()
                }
                continue
            }

            previous.getOrDefault(file, mutableMapOf())[config] = annotationResult.map { result ->
                val end = if (result.line != null && (result.column == null || result.lineEnd != null && result.lineEnd > result.line)) document.getLineEndOffset(result.line - 1) else null
                val start = if (result.line == null) null else run {
                    (document.getLineStartOffset(result.line - 1) + (result.column ?: 1) - 1).let { start ->
                        if (end != null) {
                            start + document.getText(TextRange(start, end)).indexOfFirst { !it.isWhitespace() }
                        } else start
                    }
                }
                val element = start?.let { file.findElementAt(start)!! }?.parent ?: file
                holder.newAnnotation(result.severity, result.message).apply {
                    if (result.line == null) fileLevel()
                    else end?.let { range(TextRange(start!!, end)) } ?: range(element)

                    when (result.severity) {
                        UserInfo -> enforcedTextAttributes(UserInfoTextAttributes)
                        Unused -> highlightType(UnusedHighlightType)
                        StaticInfo -> enforcedTextAttributes(StaticInfoTextAttributes)
                    }
                    if (result.line != null && result.code != null) {
                        newFix(SuppressIntention(result)).registerFix()
                    }
                }.create()
                RaisedAnnotation(result, element, end?.let { TextRange(start!!, end) })
            }
        }
    }
}

class Info(val file: PsiFile)

data class RaisedAnnotation(
        val result: Result,
        val element: PsiElement,
        val range: TextRange?,
)

open class Result(
        val line: Int?,
        val lineEnd: Int?,
        val column: Int?,
        val columnEnd: Int?,
        val message: String,
        val code: String?,
        val severity: HighlightSeverity,
        val ignore: String? = null,
) {
    override fun toString(): String {
        return "$message, $code, $line:$column"
    }
}


object PreservePrevious : Result(0, 0, 0, 0, "", "", HighlightSeverity.ERROR)

val UserInfo = HighlightSeverity("User Info", 100, { "info" }, { "Info" }, { "Info" })
val UserInfoTextAttributes = TextAttributes().apply { effectType = EffectType.WAVE_UNDERSCORE; effectColor = JBColor.blue }

val StaticInfo = HighlightSeverity("Static Info", 10, { "static info" }, { "Static Info" }, { "Static Info" })

/**
 * Very hacky: 15% from the background color to the foreground color
 */
val StaticInfoTextAttributes = TextAttributes().apply {
    effectType = EffectType.LINE_UNDERSCORE
    effectColor = JBColor.lazy {
        val scheme = EditorColorsManager.getInstance().globalScheme
        val b = scheme.defaultBackground
        val f = scheme.defaultForeground
        val factor = 15
        @Suppress("UseJBColor") Color(
                b.red - (b.red - f.red) / factor,
                b.green - (b.green - f.green) / factor,
                b.blue - (b.blue - f.blue) / factor,
        )
    }
}

val Unused = HighlightSeverity("Unused", 100, { "unused" }, { "Unused" }, { "Unused" })
val UnusedHighlightType = ProblemHighlightType.LIKE_UNUSED_SYMBOL

class SuppressIntention(val result: Result) : IntentionAction {
    override fun startInWriteAction() = true

    override fun getText() = "Add ignore"

    override fun getFamilyName() = "amon gus"

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?) = true

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        val documentManager = PsiDocumentManager.getInstance(project)
        val document = documentManager.getDocument(file!!)!!
        val eol = document.getLineEndOffset(result.line!! - 1)
        // TODO: inject into existing ignore if possible
        document.insertString(eol, "  # ${result.ignore!!.replace("%", result.code!!)}")
        documentManager.commitDocument(document)
    }
}