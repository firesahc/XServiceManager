# XServiceManager

[English](README.md)

## 这是什么

XServiceManager 是供 Xposed 模块在 `system_server` 中使用的 Binder 服务桥。它通过系统现有 `clipboard` Binder 的私有事务暴露模块自有服务。

它**不会**把自定义服务注册到 Android `ServiceManager`，不会替换 clipboard Binder，也不会绕过 SELinux 策略。普通剪贴板事务仍交给原实现处理。

## 支持版本

Android 10 及以上（API 29+，与本库 `minSdkVersion` 一致）。

## 工作原理

`initForSystemServer()` 会 Hook `IClipboard.Stub.onTransact()`，并且只处理 XServiceManager 的私有事务码。客户端先获取系统 `clipboard` Binder，再通过私有事务查询 XServiceManager 进程内的服务表，之后直接与返回的自定义 Binder 通信。

```mermaid
flowchart LR
    Client["客户端应用"] --> Clipboard["系统 clipboard Binder"]
    Clipboard --> Hook["IClipboard.Stub.onTransact Hook"]
    Hook --> Registry["XServiceManager 服务表"]
    Registry --> Service["自定义 Binder 服务"]
```

## Xposed 集成

在使用方模块中添加 `implementation project(path: ':libxservicemanager')`。必须先确认当前进程是 `system_server`，再初始化 XServiceManager。

### 不依赖系统 `Context` 的服务

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

### 依赖系统 `Context` 的服务

`registerService()` 只登记 `ServiceFetcher`，本身不保证服务已经创建。引导阶段注册完全部 fetcher 后，必须调用 `flushRegisteredServices()`。

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

成功 flush 后，如果能够取得系统 `Context`，后续 `registerService()` 会立即创建对应服务。

### 客户端查询

桥或服务不可用时，查询结果可能为 `null`；使用前必须检查。

```java
IBinder binder = XServiceManager.getService("simple");
if (binder != null) {
    ISimpleService service = ISimpleService.Stub.asInterface(binder);
    service.doSomething();
}
```

也可以使用 `getServiceInterface()`。使用该方法时，应避免混淆生成的 AIDL 接口：

```proguard
-keep class com.your.ISimpleService$* { *; }
```

## 诊断

按 `XServiceManager` 标签过滤日志。桥成功安装时会输出：

```text
clipboard transaction bridge installed
```

发生故障时检查 `getLastError()` 或 `getRemoteBridgeStatus()`。桥已安装不等于某个自定义服务已经可用，因此需要同时检查桥状态和服务注册状态。

## 安全性与稳定性

自定义服务运行在 `system_server` 中，拥有高权限进程能力。每个对外 Binder 方法都必须校验调用方身份、验证并限制输入、避免在 Binder 线程执行无界工作，并在失败时保证不拖垮进程。

实际可写路径取决于设备的 SELinux 策略与目录标签，不能只根据 Linux UID 推断。应使用明确配置、最小权限的数据目录，并在每个受支持的 Android/ROM 目标上验证策略。

Binder 事务缓冲区有限。应避免传输大对象；必要时使用有界的带外传输。
