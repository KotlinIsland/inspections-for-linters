package com.github.kotlinisland.inspectionsforlinters.settings

import com.intellij.diff.tools.external.ExternalDiffSettings
import com.intellij.ide.IdeBundle
import com.intellij.ide.browsers.BrowserFamily
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.ComboBoxTableRenderer
import com.intellij.ui.dsl.builder.*
import com.intellij.ui.dsl.gridLayout.Gaps
import com.intellij.util.Function
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.LocalPathCellEditor
import com.intellij.util.ui.table.IconTableCellRenderer
import com.intellij.util.ui.table.TableModelEditor
import javax.swing.JComponent
import javax.swing.event.TableModelEvent
import javax.swing.table.TableCellEditor
import javax.swing.table.TableCellRenderer

class Filter(
        var pattern: String = """(.*?:(?<line>\d+):(?<message>.*)""",
        var severity: String = "ERROR",
)

class ConfigComponent(val config: Config) : BoundConfigurable("amon gus") {

    private lateinit var filtersTable: JComponent
    private lateinit var filtersEditor: TableModelEditor<Filter>

    override fun createPanel() = panel {
        val itemEditor: TableModelEditor.DialogItemEditor<Filter> = object :
                TableModelEditor.DialogItemEditor<Filter> {
            override fun getItemClass(): Class<Filter> {
                return Filter::class.java
            }

            override fun clone(item: Filter, forInPlaceEditing: Boolean): Filter {
                return Filter(item.pattern, item.severity)
            }

            override fun edit(
                    browser: Filter,
                    mutator: Function<in Filter, out Filter>,
                    isAdd: Boolean
            ) {
                return
            }

            override fun applyEdited(oldItem: Filter, newItem: Filter) {
//                oldItem.specificSettings = newItem.specificSettings
            }

        }
        filtersEditor = TableModelEditor(COLUMNS, itemEditor, "Why are you so slow?")
                .modelListener(object : TableModelEditor.DataChangedListener<Filter?>() {
                    override fun tableChanged(event: TableModelEvent) {
                        update()
                    }

                    override fun dataChanged(columnInfo: ColumnInfo<Filter?, *>, rowIndex: Int) {
                        if (columnInfo === PATH_COLUMN_INFO || columnInfo === ACTIVE_COLUMN_INFO) {
                            update()
                        }
                    }

                    private fun update() {

                        println("bruh")
//                    if (defaultBrowser == DefaultBrowserPolicy.FIRST) {
//                        setCustomPathToFirstListed()
//                    }
                    }
                })
        row("Executable:") {
            // TODO: autodetect vs hard code, maybe a checkbox or a "resolve" button
            // TODO: mirror name of config until this is modified
            // TODO: Path picker button
            textField().bindText(config::exec).align(AlignX.FILL)
        }
        row("Arguments:") {
            // TODO: use the text field from run configs that handles args
            textField().bindText(
                    { config.args.joinToString(" ") },
                    { config.args = it.split(" ").toMutableList() },
            ).align(AlignX.FILL)
        }
        row("Strategy:") {
            comboBox(InspectionStrategy.values().asList()).bindItem(config::strategy.toNullableProperty())
        }
        row("ignore template:") {
            textField()
        }

        row {
            filtersTable = cell(filtersEditor.createComponent())
                    .align(Align.FILL)
                    .label("Filters: (WIP)", LabelPosition.TOP)
                    .component
        }
    }

    companion object {
        private val APP_FILE_CHOOSER_DESCRIPTOR =
                FileChooserDescriptorFactory.createSingleFileOrExecutableAppDescriptor()
        private val PATH_COLUMN_INFO: TableModelEditor.EditableColumnInfo<Filter, String> =
                object : TableModelEditor.EditableColumnInfo<Filter, String>(
                        IdeBundle.message("settings.browsers.column.path")
                ) {
                    override fun valueOf(item: Filter): String? {
                        return "who asked?" // item.PathUtil.toSystemDependentName(item.path)
                    }

                    override fun setValue(item: Filter, value: String) {
//                item.path = value
                    }

                    override fun getEditor(item: Filter): TableCellEditor? {
                        return LocalPathCellEditor().fileChooserDescriptor(APP_FILE_CHOOSER_DESCRIPTOR).normalizePath(true)
                    }
                }
        private val ACTIVE_COLUMN_INFO: TableModelEditor.EditableColumnInfo<Filter, Boolean> =
                object : TableModelEditor.EditableColumnInfo<Filter, Boolean>() {
                    override fun getColumnClass(): Class<*> {
                        return Boolean::class.java
                    }

                    override fun valueOf(item: Filter): Boolean {
                        return true
//                    return item.isActive
                    }

                    override fun setValue(item: Filter, value: Boolean) {
//                    item.isActive = value
                    }
                }

        private val COLUMNS = arrayOf<ColumnInfo<*, *>>(
                object : TableModelEditor.EditableColumnInfo<Filter, String>(
                        "pattern"
                ) {
                    override fun valueOf(item: Filter): String {
                        return item.pattern
                    }

                    override fun setValue(item: Filter, value: String) {
//                    item.name = value
                    }
                },
                object : TableModelEditor.EditableColumnInfo<Filter, String>(
                        "severity"
                ) {
//                override fun getColumnClass(): Class<*> {
//                    return HighlightSeverity::class.java
//                }

                    override fun valueOf(item: Filter): String {
                        return item.severity
                    }

                    override fun setValue(item: Filter, value: String) {
                        item.severity = value
                    }

                    override fun getEditor(item: Filter) = createComboBoxRendererAndEditor()

                    // TODO: render as they would appear
                    private fun createComboBoxRendererAndEditor() = ComboBoxTableRenderer(
                            arrayOf("error", "warning", "info", "unreachable", "passive info")
                    )
                            .withClickCount(1)
                },

                )
    }
}

