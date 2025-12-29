package com.github.l34130.mise.core.setting

import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.textFieldWithHistoryWithBrowseButton
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.COLUMNS_MEDIUM
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.layout.selected
import com.intellij.util.application
import javax.swing.JComponent

class MiseConfigurable(
    private val project: Project,
) : SearchableConfigurable {
    private val myMiseExecutableTf =
        textFieldWithHistoryWithBrowseButton(
            project = project,
            fileChooserDescriptor = FileChooserDescriptor(true, false, false, false, false, false).withTitle("Select Mise Executable"),
            historyProvider = { listOf("/opt/homebrew/bin/mise").distinct() },
        )
    private val myProjectMiseExecutableTf =
        textFieldWithHistoryWithBrowseButton(
            project = project,
            fileChooserDescriptor = FileChooserDescriptor(true, false, false, false, false, false).withTitle("Select Mise Executable"),
            historyProvider = { listOf("/opt/homebrew/bin/mise").distinct() },
        )
    private val myMiseDirEnvCb = JBCheckBox("Use environment variables from mise")
    private val myMiseConfigEnvironmentTf = JBTextField()
    private val myMiseVcsCb = JBCheckBox("Enable VCS Integration")

    override fun getDisplayName(): String = "Mise Settings"

    override fun createComponent(): JComponent {
        val applicationSettings = application.service<MiseApplicationSettings>()
        val projectSettings = project.service<MiseProjectSettings>()

        myMiseExecutableTf.setTextAndAddToHistory(applicationSettings.state.executablePath)
        myProjectMiseExecutableTf.setTextAndAddToHistory(projectSettings.state.executablePath)
        myMiseDirEnvCb.isSelected = projectSettings.state.useMiseDirEnv
        myMiseConfigEnvironmentTf.text = projectSettings.state.miseConfigEnvironment
        myMiseVcsCb.isSelected = projectSettings.state.useMiseVcsIntegration

        return panel {
            group("Application Settings", indent = false) {
                row("Mise Executable:") {
                    cell(myMiseExecutableTf)
                        .align(AlignX.FILL)
                        .resizableColumn()
                        .comment(
                            """
                            Optional: Override mise executable path for all projects.</br>
                            Leave empty to auto-detect 'mise' from PATH or common locations (recommended).</br>
                            For WSL: <code>\\wsl.localhost\DistroName\path\to\mise</code></br>
                            Not installed? Visit the <a href='https://mise.jdx.dev/installing-mise.html'>mise installation</a>
                            """.trimIndent(),
                        )
                }
            }

            group("Project Settings", indent = false) {
                row("Mise Executable Override:") {
                    cell(myProjectMiseExecutableTf)
                        .align(AlignX.FILL)
                        .resizableColumn()
                        .comment(
                            """
                            Optional: Override mise executable for this project only.</br>
                            Leave empty to use application setting or PATH default.</br>
                            For WSL projects, use UNC path: <code>\\wsl.localhost\DistroName\path\to\mise</code>
                            """.trimIndent(),
                        )
                }

                panel {
                    indent {
                        row {
                            cell(myMiseDirEnvCb)
                                .resizableColumn()
                                .align(AlignX.FILL)
                                .comment("Load environment variables from mise configuration file(s)")
                        }
                        indent {
                            row("Config Environment:") {
                                cell(myMiseConfigEnvironmentTf)
                                    .columns(COLUMNS_MEDIUM)
                                    .comment(
                                        """
                                        Specify the mise configuration environment to use (leave empty for default) <br/>
                                        <a href='https://mise.jdx.dev/configuration/environments.html'>Learn more about mise configuration environments</a>
                                        """.trimIndent(),
                                    )
                            }.enabledIf(myMiseDirEnvCb.selected)
                            row {
                                cell(myMiseVcsCb)
                                    .resizableColumn()
                                    .comment("Enable mise environment variables and tools for VCS operations")
                            }.enabledIf(myMiseDirEnvCb.selected)
                        }
                    }
                }
            }
        }
    }

    override fun isModified(): Boolean {
        val applicationSettings = application.service<MiseApplicationSettings>()
        val projectSettings = project.service<MiseProjectSettings>()
        return myMiseExecutableTf.text != applicationSettings.state.executablePath ||
            myProjectMiseExecutableTf.text != projectSettings.state.executablePath ||
            myMiseDirEnvCb.isSelected != projectSettings.state.useMiseDirEnv ||
            myMiseConfigEnvironmentTf.text != projectSettings.state.miseConfigEnvironment ||
            myMiseVcsCb.isSelected != projectSettings.state.useMiseVcsIntegration
    }

    override fun apply() {
        if (isModified) {
            val applicationSettings = application.service<MiseApplicationSettings>()
            val projectSettings = project.service<MiseProjectSettings>()

            applicationSettings.state.executablePath = myMiseExecutableTf.text
            projectSettings.state.executablePath = myProjectMiseExecutableTf.text
            projectSettings.state.useMiseDirEnv = myMiseDirEnvCb.isSelected
            projectSettings.state.miseConfigEnvironment = myMiseConfigEnvironmentTf.text
            projectSettings.state.useMiseVcsIntegration = myMiseVcsCb.isSelected
        }
    }

    override fun getId(): String = MiseConfigurable::class.java.name
}
