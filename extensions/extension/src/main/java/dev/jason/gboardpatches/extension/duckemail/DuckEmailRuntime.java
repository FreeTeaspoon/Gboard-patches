package dev.jason.gboardpatches.extension.duckemail;

import android.content.Context;
import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.os.Looper;
import android.view.inputmethod.InputConnection;
import android.widget.Toast;
import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicBoolean;
import dev.jason.gboardpatches.extension.R;
import dev.jason.gboardpatches.extension.settings.GboardSettingsText;

/** An asynchronous result may edit only the input session that requested it. */
public final class DuckEmailRuntime {
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private static WeakReference<InputMethodService> service = new WeakReference<>(null);
    private static final DuckEmailInputSession SESSION = new DuckEmailInputSession();
    private DuckEmailRuntime() {}

    public static void start(Object receiver) {
        end();
        if (receiver instanceof InputMethodService ime) {
            service = new WeakReference<>(ime);
            WeakReference<InputMethodService> reference = service;
            SESSION.begin(new DuckEmailInputSession.Editor() {
                public InputConnection connection() {
                    InputMethodService current = reference.get();
                    return current == null ? null : current.getCurrentInputConnection();
                }
                public boolean shown() {
                    InputMethodService current = reference.get();
                    return current != null && current.isInputViewShown();
                }
            });
        }
    }

    public static void end() {
        SESSION.end();
        service.clear();
    }

    public static boolean enabled(Context context) {
        try { return context != null && new DuckEmailStore(context).enabled(); }
        catch (Throwable ignored) { return false; }
    }

    public static void generate(Context context) {
        try {
            if (Looper.myLooper() != Looper.getMainLooper() || !enabled(context)) return;
            Context app = context.getApplicationContext();
            DuckEmailStore store = new DuckEmailStore(app);
            if (!store.signedIn()) {
                message(app, R.string.gboard_duck_email_sign_in_first);
                return;
            }
            DuckEmailInputSession.Request request = SESSION.request();
            if (request == null) {
                message(app, R.string.gboard_duck_email_no_editor);
                return;
            }
            if (!BUSY.compareAndSet(false, true)) {
                message(app, R.string.gboard_duck_email_generating);
                return;
            }
            String revision = store.revision();
            Handler main = new Handler(Looper.getMainLooper());
            message(app, R.string.gboard_duck_email_generating);
            DuckEmailTasks.NETWORK.execute(() -> {
                String address = null;
                Exception failure = null;
                try {
                    address = new DuckEmailApi().generate(store.token());
                    if (!store.remember(address, revision)) address = null;
                } catch (Exception exception) { failure = exception; }
                String result = address;
                Exception error = failure;
                main.post(() -> {
                    try {
                        if (error != null) {
                            // A restored encrypted token may require signing in again.
                            String text = error instanceof java.security.GeneralSecurityException
                                    ? GboardSettingsText.get(app, R.string.gboard_duck_email_auth_error)
                                    : DuckEmailTasks.error(app, error);
                            Toast.makeText(app, text, Toast.LENGTH_LONG).show();
                            return;
                        }
                        if (result == null || !revision.equals(store.revision()) || !store.enabled()) return;
                        boolean filled = SESSION.fill(request, result);
                        message(app, filled ? R.string.gboard_duck_email_filled
                                : R.string.gboard_duck_email_saved);
                    } catch (Throwable ignored) {
                        message(app, R.string.gboard_duck_email_saved);
                    } finally { BUSY.set(false); }
                });
            });
        } catch (Throwable ignored) {
            BUSY.set(false);
            message(context, R.string.gboard_duck_email_network_error);
        }
    }

    private static void message(Context context, int resource) {
        try {
            Toast.makeText(context, GboardSettingsText.get(context, resource), Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) { /* Never escape a toolbar callback. */ }
    }
}
