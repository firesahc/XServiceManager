package com.kaisar.xservicemanager;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * System service injection framework that bypasses SELinux restrictions to add
 * custom services accessible via IPC from user applications.
 *
 * <h3>Architecture</h3>
 * <p>XServiceManager operates by installing an Xposed hook on
 * {@code android.content.IClipboard.Stub#onTransact} inside {@code system_server}.
 * The original clipboard service remains in place. Calls using XServiceManager's
 * private transaction code are handled by this class and routed to custom Binder services
 * stored in an in-memory registry.</p>
 *
 * <h3>Workflow</h3>
 * <ol>
 *   <li>{@link #initForSystemServer()} hooks {@code IClipboard.Stub#onTransact}</li>
 *   <li>The hook handles only XServiceManager's private transaction code and leaves normal
 *       clipboard transactions untouched</li>
 *   <li>Services registered via {@link #registerService(String, ServiceFetcher)} are
 *       created when {@link #flushRegisteredServices()} runs, or immediately when registered
 *       after a previous flush</li>
 *   <li>Services added via {@link #addService(String, IBinder)} are stored immediately
 *       in the internal cache and made available for IPC retrieval</li>
 *   <li>User applications retrieve custom services via {@link #getService(String)}
 *       or {@link #getServiceInterface(String)} by sending a private transaction to clipboard</li>
 * </ol>
 *
 * <h3>Constraints</h3>
 * <ul>
 *   <li><b>Process:</b> All public methods must be called from the {@code system_server} process.
 *       Calling from any other process (including system apps) will be silently rejected.</li>
 *   <li><b>Initialization:</b> {@link #initForSystemServer()} must be called before any other
 *       public method. Failed initialization is retryable; successful initialization is guarded
 *       by an internal {@link java.util.concurrent.atomic.AtomicBoolean}.</li>
 *   <li><b>Thread safety:</b> Service registration and cache access are synchronized so late
 *       registration and diagnostics can run safely after startup.</li>
 *   <li><b>SELinux:</b> Custom services run in the {@code system} user group within the
 *       {@code system_server} process and are subject to standard SELinux restrictions.
 *       Data storage is limited to paths accessible to the system user (e.g., {@code /data/system}).</li>
 * </ul>
 *
 * <h3>Service Registration Modes</h3>
 * <table>
 *   <tr><th>Mode</th><th>Method</th><th>Timing</th><th>Use Case</th></tr>
 *   <tr>
 *     <td>Immediate</td>
 *     <td>{@link #addService(String, IBinder)}</td>
 *     <td>Available immediately after call</td>
 *     <td>Services not dependent on system context or core services</td>
 *   </tr>
 *   <tr>
 *     <td>Deferred</td>
 *     <td>{@link #registerService(String, ServiceFetcher)}</td>
 *     <td>Created on flush, or immediately after a previous flush</td>
 *     <td>Services requiring {@link Context} or dependent on system services</td>
 *   </tr>
 * </table>
 *
 * @see android.os.ServiceManager
 * @see android.app.ActivityThread
 */
public final class XServiceManager {

    // ===================================================================
    // 日志委托接口 — 允许主项目将日志接入自有日志系统
    // ===================================================================

    public interface LogDelegate {
        void d(String tag, String msg);
        void i(String tag, String msg);
        void w(String tag, String msg);
        void w(String tag, String msg, Throwable tr);
        void e(String tag, String msg);
        void e(String tag, String msg, Throwable tr);
    }

    private static final LogDelegate DEFAULT_DELEGATE = new LogDelegate() {
        @Override public void d(String tag, String msg) { Log.d(tag, msg); }
        @Override public void i(String tag, String msg) { Log.i(tag, msg); }
        @Override public void w(String tag, String msg) { Log.w(tag, msg); }
        @Override public void w(String tag, String msg, Throwable tr) { Log.w(tag, msg, tr); }
        @Override public void e(String tag, String msg) { Log.e(tag, msg); }
        @Override public void e(String tag, String msg, Throwable tr) { Log.e(tag, msg, tr); }
    };

    private static volatile LogDelegate sLog = DEFAULT_DELEGATE;

    /**
     * 设置日志委托。传入 {@code null} 恢复为默认的 android.util.Log 直调。
     */
    public static void setLogDelegate(@Nullable LogDelegate delegate) {
        sLog = delegate != null ? delegate : DEFAULT_DELEGATE;
    }

    private static final String TAG = "XServiceManager";
    private static final String DELEGATE_SERVICE = "clipboard";
    private static final Map<String, ServiceFetcher<? extends Binder>> SERVICE_FETCHERS = new ArrayMap<>();
    private static final Map<String, IBinder> sCache = new HashMap<>();
    private static final Object sLock = new Object();
    private static final AtomicBoolean sInited = new AtomicBoolean(false);
    private static volatile boolean sFlushed;
    private static volatile String sLastError;
    /** 通过 clipboard Binder 私有事务承载自定义服务查询，避免直接 addService 触发 SELinux 限制。 */
    private static final String DESCRIPTOR = XServiceManager.class.getName();
    private static final int TRANSACTION_ping = ('_'<<24)|('X'<<16)|('P'<<8)|'G';
    private static final int TRANSACTION_getService = ('_'<<24)|('X'<<16)|('S'<<8)|'M';
    private static final int TRANSACTION_getStatus = ('_'<<24)|('X'<<16)|('S'<<8)|'T';

    public static final class BridgeStatus {
        public final boolean bridgeInstalled;
        public final boolean systemServer;
        public final int registeredServiceCount;
        @Nullable public final String lastError;

        private BridgeStatus(boolean bridgeInstalled, boolean systemServer,
                             int registeredServiceCount, @Nullable String lastError) {
            this.bridgeInstalled = bridgeInstalled;
            this.systemServer = systemServer;
            this.registeredServiceCount = registeredServiceCount;
            this.lastError = lastError;
        }

        @NonNull
        @Override
        public String toString() {
            return "BridgeStatus{"
                    + "bridgeInstalled=" + bridgeInstalled
                    + ", systemServer=" + systemServer
                    + ", registeredServiceCount=" + registeredServiceCount
                    + ", lastError='" + lastError + '\''
                    + '}';
        }
    }

    public interface ServiceFetcher<T extends Binder> {
        T createService(Context ctx);
    }

    @SuppressLint("PrivateApi")
    private static final class ServiceManagerReflection {
        static final Class<?> SERVICE_MANAGER_CLASS;
        static final Method CHECK_SERVICE_METHOD;

        static {
            try {
                SERVICE_MANAGER_CLASS = Class.forName("android.os.ServiceManager");
                CHECK_SERVICE_METHOD = SERVICE_MANAGER_CLASS.getMethod("checkService", String.class);
            } catch (ClassNotFoundException | NoSuchMethodException e) {
                throw new ExceptionInInitializerError(e);
            }
        }
    }

    @SuppressLint("PrivateApi")
    private static Class<?> getServiceManagerClass() {
        return ServiceManagerReflection.SERVICE_MANAGER_CLASS;
    }

    @SuppressLint("PrivateApi")
    private static IBinder checkService(String name)
            throws InvocationTargetException, IllegalAccessException {
        return (IBinder) ServiceManagerReflection.CHECK_SERVICE_METHOD.invoke(null, name);
    }

    /**
     * Install a private transaction bridge on {@code IClipboard.Stub#onTransact}.
     * This is the entry point for the entire framework and must be called
     * from the {@code system_server} process during Xposed module initialization.
     *
     * <p>Safe to call multiple times — subsequent calls are no-ops after the first
     * successful initialization.</p>
     */
    public static boolean initForSystemServer() {
        if (sInited.get()) return true;
        if (!isSystemServerProcess()) {
            setLastError("initForSystemServer called from non-system_server process");
            sLog.w(TAG, sLastError);
            return false;
        }
        try {
            installClipboardTransactionHook();
            sInited.set(true);
            sLastError = null;
            sLog.d(TAG, "clipboard transaction bridge installed");
            return true;
        } catch (Throwable e) {
            setLastError("install clipboard transaction bridge failed: " + e.getClass().getSimpleName()
                    + ": " + e.getMessage());
            sLog.e(TAG, "inject fail", e);
            return false;
        }
    }

    public static boolean isBridgeInstalled() {
        return sInited.get();
    }

    @NonNull
    public static BridgeStatus getBridgeStatus() {
        int serviceCount;
        synchronized (sLock) {
            serviceCount = sCache.size();
        }
        return new BridgeStatus(sInited.get(), isSystemServerProcess(), serviceCount, sLastError);
    }

    @Nullable
    public static String getLastError() {
        return sLastError;
    }

    private static void setLastError(String error) {
        sLastError = error;
    }

    private static void installClipboardTransactionHook() {
        Class<?> clipboardStubClass = XposedHelpers.findClass(
                "android.content.IClipboard$Stub", ClassLoader.getSystemClassLoader());
        XposedHelpers.findAndHookMethod(clipboardStubClass, "onTransact",
                int.class, Parcel.class, Parcel.class, int.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        int code = (int) param.args[0];
                        if (code != TRANSACTION_ping
                                && code != TRANSACTION_getService
                                && code != TRANSACTION_getStatus) {
                            return;
                        }
                        Parcel data = (Parcel) param.args[1];
                        Parcel reply = (Parcel) param.args[2];
                        if (reply == null) {
                            return;
                        }
                        try {
                            switch (code) {
                                case TRANSACTION_ping:
                                    if (handlePingTransaction(data, reply)) {
                                        param.setResult(true);
                                    }
                                    break;
                                case TRANSACTION_getService:
                                    if (handleGetServiceTransaction(data, reply)) {
                                        param.setResult(true);
                                    }
                                    break;
                                case TRANSACTION_getStatus:
                                    if (handleGetStatusTransaction(data, reply)) {
                                        param.setResult(true);
                                    }
                                    break;
                            }
                        } catch (Throwable t) {
                            setLastError("handle private clipboard transaction failed: "
                                    + t.getClass().getSimpleName() + ": " + t.getMessage());
                            sLog.e(TAG, sLastError, t);
                            reply.writeException(t instanceof Exception
                                    ? (Exception) t : new RemoteException(t.getMessage()));
                            param.setResult(true);
                        }
                    }
                });
    }

    private static boolean isSystemServerProcess() {
        if (Process.myUid() != Process.SYSTEM_UID) {
            return false;
        }
        try {
            try (BufferedReader r = new BufferedReader(new FileReader(String.format(Locale.US, "/proc/%d/cmdline", Process.myPid())))) {
                String processName = r.readLine();
                if (processName == null) return false;
                processName = processName.replace("\0", "").trim();
                return "system_server".equals(processName);
            }
        } catch (IOException ignored) {
            sLog.d(TAG, "Process not found or inaccessible — not system_server");
        }
        return false;
    }

    private static void initializeRegisteredServices(Context ctx) {
        Map<String, ServiceFetcher<? extends Binder>> fetchers;
        synchronized (sLock) {
            fetchers = new HashMap<>(SERVICE_FETCHERS);
        }
        for (Map.Entry<String, ServiceFetcher<? extends Binder>> entry : fetchers.entrySet()) {
            String name = entry.getKey();
            // 幂等：跳过已创建的服务
            synchronized (sLock) {
                if (sCache.containsKey(name)) {
                    sLog.d(TAG, "service " + name + " already exists, skip");
                    continue;
                }
            }
            try {
                Binder service = entry.getValue().createService(ctx);
                addService(name, service);
                sLog.i(TAG, "service " + name + " created and added");
            } catch (Exception e) {
                sLog.e(TAG, "create " + name + " service fail", e);
            }
        }
    }

    @SuppressLint({"PrivateApi", "DiscouragedPrivateApi"})
    @Nullable
    private static Context getSystemContext() {
        try {
            Class<?> ActivityThreadClass = Class.forName("android.app.ActivityThread");
            Method currentActivityThread = ActivityThreadClass.getMethod("currentActivityThread");
            Method getSystemContext = ActivityThreadClass.getMethod("getSystemContext");
            return (Context) getSystemContext.invoke(currentActivityThread.invoke(null));
        } catch (Exception e) {
            sLog.e(TAG, "getSystemContext fail", e);
            return null;
        }
    }

    private static boolean handleGetServiceTransaction(@NonNull Parcel data,
                                                       @NonNull Parcel reply)
            throws RemoteException {
        data.enforceInterface(DESCRIPTOR);
        String name = data.readString();
        reply.writeNoException();
        IBinder binder = getServiceInternal(name);
        reply.writeStrongBinder(binder);
        return true;
    }

    private static boolean handlePingTransaction(@NonNull Parcel data,
                                                 @NonNull Parcel reply)
            throws RemoteException {
        data.enforceInterface(DESCRIPTOR);
        reply.writeNoException();
        reply.writeInt(1);
        return true;
    }

    private static boolean handleGetStatusTransaction(@NonNull Parcel data,
                                                      @NonNull Parcel reply)
            throws RemoteException {
        data.enforceInterface(DESCRIPTOR);
        writeBridgeStatus(reply, getBridgeStatus());
        return true;
    }

    private static IBinder getServiceInternal(String name) {
        IBinder binder;
        synchronized (sLock) {
            binder = sCache.get(name);
        }
        sLog.d(TAG, String.format("get service %s %s", name, binder));
        return binder;
    }

    private static void writeBridgeStatus(@NonNull Parcel reply, @NonNull BridgeStatus status) {
        reply.writeNoException();
        reply.writeInt(status.bridgeInstalled ? 1 : 0);
        reply.writeInt(status.systemServer ? 1 : 0);
        reply.writeInt(status.registeredServiceCount);
        reply.writeString(status.lastError);
    }

    @NonNull
    private static BridgeStatus readBridgeStatus(@NonNull Parcel reply) {
        boolean bridgeInstalled = reply.readInt() != 0;
        boolean systemServer = reply.readInt() != 0;
        int serviceCount = reply.readInt();
        String lastError = reply.readString();
        return new BridgeStatus(bridgeInstalled, systemServer, serviceCount, lastError);
    }

    /**
     * Register a service factory for deferred (lazy) creation.
     * The service is not created immediately before the first flush; it will be
     * instantiated via the {@link ServiceFetcher#createService(Context)} callback
     * when {@link #flushRegisteredServices()} runs. After a previous flush, late
     * registrations are created immediately.
     *
     * <p>Use this method when your service needs a system {@link Context} or
     * depends on core system services that are not yet available at registration time.
     * For immediate registration, use {@link #addService(String, IBinder)} instead.</p>
     *
     * <p>Must be called from {@code system_server} — calls from other processes
     * are silently ignored and logged as warnings.</p>
     *
     * @param name           the name of the new service (used as lookup key)
     * @param serviceFetcher factory that creates the service when needed
     * @param <T>            the concrete service type extending {@link Binder}
     */
    public static <T extends Binder> void registerService(String name, ServiceFetcher<T> serviceFetcher) {
        if (!isSystemServerProcess()) {
            sLog.w(TAG, String.format("register service %s ignored — not system_server", name));
            return;
        }
        sLog.d(TAG, String.format("register service %s %s", name, serviceFetcher));
        boolean shouldCreateNow;
        synchronized (sLock) {
            SERVICE_FETCHERS.put(name, serviceFetcher);
            shouldCreateNow = sFlushed && !sCache.containsKey(name);
        }
        if (shouldCreateNow) {
            Context ctx = getSystemContext();
            if (ctx == null) {
                setLastError("registerService: cannot get system context for " + name);
                sLog.e(TAG, sLastError);
                return;
            }
            try {
                addService(name, serviceFetcher.createService(ctx));
                sLog.i(TAG, "service " + name + " created after late registration");
            } catch (Exception e) {
                setLastError("create " + name + " service after late registration failed: "
                        + e.getClass().getSimpleName() + ": " + e.getMessage());
                sLog.e(TAG, sLastError, e);
            }
        }
    }

    /**
     * 立即创建所有已注册的延迟服务（ServiceFetcher）。
     * 必须在所有 {@link #registerService(String, ServiceFetcher)} 调用完成后调用。
     *
     * <p>幂等操作：已存在于 {@code sCache} 中的服务跳过创建。
     * 通常由 {@code bootstrapSystemService()} 在 init + register 之后主动调用，
     * 作为主动创建路径，解决仅依赖系统服务时序导致延迟服务永不创建的竞态问题。</p>
     *
     * <p>Must be called from {@code system_server} — calls from other processes
     * are silently ignored and logged as warnings.</p>
     */
    public static void flushRegisteredServices() {
        if (!isSystemServerProcess()) {
            sLog.w(TAG, "flushRegisteredServices ignored — not system_server");
            return;
        }
        Context ctx = getSystemContext();
        if (ctx == null) {
            sLog.e(TAG, "flushRegisteredServices: cannot get system context");
            return;
        }
        initializeRegisteredServices(ctx);
        int serviceCount;
        synchronized (sLock) {
            sFlushed = true;
            serviceCount = sCache.size();
        }
        sLog.i(TAG, "flushRegisteredServices done, cached services: " + serviceCount);
    }

    /**
     * Immediately register a service instance in the internal cache.
     * The service is available for IPC retrieval as soon as this method returns.
     *
     * <p>Use this method for services that do NOT depend on a {@link Context}
     * or on other system services. If your service needs system context,
     * use {@link #registerService(String, ServiceFetcher)} instead.</p>
     *
     * <p>Must be called from {@code system_server} — calls from other processes
     * are silently ignored and logged as warnings.</p>
     *
     * @param name    the name of the new service (used as lookup key)
     * @param service the service object to register
     */
    public static void addService(String name, IBinder service) {
        if (!isSystemServerProcess()) {
            sLog.w(TAG, String.format("add service %s ignored — not system_server", name));
            return;
        }
        sLog.d(TAG, String.format("add service %s %s", name, service));
        synchronized (sLock) {
            sCache.put(name, service);
        }
    }

    public static boolean pingBridge() {
        try {
            IBinder delegateService = checkService(DELEGATE_SERVICE);
            if (delegateService == null) {
                setLastError("cannot access delegate service: clipboard is null");
                sLog.w(TAG, sLastError);
                return false;
            }
            Parcel _data = Parcel.obtain();
            Parcel _reply = Parcel.obtain();
            try {
                _data.writeInterfaceToken(DESCRIPTOR);
                if (!delegateService.transact(TRANSACTION_ping, _data, _reply, 0)) {
                    setLastError("clipboard bridge did not handle XServiceManager ping");
                    sLog.w(TAG, sLastError);
                    return false;
                }
                _reply.readException();
                boolean ok = _reply.readInt() == 1;
                if (ok) {
                    sLastError = null;
                } else {
                    setLastError("clipboard bridge ping returned false");
                }
                return ok;
            } finally {
                _data.recycle();
                _reply.recycle();
            }
        } catch (Exception e) {
            Throwable cause = e instanceof InvocationTargetException ? e.getCause() : e;
            setLastError("ping bridge error: " + cause.getClass().getSimpleName()
                    + ": " + cause.getMessage());
            sLog.e(TAG, "ping bridge error", cause);
            return false;
        }
    }

    @Nullable
    public static BridgeStatus getRemoteBridgeStatus() {
        try {
            IBinder delegateService = checkService(DELEGATE_SERVICE);
            if (delegateService == null) {
                setLastError("cannot access delegate service: clipboard is null");
                sLog.w(TAG, sLastError);
                return null;
            }
            Parcel _data = Parcel.obtain();
            Parcel _reply = Parcel.obtain();
            try {
                _data.writeInterfaceToken(DESCRIPTOR);
                if (!delegateService.transact(TRANSACTION_getStatus, _data, _reply, 0)) {
                    setLastError("clipboard bridge did not handle XServiceManager status");
                    sLog.w(TAG, sLastError);
                    return null;
                }
                _reply.readException();
                BridgeStatus status = readBridgeStatus(_reply);
                sLastError = status.lastError;
                return status;
            } finally {
                _data.recycle();
                _reply.recycle();
            }
        } catch (Exception e) {
            Throwable cause = e instanceof InvocationTargetException ? e.getCause() : e;
            setLastError("get bridge status error: " + cause.getClass().getSimpleName()
                    + ": " + cause.getMessage());
            sLog.e(TAG, "get bridge status error", cause);
            return null;
        }
    }

    /**
     * Returns a reference to a service with the given name by routing the request
     * through the clipboard service delegate IPC channel.
     *
     * @param name the name of the service to retrieve
     * @return a reference to the service, or {@code null} if the service
     *         does not exist or the delegate is unavailable
     */
    public static IBinder getService(String name) {
        try {
            IBinder delegateService = checkService(DELEGATE_SERVICE);
            if (delegateService == null) {
                setLastError("cannot access delegate service: clipboard is null");
                sLog.w(TAG, sLastError);
                return null;
            }
            Parcel _data = Parcel.obtain();
            Parcel _reply = Parcel.obtain();
            try {
                _data.writeInterfaceToken(DESCRIPTOR);
                _data.writeString(name);
                if (!delegateService.transact(TRANSACTION_getService, _data, _reply, 0)) {
                    setLastError("clipboard bridge did not handle XServiceManager transaction");
                    sLog.w(TAG, sLastError);
                    return null;
                }
                _reply.readException();
                IBinder binder = _reply.readStrongBinder();
                if (binder == null) {
                    setLastError(String.format("service %s is not registered in XServiceManager", name));
                } else {
                    sLastError = null;
                }
                return binder;
            } finally {
                _data.recycle();
                _reply.recycle();
            }
        } catch (Exception e) {
            Throwable cause = e instanceof InvocationTargetException ? e.getCause() : e;
            setLastError(String.format("get %s service error: %s: %s",
                    name, cause.getClass().getSimpleName(), cause.getMessage()));
            sLog.e(TAG, String.format("get %s service error", name), cause);
            return null;
        }
    }

    /**
     * Retrieves a typed service interface by name, automatically resolving the
     * AIDL {@code Stub.asInterface()} method via reflection.
     *
     * <p>This is a convenience wrapper around {@link #getService(String)} that
     * returns a typed interface proxy instead of a raw {@link IBinder}.
     * The service's AIDL stub class must be accessible and unobfuscated
     * (e.g., keep with {@code -keep class com.your.IService$Stub {*;}}).</p>
     *
     * @param name the name of the service to retrieve
     * @param <I>  the service interface type (e.g., {@code ISimpleService})
     * @return a typed interface proxy, or {@code null} if the service
     *         does not exist or the delegate is unavailable
     */
    @SuppressWarnings("unchecked")
    public static <I extends IInterface> I getServiceInterface(String name) {
        try {
            IBinder service = getService(name);
            Objects.requireNonNull(service, String.format("can't found %s service", name));
            String descriptor = service.getInterfaceDescriptor();
            Class<?> StubClass = XServiceManager.class.getClassLoader().loadClass(descriptor + "$Stub");
            return (I) StubClass.getMethod("asInterface", IBinder.class).invoke(null, service);
        } catch (Exception e) {
            sLog.e(TAG, String.format("get %s service error", name), e instanceof InvocationTargetException ? e.getCause() : e);
            return null;
        }
    }

}
