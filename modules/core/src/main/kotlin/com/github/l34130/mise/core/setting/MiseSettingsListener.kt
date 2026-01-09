package com.github.l34130.mise.core.setting

import com.intellij.util.messages.Topic

/**
 * Listener for mise settings changes.
 * Broadcasts when user modifies mise settings in the IDE configuration.
 */
interface MiseSettingsListener {
    companion object {
        @JvmField
        @Topic.ProjectLevel
        val TOPIC = Topic(
            "Mise Settings Changed",
            MiseSettingsListener::class.java,
            Topic.BroadcastDirection.NONE
        )
    }

    /**
     * Called when mise settings have been changed and applied.
     */
    fun settingsChanged()
}
