# Extension Points

This plugin exposes extension points so other plugins can reuse the same SDK
sync logic that is driven by `mise.toml` files.

## projectSdkSetup

Use this extension point to add SDK auto-configuration for a tool that the
plugin does not ship out of the box.

### Registration (plugin.xml)

Your plugin must declare a dependency on the Mise plugin to register a provider.
This can be either a required or optional dependency.

```xml
<depends>com.github.l34130.mise</depends>

<extensions defaultExtensionNs="com.github.l34130.mise">
  <projectSdkSetup implementation="com.example.mise.MyLanguageSdkSetup"/>
</extensions>
```

If your plugin should work without Mise installed, register the extension
behind an optional dependency:

```xml
<depends optional="true" config-file="mise-extensions.xml">com.github.l34130.mise</depends>
```

```xml
<!-- mise-extensions.xml -->
<extensions defaultExtensionNs="com.github.l34130.mise">
  <projectSdkSetup implementation="com.example.mise.MyLanguageSdkSetup"/>
</extensions>
```

### Implementation

Implement `com.github.l34130.mise.core.setup.AbstractProjectSdkSetup`. The base
class handles the common workflow:

- Finds the matching tool from `mise ls --local --json`.
- Prompts for installation when the tool is missing (and can auto-install if
  enabled). Installs run `mise install --raw --yes <tool>` in a Run tool window.
- Prompts to configure when the IDE SDK is out of sync, with "Configure now"
  and "Always keep <SDK> in sync" actions.
- Calls your `checkSdkStatus` and `applySdkConfiguration` hooks on a background
  thread (use ReadAction/WriteAction when touching IDE state).

Defaults and settings integration:

- `defaultAutoInstall` and `defaultAutoConfigure` define the per-SDK defaults.
- Users can override these per SDK in "Mise Settings → SDK Setup".
- `getSettingsId` is the stable ID for storing user choices.
- `getSettingsDisplayName` is the label shown in the settings UI.

Key things to use from `MiseDevTool`:

- `resolvedInstallPath` returns the real install directory (alias resolved) and
  converts WSL paths to UNC on Windows.
- `resolvedVersion` returns the full version string (not the alias).

Minimal example pattern:

```kotlin
class MyLanguageSdkSetup : AbstractProjectSdkSetup() {
    override fun getDevToolName(project: Project) = MiseDevToolName("mytool")

    override fun checkSdkStatus(tool: MiseDevTool, project: Project): SdkStatus {
        // Compare current SDK to tool.resolvedInstallPath / tool.resolvedVersion.
        return SdkStatus.NeedsUpdate(currentSdkVersion = null)
    }

    override fun applySdkConfiguration(tool: MiseDevTool, project: Project) {
        // Apply the SDK in your product API.
    }

    override fun <T : Configurable> getSettingsConfigurableClass(): KClass<out T>? = null
}
```

You can override `defaultAutoConfigure` to return false when a language SDK
should only be updated via the manual action.

Use `defaultAutoInstall` when a tool should be automatically installed by
default.

### Reference implementations

These are the in-tree providers that show typical usage:

- Java: `modules/products/idea/src/main/kotlin/com/github/l34130/mise/idea/jdk/MiseProjectJdkSetup.kt`
- Go: `modules/products/goland/src/main/kotlin/com/github/l34130/mise/goland/go/MiseProjectGoSdkSetup.kt`
- Ruby (manual only): `modules/products/ruby/src/main/kotlin/com/github/l34130/mise/ruby/sdk/MiseRubyProjectSdkSetup.kt`
- Python (manual only): `modules/products/pycharm/src/main/kotlin/com/github/l34130/mise/pycharm/sdk/MisePythonSdkSetup.kt`

### Optional manual action

If you want a manual "Reload SDK" action, register your class as an action in
your plugin.xml the same way this plugin does (the class already extends
`DumbAwareAction`). Manual invocations run in user-interaction mode and will
apply SDK changes immediately.
