# Local run without Docker

PowerShell from the project root:

```powershell
.\run-local.cmd
```

Defaults:

- URL: `http://localhost:8080`
- JDK: `%USERPROFILE%\.jdks\ms-21.0.11-1`
- models: `.\models`
- videos: `.\videos`
- memory: `-Xms256m -Xmx2g`

Custom port:

```powershell
.\run-local.cmd -Port 8081
```

Custom JDK:

```powershell
.\run-local.cmd -JavaHome "C:\Program Files\Java\jdk-21"
```

Skip rebuild if the jar is already compiled:

```powershell
.\run-local.cmd -SkipBuild
```

If you want to run the PowerShell script directly and your system blocks scripts, use:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\run-local.ps1
```

In the UI, use an absolute Windows path with forward slashes, for example:

```text
C:/Users/YourUser/IdeaProjects/passenger-counter/videos/bus.mp4
```

The script sets these environment variables before starting the app:

- `JAVA_HOME`
- `SERVER_PORT`
- `PC_MODELS_DIR`
- `PC_VIDEOS_DIR`

`PC_VIDEOS_DIR` is printed for convenience; video files are still selected in the UI by path.
