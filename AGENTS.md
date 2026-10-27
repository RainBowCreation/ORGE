## Testing in this repo

Default test loop is `rtdd`, never the full suite:

    JAVA_HOME=$HOME/.jdks/dragonwell-21.0.11 rtdd run      # changed-code tests only
    rtdd which                                             # preview selection, runs nothing

Gradle 8.12 needs JDK 21 (default JDK 25 fails). The adapter override lives in
`.rtdd/adapters/gradle.yaml`; `-Prtdd` lets `:core:test` run integration-tagged classes when
named. Selection is static (name correspondence `Foo` -> `FooTest`/`FooIT`), so run the full
suite (`./gradlew :core:check`) only before merge/push or when rtdd escalates to T2.
