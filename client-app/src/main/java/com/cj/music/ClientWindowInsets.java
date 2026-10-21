package com.cj.music;

import android.app.Activity;
import android.graphics.Rect;
import android.view.View;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

/** Keeps ordinary screens clear of transparent system bars on Android 15 and later. */
final class ClientWindowInsets {
    private ClientWindowInsets() {}

    static void apply(Activity activity) {
        WindowCompat.setDecorFitsSystemWindows(activity.getWindow(), false);
        View content = activity.findViewById(android.R.id.content);
        if (content == null) return;
        Rect initial = new Rect(content.getPaddingLeft(), content.getPaddingTop(),
                content.getPaddingRight(), content.getPaddingBottom());
        ViewCompat.setOnApplyWindowInsetsListener(content, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(initial.left + bars.left, initial.top + bars.top,
                    initial.right + bars.right, initial.bottom + bars.bottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(content);
    }
}
