package com.github.l34130.mise.core.setup

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class MiseProjectSdkSetupActivity : ProjectActivity, DumbAware {
    override suspend fun execute(project: Project) {
        AbstractProjectSdkSetup.EP_NAME.extensionList.forEach { provider ->
            if (!provider.shouldAutoConfigure(project)) {
                return@forEach
            }
            try {
                provider.configureSdk(project, isUserInteraction = false)
            } catch (e: Throwable) {
                logger.warn("Failed to auto-configure SDK for ${provider.javaClass.name}", e)
            }
        }
    }

    companion object {
        private val logger = logger<MiseProjectSdkSetupActivity>()
    }
}
