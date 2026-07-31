# GitHub Actions Controller

A JetBrains IDE plugin that seamlessly integrates GitHub Actions into your Git workflow. Manage, view, and trigger workflows directly from the IDE's version control panel.

![GitHub Actions Panel](.github/images/preview.png) *(Preview placeholder)*

## 🌟 Features

- **Integrated Git Panel**: A dedicated "GitHub Actions" tab inside the IDE's standard Git ToolWindow.
- **Workflow & Run Browser**: View all workflows and their recent runs, complete with real-time status icons (success, failure, in-progress, etc.).
- **In-IDE Log Viewer**: Double-click on any run to inspect its Job execution details and logs without leaving the IDE.
- **Zero Configuration**: Uses the built-in IntelliJ GitHub account manager (`Settings` → `Version Control` → `GitHub`). No need to manually copy-paste Personal Access Tokens (PAT).
- **Trigger Workflows**: Manually dispatch `workflow_dispatch` events directly from the UI. Automatically detects the current Git branch.
- **Multi-Account & Enterprise Support**: Easily switch between multiple logged-in GitHub accounts. Automatically detects GitHub Enterprise remotes.
- **Bilingual Support**: Fully localized in English and Simplified Chinese (简体中文).

## 🚀 Installation

*Note: This plugin is currently in development and not yet published to the JetBrains Marketplace.*

1. Download the latest `github-actions-controller-x.x.x.zip` from the [Releases](#) page.
2. In your IDE, go to `Settings` / `Preferences` → `Plugins`.
3. Click the ⚙️ (gear) icon and select **Install Plugin from Disk...**.
4. Select the downloaded ZIP file and click **Restart IDE**.

## 🛠️ Usage

1. Open a project that has a GitHub remote (`origin`).
2. Ensure you are logged into your GitHub account in `Settings` → `Version Control` → `GitHub`.
3. Open the **Git** tool window (usually at the bottom left, or `Cmd+9` / `Alt+9`).
4. Navigate to the **GitHub Actions** tab.
5. Select a workflow on the left to see its recent runs on the right.
6. **Trigger a workflow**: Select a workflow and click the `▶ (Execute)` button in the toolbar.
7. **Switch accounts**: Use the user icon dropdown in the toolbar to switch accounts if you have multiple configured.

## 💻 Development

The project is built with Kotlin and the IntelliJ Platform Plugin SDK.

### Build from source

By default, Gradle uses the IntelliJ IDEA installation at
`/Applications/IntelliJ IDEA.app` for faster local builds on macOS.

```bash
# Compile the Kotlin source code
./gradlew compileKotlin

# Build the plugin ZIP file (output: build/distributions/)
./gradlew buildPlugin

# Run a sandboxed IDE instance with the plugin installed
./gradlew runIde
```

For Windows, Linux, a different macOS installation path, or a reproducible
build that downloads IntelliJ IDEA 2026.1.4 from JetBrains repositories, add
`-PuseLocalIde=false`:

```bash
./gradlew buildPlugin -PuseLocalIde=false
```

GitHub Actions always uses the portable mode.

## 📝 License

This project is licensed under the [MIT License](LICENSE).
