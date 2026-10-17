# ktc-application

Application distributions for **Kotlin Toolchain 0.13.0**: separate runtime JARs,
Unix and Windows start scripts, and reproducible tar/zip archives.

This plugin uses the public `module.jar` and `module.runtimeClasspath` APIs. It does
not invoke Gradle, unpack an executable JAR, or bundle a second build system.

## Install

With the [ktc-plugins](https://github.com/Heapy/ktc-plugins) release launchers in your project:

```sh
./ktc-plugins add Heapy/ktc-application --tag v0.1.0 --mode downloaded --enable-in app
```

Commit the launchers, `ktc-plugins.yaml`, `ktc-plugins.lock.yaml`, project/module
configuration, and `plugins/.gitignore`. Plugin sources stay ignored. Restore them
before invoking Kotlin Toolchain in a fresh clone or CI:

```sh
./ktc-plugins sync
./ktc-plugins verify
```

The root `ktc-plugin.yaml` declares the self-contained `plugins/application` module
and its Apache-2.0 license. The installer locks the resolved commit and file hashes.

## Configure

```yaml
product: jvm/app

settings:
  jvm:
    mainClass: com.example.MainKt

plugins:
  application:
    enabled: true
    applicationName: server
    distributionName: my-app
    defaultJvmArgs:
      - -Xmx512m
      - "-Dmessage=hello world"
```

Both names default to the module name. Set `settings.jvm.mainClass` explicitly;
the plugin does not guess a main class. Keep `settings.jvm.runtimeClasspathMode`
at its default `jars`. Names must be portable filenames. Distinct runtime JARs with
the same filename (including case-only differences) fail rather than overwrite one another.

## Commands and output

```sh
./kotlin do installDist -m app
./kotlin do distTar -m app
./kotlin do distZip -m app
```

`installDist` produces:

```text
build/tasks/_app_installDist@application/my-app/
├── bin/server
├── bin/server.bat
├── conf/jvm.options
└── lib/*.jar
```

The archives are `build/tasks/_app_distTar@application/my-app.tar` and
`build/tasks/_app_distZip@application/my-app.zip`, each containing that top-level
`my-app/` directory. `--build-dir` relocates these outputs. Archive tasks depend on
installation automatically; an ordinary build/check does not create distributions.

Launch with `bin/server` or `bin\server.bat`. The scripts use `JAVA_HOME` when set,
otherwise Java on `PATH`, and preserve application arguments and exit codes.
Java's `@argument-file` support reads `conf/jvm.options`; JVM arguments containing
spaces, quotes, or shell metacharacters are not evaluated by a shell. Java 9+ is
required for this mechanism, along with the application's own JVM version requirement.
Use the JVM's standard `JAVA_TOOL_OPTIONS` or `JDK_JAVA_OPTIONS` for runtime overrides;
the launchers do not parse shell-specific `JAVA_OPTS` or application-specific variables.

Installation removes stale output. Archives sort entries, fix timestamps and ownership,
and record Unix launcher executable permissions even when generated on Windows.
Dependency JAR bytes remain intact. Reproducibility assumes identical input JARs.

## Scope

JVM applications only, with a JAR runtime classpath and an explicit main class.
This release does not create native executables, custom runtime images, installers,
container images, or add arbitrary distribution content. Archives contain an ordinary
classpath application; no Spring Boot launcher is involved.

The plugin targets JVM 17 and pins Kotlin 2.4.21 and Commons Compress 1.28.0.
Consumer compiler and JVM release settings remain independent. Plugin integration
is verified against Kotlin Toolchain 0.13.0; revalidate when upgrading the Toolchain.

## Develop and verify

```sh
./kotlin test -m application
./kotlin do distTar -m example
./kotlin do distZip -m example
python3 scripts/smoke.py
```

Tests execute a real Java application from a path containing spaces and shell
metacharacters, check JVM options/arguments/exit codes, reject invalid configuration
and classpath collisions, and inspect tar/zip contents, modes, and reproducibility.
The smoke test extracts and runs the example with a real external runtime dependency.
CI runs these checks on Linux, macOS ARM64, and Windows.

Licensed under [Apache-2.0](LICENSE).
