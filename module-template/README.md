# KXit DEX module template

1. Put this class in a tiny Android/Java library module that compiles against KXit's `module-api` interface source or a copy of the three public module API types.
2. Compile it to DEX (for example with D8 from Android Build Tools).
3. Copy the resulting `classes.dex` to `Android/data/com.mazfons.kxit/modules/hello.dex`.
4. Copy `hello.kxm.example` as `hello.kxm`, and make its first non-empty line the actual fully-qualified class name.
5. Restart KXit. Modules are loaded at app startup, not hot-loaded.

Only load modules you wrote or trust. A DEX module runs with the KXit app's process privileges.
