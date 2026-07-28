# XServiceManager

[中文](README_zh.md)

## What it is

XServiceManager is a Binder service bridge for Xposed modules running in `system_server`. It exposes module-owned services through private transactions on the existing `clipboard` Binder.

It does **not** register custom services with Android's `ServiceManager`, replace the clipboard Binder, or bypass SELinux policy. Normal clipboard transactions continue through the original implementation.

## Supported versions

Android 10 and later (API 29+, matching this library's `minSdkVersion`).

## How it works

`initForSystemServer()` hooks `IClipboard.Stub.onTransact()` and handles only XServiceManager's private transaction codes. Clients obtain the system `clipboard` Binder, query XServiceManager's in-process service table through a private transaction, and then communicate directly with the returned custom Binder.

```mermaid
flowchart LR
    Client["Client app"] --> Clipboard["System clipboard Binder"]
    Clipboard --> Hook["IClipboard.Stub.onTransact hook"]
    Hook --> Registry["XServiceManager service table"]
    Registry --> Service["Custom Binder service"]
```

## Xposed integration

Add `implementation project(path: ':libxservicemanager')` to the consuming module. Initialize XServiceManager only after confirming that the current process is `system_server`.

### Service without a system `Context`

```java
public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
    if ("android".equals(lpparam.packageName)) {
        if (!XServiceManager.initForSystemServer()) {
            return;
        }
        XServiceManager.addService("simple", new SimpleService());
    }
}
```

### Service that requires a system `Context`

`registerService()` stores a `ServiceFetcher`; it does not by itself guarantee that the service has been created. After registering all fetchers during bootstrap, call `flushRegisteredServices()`.

```java
public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
    if ("android".equals(lpparam.packageName)) {
        if (!XServiceManager.initForSystemServer()) {
            return;
        }
        XServiceManager.registerService(
                "simple2",
                context -> new SimpleService2(context));
        XServiceManager.flushRegisteredServices();
    }
}
```

After a successful flush, later `registerService()` calls create their service immediately when a system `Context` is available.

### Client lookup

A lookup can return `null` when the bridge or service is unavailable. Always check the result before use.

```java
IBinder binder = XServiceManager.getService("simple");
if (binder != null) {
    ISimpleService service = ISimpleService.Stub.asInterface(binder);
    service.doSomething();
}
```

`getServiceInterface()` is also available. If it is used, keep the generated AIDL interface from obfuscation:

```proguard
-keep class com.your.ISimpleService$* { *; }
```

## Diagnostics

Filter logs by the `XServiceManager` tag. Successful bridge installation logs:

```text
clipboard transaction bridge installed
```

For failures, inspect `getLastError()` or `getRemoteBridgeStatus()`. A bridge can be installed while a particular custom service is still unavailable, so verify both bridge state and registered-service state.

## Security and stability

Custom services execute inside `system_server` and therefore have highly privileged process access. Every exposed Binder method must authenticate its caller, validate and bound all input, avoid unbounded work on Binder threads, and fail without destabilizing the process.

Writable paths depend on the device's SELinux policy and directory labels; do not infer access from the Linux UID alone. Use a deliberately provisioned, least-privilege data directory and verify the policy on every supported Android/ROM target.

Binder transactions have a limited buffer. Avoid large payloads and use bounded out-of-band transport where appropriate.
