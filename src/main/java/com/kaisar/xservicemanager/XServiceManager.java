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
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * System service injection framework that bypasses SELinux restrictions to add
 * custom services accessible via IPC from user applications.
 *
 * <h3>Architecture</h3>
 * <p>XServiceManager operates by intercepting the {@code addService("clipboard", ...)} call
 * within the {@link android.os.ServiceManager} using a dynamic proxy. When {@code initForSystemServer()}
 * is called, it replaces the system's IServiceManager instance with a proxy that wraps
 * the clipboard service's {@link IBinder} in a {@link BinderDelegateService}, routing custom
 * service calls through an {@code XServiceManagerService} handler.</p>
 *
 * <h3>Workflow</h3>
 * <ol>
 *   <li>{@link #initForSystemServer()} creates a {@link java.lang.reflect.Proxy} on IServiceManager</li>
 *   <li>The proxy intercepts {@code addService("clipboard")} and wraps the clipboard IBinder
 *       with a {@code BinderDelegateService} that delegates custom service transactions
 *       (identified by a magic transaction code) to the {@code XServiceManagerService}</li>
 *   <li>Services registered via {@link #registerService(String, ServiceFetcher)} are
 *       lazily created when the clipboard service is first added</li>
 *   <li>Services added via {@link #addService(String, IBinder)} are stored immediately
 *       in the internal cache and made available for IPC retrieval</li>
 *   <li>User applications retrieve custom services via {@link #getService(String)}
 *       or {@link #getServiceInterface(String)} by accessing the wrapped clipboard service</li>
 * </ol>
 *
 * <h3>Constraints</h3>
 * <ul>
 *   <li><b>Process:</b> All public methods must be called from the {@code system_server} process.
 *       Calling from any other process (including system apps) will be silently rejected.</li>
 *   <li><b>Initialization:</b> {@link #initForSystemServer()} must be called before any other
 *       public method, and should only be invoked once (repeated calls are ignored via an
 *       internal {@link java.util.concurrent.atomic.AtomicBoolean} guard).</li>
 *   <li><b>Thread safety:</b> This class is NOT thread-safe. All initialization and service
 *       registration should occur during the single-threaded startup phase of system_server.
 *       The internal maps ({@code SERVICE_FETCHERS}, {@code sCache}) are not synchronized.</li>
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
 *     <td>Created lazily when clipboard service is first added</td>
 *     <td>Services requiring {@link Context} or dependent on system services</td>
 *   </tr>
 * </table>
 *
 * @see android.os.ServiceManager
 * @see android.app.ActivityThread
 * @see java.lang.reflect.Proxy
 */
public final class XServiceManager {

    private static final String TAG = "XServiceManager";
    private static final String DELEGATE_SERVICE = "clipboard";
    // NOT thread-safe — only accessed during single-threaded init phase
    private static final Map<String, ServiceFetcher<? extends Binder>> SERVICE_FETCHERS = new ArrayMap<>();
    // NOT thread-safe — only accessed during single-threaded init phase
    private static final Map<String, IBinder> sCache = new HashMap<>();
    private static final AtomicBoolean sInited = new AtomicBoolean(false);

    private static final String DESCRIPTOR = XServiceManager.class.getName();
    private static final int TRANSACTION_getService = ('_'<<24)|('X'<<16)|('S'<<8)|'M';

    public interface ServiceFetcher<T extends Binder> {
        T createService(Context ctx);
    }

    @SuppressLint("PrivateApi")
    private static final class ServiceManagerReflection {
        static final Class<?> SERVICE_MANAGER_CLASS;
        static final Method CHECK_SERVICE_METHOD;
        static final Method ADD_SERVICE_METHOD;
        static final Field S_SERVICE_MANAGER_FIELD;

        static {
            try {
                SERVICE_MANAGER_CLASS = Class.forName("android.os.ServiceManager");
                CHECK_SERVICE_METHOD = SERVICE_MANAGER_CLASS.getMethod("checkService", String.class);
                ADD_SERVICE_METHOD = SERVICE_MANAGER_CLASS.getMethod("addService", String.class, IBinder.class);
                S_SERVICE_MANAGER_FIELD = SERVICE_MANAGER_CLASS.getDeclaredField("sServiceManager");
                S_SERVICE_MANAGER_FIELD.setAccessible(true);
            } catch (ClassNotFoundException | NoSuchMethodException | NoSuchFieldException e) {
                throw new ExceptionInInitializerError(e);
            }
        }
    }

    @SuppressLint("PrivateApi")
    private static Class<?> getServiceManagerClass() {
        return ServiceManagerReflection.SERVICE_MANAGER_CLASS;
    }

    @SuppressLint("PrivateApi")
    private static IBinder checkService(String name) throws Exception {
        return (IBinder) ServiceManagerReflection.CHECK_SERVICE_METHOD.invoke(null, name);
    }

    @SuppressLint("PrivateApi")
    private static void addServiceToSM(String name, IBinder service) throws Exception {
        ServiceManagerReflection.ADD_SERVICE_METHOD.invoke(null, name, service);
    }

    /**
     * Initialize the ServiceManager proxy and install the clipboard service delegate.
     * This is the entry point for the entire framework and must be called
     * from the {@code system_server} process during Xposed module initialization.
     *
     * <p>Safe to call multiple times — subsequent calls are no-ops after the first
     * successful initialization.</p>
     *
     * <p>Internal workflow:
     * <ol>
     *   <li>Obtain the IServiceManager proxy via reflection</li>
     *   <li>Create a dynamic proxy wrapper ({@code createClipboardServiceDelegate})</li>
     *   <li>Replace the system's IServiceManager instance ({@code installServiceManagerDelegate})</li>
     *   <li>Handle late-registered clipboard services ({@code performLateWrappingIfNeeded})</li>
     * </ol>
     */
    public static void initForSystemServer() {
        if (!sInited.compareAndSet(false, true)) return;
        if (!isSystemServerProcess()) {
            Log.w(TAG, "[GodModePro] initForSystemServer called from non-system_server process");
            return;
        }
        try {
            Object serviceManager = getServiceManagerProxy();
            Object delegate = createClipboardServiceDelegate(serviceManager);
            installServiceManagerDelegate(delegate);
            Log.d(TAG, "[GodModePro] inject success");
            performLateWrappingIfNeeded();
        } catch (NoSuchMethodException | IllegalAccessException
                 | InvocationTargetException e) {
            Log.e(TAG, "[GodModePro] inject fail", e);
        }
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
            // Process not found or inaccessible — not system_server
        }
        return false;
    }

    @SuppressLint({"PrivateApi", "DiscouragedPrivateApi"})
    private static Object getServiceManagerProxy()
            throws NoSuchMethodException, IllegalAccessException, InvocationTargetException {
        Class<?> serviceManagerClass = getServiceManagerClass();
        Method getIServiceManagerMethod = serviceManagerClass.getDeclaredMethod("getIServiceManager");
        getIServiceManagerMethod.setAccessible(true);
        return getIServiceManagerMethod.invoke(null);
    }

    @SuppressLint({"PrivateApi", "DiscouragedPrivateApi"})
    private static Object createClipboardServiceDelegate(Object serviceManager) {
        Field sServiceManagerField = ServiceManagerReflection.S_SERVICE_MANAGER_FIELD;
        Class<?> IServiceManagerClass = sServiceManagerField.getType();
        return Proxy.newProxyInstance(IServiceManagerClass.getClassLoader(), new Class[]{IServiceManagerClass}, (proxy, method, args) -> {
            final String methodName = method.getName();
            if ("addService".equals(methodName) && DELEGATE_SERVICE.equals(args[0])) {
                try {
                    IBinder clipboardService = (IBinder) args[1];
                    IBinder xServiceManagerService = new XServiceManagerService();
                    args[1] = new BinderDelegateService(clipboardService, xServiceManagerService);
                    @SuppressLint("PrivateApi") Class<?> ActivityThreadClass = Class.forName("android.app.ActivityThread");
                    Method currentActivityThread = ActivityThreadClass.getMethod("currentActivityThread");
                    Method getSystemContext = ActivityThreadClass.getMethod("getSystemContext");
                    Context systemContext = (Context) getSystemContext.invoke(currentActivityThread.invoke(null));
                    initializeRegisteredServices(systemContext);
                } catch (Exception e) {
                    Log.e(TAG, "[GodModePro] addService delegate fail", e);
                }
            }
            try {
                return method.invoke(serviceManager, args);
            } catch (InvocationTargetException e) {
                Log.w(TAG, "[GodModePro] proxy call " + methodName + " failed", e.getCause());
                throw e.getCause();
            }
        });
    }

    @SuppressLint({"PrivateApi", "DiscouragedPrivateApi"})
    private static void installServiceManagerDelegate(Object delegate) throws IllegalAccessException {
        ServiceManagerReflection.S_SERVICE_MANAGER_FIELD.set(null, delegate);
    }

    private static void initializeRegisteredServices(Context ctx) {
        for (Map.Entry<String, ServiceFetcher<?>> serviceFetcherEntry : SERVICE_FETCHERS.entrySet()) {
            String name = serviceFetcherEntry.getKey();
            try {
                Binder service = serviceFetcherEntry.getValue().createService(ctx);
                addService(name, service);
                Log.i(TAG, "[GodModePro] service " + name + " created and added");
            } catch (Exception e) {
                Log.e(TAG, "[GodModePro] create " + name + " service fail", e);
            }
        }
    }

    private static void performLateWrappingIfNeeded() {
        try {
            IBinder existingClipboard = checkService(DELEGATE_SERVICE);
            if (existingClipboard != null) {
                Log.w(TAG, "[GodModePro] clipboard already registered, performing late wrapping");
                addServiceToSM(DELEGATE_SERVICE, existingClipboard);
                Log.i(TAG, "[GodModePro] late wrapping success");
            }
        } catch (Exception e) {
            Log.e(TAG, "[GodModePro] late wrapping failed", e);
        }
    }

    private static final class BinderDelegateService extends Binder {

        private final IBinder systemService;
        private final IBinder customService;

        public BinderDelegateService(IBinder systemService, IBinder customService) {
            this.systemService = systemService;
            this.customService = customService;
        }

        @Override
        protected boolean onTransact(int code, @NonNull Parcel data, @Nullable Parcel reply, int flags) throws RemoteException {
            if(code == TRANSACTION_getService){
                return customService.transact(code, data, reply, flags);
            }
            return systemService.transact(code, data, reply, flags);
        }
    }

    private static final class XServiceManagerService extends Binder {

        @Override
        protected boolean onTransact(int code, @NonNull Parcel data, Parcel reply, int flags) throws RemoteException {
            String descriptor = DESCRIPTOR;
            switch (code) {
                case INTERFACE_TRANSACTION: {
                    reply.writeString(descriptor);
                    return true;
                }
                case TRANSACTION_getService: {
                    data.enforceInterface(descriptor);
                    String name = data.readString();
                    reply.writeNoException();
                    IBinder binder = getServiceInternal(name);
                    reply.writeStrongBinder(binder);
                    return true;
                }
                default: {
                    return super.onTransact(code, data, reply, flags);
                }
            }
        }

    }

    private static IBinder getServiceInternal(String name) {
        IBinder binder = sCache.get(name);
        Log.d(TAG, String.format("[GodModePro] get service %s %s", name, binder));
        return binder;
    }

    /**
     * Register a service factory for deferred (lazy) creation.
     * The service is not created immediately; it will be instantiated via the
     * {@link ServiceFetcher#createService(Context)} callback when the clipboard
     * service is first intercepted by the proxy.
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
            Log.w(TAG, String.format("[GodModePro] register service %s ignored — not system_server", name));
            return;
        }
        Log.d(TAG, String.format("[GodModePro] register service %s %s", name, serviceFetcher));
        SERVICE_FETCHERS.put(name, serviceFetcher);
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
            Log.w(TAG, String.format("[GodModePro] add service %s ignored — not system_server", name));
            return;
        }
        Log.d(TAG, String.format("[GodModePro] add service %s %s", name, service));
        sCache.put(name, service);
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
                Log.w(TAG, "[GodModePro] cannot access delegate service");
                return null;
            }
            Parcel _data = Parcel.obtain();
            Parcel _reply = Parcel.obtain();
            try {
                _data.writeInterfaceToken(DESCRIPTOR);
                _data.writeString(name);
                delegateService.transact(TRANSACTION_getService, _data, _reply, 0);
                _reply.readException();
                return _reply.readStrongBinder();
            } finally {
                _data.recycle();
                _reply.recycle();
            }
        } catch (Exception e) {
            Log.e(TAG, String.format("[GodModePro] get %s service error", name), e instanceof InvocationTargetException ? e.getCause() : e);
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
            Log.e(TAG, String.format("[GodModePro] get %s service error", name), e instanceof InvocationTargetException ? e.getCause() : e);
            return null;
        }
    }

}
