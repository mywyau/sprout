# Application packaging

`sprout package` compiles the main sources, resolves only main dependencies, detects one application
main class, and writes production-ready files below `.sprout/package/`:

```text
.sprout/package/
├── hello/
│   ├── bin/
│   │   ├── hello
│   │   └── hello.cmd
│   ├── lib/
│   │   ├── hello.jar
│   │   └── 0001-runtime-dependency.jar
│   └── metadata/
│       ├── application.properties
│       └── checksums.txt
├── hello.tar.gz
├── hello.zip
└── hello-checksums.txt
```

Dependency names have numeric prefixes because JVM classpath order can affect behaviour. Sprout keeps
the order selected by Coursier instead of relying on filesystem ordering. Main resources are included
in the application JAR. Test classes, test resources, and test-only dependencies are excluded.

Run the directory distribution on Unix-like systems with `hello/bin/hello`; on Windows use
`hello\bin\hello.cmd`. The launcher honours `JAVA_HOME`, falls back to `java` on `PATH`, forwards
arguments, and accepts JVM flags through `JAVA_OPTS`. A compatible JRE is the only runtime prerequisite.

Verify downloaded archives from the package directory with either common SHA-256 implementation:

```bash
sha256sum -c hello-checksums.txt
# macOS
shasum -a 256 -c hello-checksums.txt
```

`metadata/checksums.txt` records every other file inside the unpacked directory. The outer checksum
file records both archives. Archive entries use stable ordering, timestamps, ownership, and
permissions so the same inputs produce the same archive bytes.

This is an application distribution, not a shaded or executable fat JAR. Keeping dependencies as
separate files avoids silently merging service descriptors and other JAR metadata. Library JAR
publication, signing, container-image creation, and Maven publishing remain outside v0.2.5.
