package com.github.kotlinisland.inspectionsforlinters.settings

import com.github.kotlinisland.inspectionsforlinters.StaticInfo
import com.github.kotlinisland.inspectionsforlinters.UserInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.*
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MasterDetailsComponent
import com.intellij.openapi.ui.NamedConfigurable
import com.intellij.util.IconUtil.addIcon
import com.intellij.util.PlatformIcons
import com.intellij.util.ui.JBInsets
import com.intellij.util.ui.JBUI
import com.intellij.util.xmlb.XmlSerializerUtil
import java.awt.Insets

class A(var config: Config, updater: Runnable) : NamedConfigurable<Config>(true, updater) {
    val myConfigComponent = ConfigComponent(config)

    override fun isModified() = myConfigComponent.isModified

    override fun apply() = myConfigComponent.apply()

    override fun getDisplayName(): String {
        return config.name
    }

    override fun setDisplayName(name: String) {
        config.name = name
    }

    override fun getEditableObject() = config

    override fun getBannerSlogan(): String {
        return "Not yet implemented"
    }

    override fun createOptionsPanel() = myConfigComponent.createComponent().apply {
        border = JBUI.Borders.empty(11, 16, 16, 16)
    }

}

lateinit var proj: Project

class Configurable(project: Project) : MasterDetailsComponent() {
    val settings = Settings.getInstance(project)
    var myModified: Boolean = false
    override fun getDisplayName(): String {
        return "hello :)"
    }

    init {
        proj = project
        initTree()
    }

    override fun initTree() {
        super.initTree()
        for (config in settings.entries) {
            val node = MyNode(A(config, TREE_UPDATER))
            addNode(node, myRoot)
        }
        myTree.emptyText.text = "Why are you so slow?"
    }

    override fun apply() {
        super.apply()
        settings.entries.clear()
        repeat(myRoot.childCount) { i ->
            val node = myRoot.getChildAt(i) as MyNode
            settings.entries.add((node.configurable as A).config)
        }
        myModified = false
    }

    override fun isModified(): Boolean {
        return super.isModified() || myModified
    }

    override fun createActions(fromPopup: Boolean): MutableList<AnAction> {
        return mutableListOf(
            object : DumbAwareAction({ "Add" }, addIcon) {
                // TODO: from template
                override fun actionPerformed(e: AnActionEvent) {
                    val node = MyNode(A(Config("New Tool", strategy = InspectionStrategy.OnSave), TREE_UPDATER))
                    addNode(node, myRoot)
                    myModified = true
                    selectNodeInTree(node, true)
                }
            },
            MyDeleteAction(),
            object : DumbAwareAction({ "Copy" }, PlatformIcons.COPY_ICON) {
                override fun actionPerformed(e: AnActionEvent) {
                    val clone = A((selectedObject as Config).copy(), TREE_UPDATER)
                    clone.config.name = "New config"
                    addNode(MyNode(clone), myRoot)
                    myModified = true
                }

                override fun update(event: AnActionEvent) {
                    event.presentation.isEnabled = selectedObject != null
                }

                override fun getActionUpdateThread() = ActionUpdateThread.EDT
            },
        )
    }

    override fun getEmptySelectionString() = "Why are you so slow?"

    override fun wasObjectStored(editableObject: Any?) = true
}

enum class InspectionStrategy { TempFile, OnSave, TriggerSave }


data class Config(
    var name: String = "",
    var exec: String = "",
    var args: MutableList<String> = mutableListOf(),
    var filters: MutableMap<Regex, HighlightSeverity> = mutableMapOf(),
    var strategy: InspectionStrategy = InspectionStrategy.OnSave,
    // TODO: Proper scoping
    var scope: List<String> = listOf(),
    var ignore: String = "",
)

@Service(Service.Level.PROJECT)
@State(name = "linterSettings", storages = [Storage("linterSettings.xml")])
class Settings : PersistentStateComponent<Settings> {

    var entries: MutableList<Config> = mutableListOf(mypyConfig, pylintConfig, robotDryRunConfig, robocopConfig)

    companion object {
        fun getInstance(project: Project): Settings = project.service()
    }

    override fun getState() = this

    override fun loadState(state: Settings) {
        XmlSerializerUtil.copyBean(state, this)
    }
}

val pythonScope = listOf(".py", ".pyi")

val mypyConfig = Config(
    "mypy",
    exec = "mypy",
    args = mutableListOf(
        "--no-error-summary",
        "--no-pretty",
        "--hide-error-context",
        "--show-error-end",
        "--follow-imports=silent"
    ),

    // test.py:1:5:1:6: error: Unsupported operand types for + ("int" and "str")  [operator]
    filters = mutableMapOf(
        Regex("""[^:]*:(?<line>\d+):(?<column>\d+):(?<lineEnd>\d+):(?<columnEnd>\d+): error: (?<message>.+) {2}\[(?<code>.+)]""") to HighlightSeverity.ERROR,
        Regex("""[^:]*:(?<line>\d+): error: (?<message>.+) {2}\[(?<code>.+)]""") to HighlightSeverity.ERROR,
        Regex("""[^:]*:(?<line>\d+):(?<column>\d+):(\d+):(\d+): note: (?<message>.+)""") to UserInfo,
    ),
    strategy = InspectionStrategy.TempFile,
    scope = pythonScope,
    ignore = "type: ignore[%]",
)

val pylintConfig = Config(
    "pylint",
    exec = "pylint",
    args = mutableListOf(""),

    // test.py:16:18: E0601: Using variable 'x' before assignment (used-before-assignment)
    filters = mutableMapOf(
        Regex(""".*:(?<line>\d+):(?<column>\d+): (.+): (?<message>.*) \((?<code>.+)\)""") to HighlightSeverity.ERROR,
    ),
    strategy = InspectionStrategy.OnSave,
    scope = pythonScope,
    ignore = "pylint: disable=%"
)

val robotDryRunConfig = Config(
    "robot dryrun",

    exec = "robot",
    args = mutableListOf("--dryrun", "--console", "None"),

    filters = mutableMapOf(
        Regex(""".*:(?<line>\d+)(?<message>.*)""") to HighlightSeverity.ERROR,
        Regex("""Warn: (?<message>.*)""") to HighlightSeverity.WARNING,
        Regex("""Error: .* on line (?<line>\d+): (?<message>.*)""") to HighlightSeverity.ERROR,
        Regex("""(?<line>\d+)(?<message>.*)""") to HighlightSeverity.ERROR,
    ),
    strategy = InspectionStrategy.OnSave,
    scope = listOf(".robot"),
)

val robocopConfig = Config(
    "robocop",

    exec = "robocop",
    args = mutableListOf(),

    filters = mutableMapOf(
        Regex(""".*:(?<line>\d+):(?<column>\d+) \[E] (?<message>.+) \((?<code>.+)\)""") to HighlightSeverity.ERROR,
        Regex(""".*:(?<line>\d+):(?<column>\d+) \[W] (?<message>.+) \((?<code>.+)\)""") to HighlightSeverity.WARNING,
        Regex(""".*:(?<line>\d+):(?<column>\d+) \[I] (?<message>.+) \((?<code>.+)\)""") to UserInfo,
    ),
    strategy = InspectionStrategy.OnSave,
    scope = listOf(".robot", ".resource"),
)
