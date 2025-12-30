package com.github.l34130.mise.core.setting

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@Service(Service.Level.PROJECT)
@State(name = "com.github.l34130.mise.settings.MiseProjectSettings", storages = [Storage("mise.xml")])
class MiseProjectSettings() : PersistentStateComponent<MiseProjectSettings.MyState> {
    private var myState = MyState()

    override fun getState() = myState

    override fun loadState(state: MyState) {
        myState = state.clone()
    }

    override fun noStateLoaded() {
        myState = MyState()
    }

    override fun initializeComponent() {
        myState =
            MyState().also {
                it.useMiseDirEnv = myState.useMiseDirEnv
                it.miseConfigEnvironment = myState.miseConfigEnvironment
                it.useMiseVcsIntegration = myState.useMiseVcsIntegration
                it.executablePath = myState.executablePath
                it.projectExecutableOverrideEnabled = myState.projectExecutableOverrideEnabled
                it.projectExecutableOverridePath = myState.projectExecutableOverridePath
            }

        // Remove the notification check - it's annoying when using PATH default
    }

    class MyState : Cloneable {
        var useMiseDirEnv: Boolean = true
        var miseConfigEnvironment: String = ""
        var useMiseVcsIntegration: Boolean = true
        var executablePath: String = ""
        var projectExecutableOverrideEnabled: Boolean = false
        var projectExecutableOverridePath: String = ""

        fun executablePath(): String = if (projectExecutableOverrideEnabled) {
            projectExecutableOverridePath
        } else {
            executablePath
        }

        public override fun clone(): MyState =
            MyState().also {
                it.useMiseDirEnv = useMiseDirEnv
                it.miseConfigEnvironment = miseConfigEnvironment
                it.useMiseVcsIntegration = useMiseVcsIntegration
                it.executablePath = executablePath
                it.projectExecutableOverrideEnabled = projectExecutableOverrideEnabled
                it.projectExecutableOverridePath = projectExecutableOverridePath
            }
    }
}
