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

- `displayVersion` is the requested version for user-facing text (e.g., `21`).
- `displayVersionWithResolved` adds the resolved version when helpful (e.g.,
  `21 (v21.0.9)`), use this for notifications that need extra clarity.
- `resolvedVersion` returns the full resolved version string (not the alias).
- `resolvedInstallPath` returns the install directory (alias resolved) and
  converts WSL paths to UNC on Windows. This is not the binary path.
- Use `MiseCommandLineHelper.getBinPath("<tool>", project)` for executables.
  This resolves aliases and WSL paths correctly and avoids guessing bin names.

Minimal example pattern:

```kotlin
class MyLanguageSdkSetup : AbstractProjectSdkSetup() {
    override fun getDevToolName(project: Project) = MiseDevToolName("mytool")

    override fun checkSdkStatus(tool: MiseDevTool, project: Project): SdkStatus {
        // Compare current SDK to the resolved tool path.
        return SdkStatus.NeedsUpdate(
            currentSdkVersion = null,
            currentSdkLocation = SdkLocation.Project,
        )
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

### SDK target patterns (use the one that matches your IDE)

The in-tree implementations are intended to be exemplars and cover the most
common SDK storage models in JetBrains IDEs. Pick the pattern that matches the
IDE API you are integrating with.

#### 1) Project SDK (ProjectRootManager + ProjectJdkTable)

Use this when the IDE stores the language SDK at the project level.

Steps:
- Read `ProjectRootManager.getInstance(project).projectSdk`.
- Compare to the mise tool (typically by SDK home path or SDK name).
- Return `SdkStatus.NeedsUpdate(..., currentSdkLocation = SdkLocation.Project)`.
- In `applySdkConfiguration`, add or update the SDK in `ProjectJdkTable` and set
  `ProjectRootManager.getInstance(project).projectSdk = newSdk`.

Reference implementations:
- Java: `modules/products/idea/src/main/kotlin/com/github/l34130/mise/idea/jdk/MiseProjectJdkSetup.kt`
- Go: `modules/products/goland/src/main/kotlin/com/github/l34130/mise/goland/go/MiseProjectGoSdkSetup.kt`
- Ruby (manual only): `modules/products/ruby/src/main/kotlin/com/github/l34130/mise/ruby/sdk/MiseRubyProjectSdkSetup.kt`

#### 2) Module SDK (ModuleRootManager + ModuleRootModificationUtil)

Use this when modules can override or inherit the project SDK (Python).

Rules implemented in-tree:
- If the project SDK is a Python SDK, check it first.
- For each module:
  - If the module inherits the project SDK and the project SDK is Python, skip it.
  - If the module does not inherit, compare its SDK directly.
- If multiple modules need changes, return `SdkStatus.MultipleNeedsUpdate` and
  supply a `configureAction` per module so each notification targets one module.

Apply:
- `ModuleRootModificationUtil.setModuleSdk(module, newSdk)`.

Reference implementation:
- Python: `modules/products/pycharm/src/main/kotlin/com/github/l34130/mise/pycharm/sdk/MisePythonSdkSetup.kt`

#### 3) Settings-based interpreter path

Use this when the IDE stores an executable path in settings.

Steps:
- Read the current path in a `ReadAction`.
- Resolve the desired path via `MiseCommandLineHelper.getBinPath`.
- Return `SdkStatus.NeedsUpdate(..., currentSdkLocation = SdkLocation.Setting)`.
- In `applySdkConfiguration`, update the settings in a `WriteAction`.

Reference implementation:
- Node interpreter: `modules/products/nodejs/src/main/kotlin/com/github/l34130/mise/nodejs/node/MiseProjectInterpreterSetup.kt`

#### 4) Settings-based tool path (non-interpreter)

Use this when the IDE stores a tool path in settings (e.g., Deno).

Reference implementation:
- Deno: `modules/products/nodejs/src/main/kotlin/com/github/l34130/mise/nodejs/deno/MiseProjectDenoSetup.kt`

#### 5) Settings-based package manager

Use this when the IDE stores a package manager path in settings (e.g., npm/yarn/pnpm).

Reference implementation:
- Node package manager: `modules/products/nodejs/src/main/kotlin/com/github/l34130/mise/nodejs/node/MiseProjectPackageSetup.kt`

#### 6) Custom SDK locations

If your SDK lives somewhere else (remote targets, custom settings), set
`currentSdkLocation = SdkLocation.Custom("YourLabel")` so notifications
describe the source clearly. Use `configureAction` to scope updates to that
location when the default `applySdkConfiguration` is too broad.

### Reference implementations

These are the in-tree providers that show typical usage:

- Java (project SDK): `modules/products/idea/src/main/kotlin/com/github/l34130/mise/idea/jdk/MiseProjectJdkSetup.kt`
- Go (project SDK): `modules/products/goland/src/main/kotlin/com/github/l34130/mise/goland/go/MiseProjectGoSdkSetup.kt`
- Ruby (project SDK, manual only): `modules/products/ruby/src/main/kotlin/com/github/l34130/mise/ruby/sdk/MiseRubyProjectSdkSetup.kt`
- Python (module SDK, manual only): `modules/products/pycharm/src/main/kotlin/com/github/l34130/mise/pycharm/sdk/MisePythonSdkSetup.kt`
- Node interpreter (settings path): `modules/products/nodejs/src/main/kotlin/com/github/l34130/mise/nodejs/node/MiseProjectInterpreterSetup.kt`
- Node package manager (settings path): `modules/products/nodejs/src/main/kotlin/com/github/l34130/mise/nodejs/node/MiseProjectPackageSetup.kt`
- Deno (settings path): `modules/products/nodejs/src/main/kotlin/com/github/l34130/mise/nodejs/deno/MiseProjectDenoSetup.kt`

### Optional manual action

If you want a manual "Reload SDK" action, register your class as an action in
your plugin.xml the same way this plugin does (the class already extends
`DumbAwareAction`). Manual invocations run in user-interaction mode and will
apply SDK changes immediately.
