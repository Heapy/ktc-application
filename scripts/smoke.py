#!/usr/bin/env python3
"""Extract and run the actual plugin-produced archives on each CI host."""
import os
from pathlib import Path
import subprocess
import tarfile
import tempfile
import zipfile

project = Path(__file__).resolve().parents[1]
for extension in ("tar", "zip"):
    command = "distTar" if extension == "tar" else "distZip"
    archive = project / f"build/tasks/_example_{command}@application/example.{extension}"
    with tempfile.TemporaryDirectory(prefix="ktc application smoke ") as directory:
        destination = Path(directory)
        if extension == "tar":
            with tarfile.open(archive) as contents:
                contents.extractall(destination, filter="data")
        else:
            with zipfile.ZipFile(archive) as contents:
                contents.extractall(destination)
                for entry in contents.infolist():
                    (destination / entry.filename).chmod((entry.external_attr >> 16) & 0o777)
        root = destination / "example"
        launcher = root / ("bin/hello.bat" if os.name == "nt" else "bin/hello")
        arguments = [str(launcher), "two words", "last"]
        environment = {
            key: value for key, value in os.environ.items()
            if key.upper() not in {"JAVA_OPTS", "HELLO_OPTS", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"}
        }
        for overrides, expected in (
            ({}, "Hello from the distribution"),
            ({"JAVA_OPTS": '-Dexample.message="shared options"'}, "Shared options"),
            ({"JAVA_OPTS": '-Dexample.message="shared options"',
              "HELLO_OPTS": '-Dexample.message="application options"'}, "Application options"),
        ):
            # Let subprocess construct cmd.exe's outer /c quoting for a batch file.
            result = subprocess.run(
                arguments, shell=os.name == "nt", capture_output=True, text=True, env=environment | overrides,
            )
            assert result.returncode == 0, (result.returncode, result.stdout, result.stderr)
            assert result.stdout.splitlines() == [expected, "two words", "last"], result
        print(f"{extension}: extracted application and dependency ran successfully")
