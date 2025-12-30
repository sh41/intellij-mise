package com.github.l34130.mise.core.setting

import com.github.l34130.mise.core.util.guessMiseProjectPath
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextBrowseFolderListener
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.fields.ExtendableTextField
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
    // Single executable path field with auto-detection (Git-style)
    // Use ExtendableTextField which supports emptyText
    private val myMiseExecutableTf = TextFieldWithBrowseButton(ExtendableTextField())

    // Checkbox to determine if path is saved project-only
    private val myProjectOnlyCb = JBCheckBox("Set this path only for the current project")

    // Other project settings
    private val myMiseDirEnvCb = JBCheckBox("Use environment variables from mise")
    private val myMiseConfigEnvironmentTf = JBTextField()
    private val myMiseVcsCb = JBCheckBox("Enable VCS Integration")

    override fun getDisplayName(): String = "Mise Settings"

    override fun createComponent(): JComponent {
        val applicationSettings = application.service<MiseApplicationSettings>()
        val projectSettings = project.service<MiseProjectSettings>()
        val executableManager = project.service<MiseExecutableManager>()

        // Determine which setting to load: project or app
        val projectPath = projectSettings.state.executablePath
        val appPath = applicationSettings.state.executablePath

        // Load current configuration
        if (projectPath.isNotBlank()) {
            // Project-level setting exists
            myMiseExecutableTf.text = projectPath
            myProjectOnlyCb.isSelected = true
        } else if (appPath.isNotBlank()) {
            // App-level setting exists
            myMiseExecutableTf.text = appPath
            myProjectOnlyCb.isSelected = false
        } else {
            // No setting - show empty
            myMiseExecutableTf.text = ""
            myProjectOnlyCb.isSelected = false
        }

        myMiseDirEnvCb.isSelected = projectSettings.state.useMiseDirEnv
        myMiseConfigEnvironmentTf.text = projectSettings.state.miseConfigEnvironment
        myMiseVcsCb.isSelected = projectSettings.state.useMiseVcsIntegration

        // Set placeholder text for auto-detected path
        val autoDetectedPath = executableManager.getAutoDetectedPath(project.guessMiseProjectPath())
        (myMiseExecutableTf.textField as ExtendableTextField).emptyText.text = "Auto-detected: $autoDetectedPath"

        // Configure file chooser to open at current or auto-detected location
        val fileChooserDescriptor = FileChooserDescriptor(true, false, false, false, false, false)
            .withTitle("Select Mise Executable")

        // Set up custom browse listener that opens at the appropriate location
        val browseListener = object : TextBrowseFolderListener(fileChooserDescriptor, project) {
            override fun getInitialFile(): VirtualFile? {
                // If field has value, use it; otherwise use auto-detected
                val currentPath = myMiseExecutableTf.text.takeIf { it.isNotBlank() } ?: autoDetectedPath
                return if (currentPath.isNotBlank()) {
                    val file = LocalFileSystem.getInstance().findFileByPath(currentPath)
                    file?.parent
                } else {
                    null
                }
            }
        }
        myMiseExecutableTf.addBrowseFolderListener(browseListener)

        return panel {
            group("Mise Executable", indent = false) {
                row("Path to Mise executable:") {
                    cell(myMiseExecutableTf)
                        .align(AlignX.FILL)
                        .resizableColumn()
                        .comment(
                            """
                            Leave empty to auto-detect from PATH or common locations (recommended).</br>
                            For WSL: <code>\\wsl.localhost\DistroName\path\to\mise</code></br>
                            Not installed? Visit the <a href='https://mise.jdx.dev/installing-mise.html'>mise installation</a>
                            """.trimIndent()
                        )
                }
                row {
                    cell(myProjectOnlyCb)
                        .comment("When checked, the path is saved for this project only. Otherwise, it applies to all projects.")
                }
            }

            group("Project Settings", indent = false) {
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

        // Check if path changed
        val currentPath = myMiseExecutableTf.text.trim()
        val projectPath = projectSettings.state.executablePath
        val appPath = applicationSettings.state.executablePath

        val pathChanged = if (myProjectOnlyCb.isSelected) {
            // Should be in project settings
            currentPath != projectPath || appPath.isNotBlank()
        } else {
            // Should be in app settings
            currentPath != appPath || projectPath.isNotBlank()
        }

        return pathChanged ||
            myMiseDirEnvCb.isSelected != projectSettings.state.useMiseDirEnv ||
            myMiseConfigEnvironmentTf.text != projectSettings.state.miseConfigEnvironment ||
            myMiseVcsCb.isSelected != projectSettings.state.useMiseVcsIntegration
    }

    override fun apply() {
        if (isModified) {
            val applicationSettings = application.service<MiseApplicationSettings>()
            val projectSettings = project.service<MiseProjectSettings>()

            val pathValue = myMiseExecutableTf.text.trim()

            if (myProjectOnlyCb.isSelected) {
                // Save to project settings only
                projectSettings.state.executablePath = pathValue
                // Clear app setting if it was there
                applicationSettings.state.executablePath = ""
            } else {
                // Save to app settings
                applicationSettings.state.executablePath = pathValue
                // Clear project setting
                projectSettings.state.executablePath = ""
            }

            projectSettings.state.useMiseDirEnv = myMiseDirEnvCb.isSelected
            projectSettings.state.miseConfigEnvironment = myMiseConfigEnvironmentTf.text
            projectSettings.state.useMiseVcsIntegration = myMiseVcsCb.isSelected
        }
    }

    override fun getId(): String = MiseConfigurable::class.java.name
}
