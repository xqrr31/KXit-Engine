KXit module system (Java/Dex, no NDK)

Runtime module pair:
  module_name.dex
  module_name.kxm

The first non-empty, non-comment line of module_name.kxm must be the fully-qualified
class name. That class must have a public no-argument constructor and implement:
  com.rookmender.kxit.module.KXitModule

KXitModule methods:
  onLoad(KXitModuleApi api)
  onUpdate(float deltaSeconds)  [optional]
  onDispose()                  [optional]

KXitModuleApi exposes log(), getBlock(), setBlock(), and setDaylight().
Modules are loaded on application startup only. Never install DEX modules from an
untrusted source: modules execute code with the host application's permissions.
The source project includes the interface and API classes under the module package.
