/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Build;
import android.view.View;
import android.view.ViewParent;

import java.lang.ref.WeakReference;
import java.util.WeakHashMap;

import app.hushgram.extension.shared.Utils;

/** The owned navigation listener is wired only by the structurally proved native tab factory. */
public final class NavigationSettings {
    private NavigationSettings() { }
    // Neither side retains a View or Activity. The live View owns its real listener, as before.
    private static final WeakHashMap<View, Binding> nativeBindings = new WeakHashMap<>();

    private static final class Binding {
        final String tab;
        final WeakReference<View.OnLongClickListener> original;
        Binding(String tab, View.OnLongClickListener original) {
            this.tab = tab;
            this.original = new WeakReference<>(original);
        }
    }

    public static View.OnLongClickListener remember(View view, Object tab, View.OnLongClickListener original) {
        if (original instanceof Press) original = ((Press) original).original;
        if (view == null || !(tab instanceof Enum<?>)) return original;
        synchronized (nativeBindings) {
            if (original == null) nativeBindings.remove(view);
            else nativeBindings.put(view, new Binding(((Enum<?>) tab).name(), original));
        }
        // A native null setter is teardown. Only the factory may add an entry to a tab without one.
        return original != null && selected(tab) ? new Press(((Enum<?>) tab).name(), original) : original;
    }

    public static void bind(View view, Object tab) {
        if (view == null || !selected(tab)) return;
        String name = ((Enum<?>) tab).name();
        View.OnLongClickListener original = null;
        synchronized (nativeBindings) {
            Binding binding = nativeBindings.get(view);
            if (binding != null) {
                if (!binding.tab.equals(name)) return;
                original = binding.original.get();
                if (original == null) return;
            }
        }
        view.setOnLongClickListener(new Press(name, original));
    }

    private static boolean selected(Object tab) {
        return Utils.settingsReady() && Settings.NAVIGATION_SETTINGS_TARGET.get().matches(tab);
    }

    private static final class Press implements View.OnLongClickListener {
        private final String tab;
        private final View.OnLongClickListener original;
        Press(String tab, View.OnLongClickListener original) { this.tab = tab; this.original = original; }

        @Override public boolean onLongClick(View view) {
            // Retained or programmatic actions on a removed/disabled button must do nothing.
            Activity owner = ownerOf(view);
            if (owner == null) return false;
            if (Utils.settingsReady() && Settings.NAVIGATION_SETTINGS_TARGET.get() != NavigationTarget.OFF
                    && Settings.NAVIGATION_SETTINGS_TARGET.get().name().equals(tab)
                    && SettingsEntry.requestOpen(owner)) return true;
            return original != null && original.onLongClick(view);
        }

        @Override public boolean onLongClickUseDefaultHapticFeedback(View view) {
            // Before API 34 Android never calls this method. Native handlers keep their choice.
            return (Utils.settingsReady() && Settings.NAVIGATION_SETTINGS_TARGET.get().name().equals(tab))
                    || original == null || Build.VERSION.SDK_INT < 34
                    || original.onLongClickUseDefaultHapticFeedback(view);
        }
    }

    private static Activity ownerOf(View view) {
        if (view == null || !view.isAttachedToWindow() || !view.isShown()) return null;
        Context context = view.getContext();
        for (int depth = 0; depth < 16 && context instanceof ContextWrapper && !(context instanceof Activity); depth++) {
            Context next = ((ContextWrapper) context).getBaseContext();
            if (next == context) break;
            context = next;
        }
        if (!(context instanceof Activity)) return null;
        Activity activity = (Activity) context;
        if (activity.isFinishing() || activity.isDestroyed() || !activity.hasWindowFocus()) return null;
        View decor = activity.getWindow().getDecorView();
        View current = view;
        for (int depth = 0; depth < 64; depth++) {
            if (!current.isEnabled() || current.getVisibility() != View.VISIBLE) return null;
            if (current == decor) return activity;
            ViewParent parent = current.getParent();
            if (!(parent instanceof View)) return null;
            current = (View) parent;
        }
        return null;
    }
}
