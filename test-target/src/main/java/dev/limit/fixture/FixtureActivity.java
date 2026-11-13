package dev.limit.fixture;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Button;
import android.widget.FrameLayout;
import android.view.Gravity;

/** Separate launchable application used only for real cross-app blocking tests. */
public final class FixtureActivity extends Activity {
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView text = new TextView(this);
        text.setText("LIMIT test target\nThis app should be blocked when its allowance is exhausted.");
        text.setTextSize(24);
        text.setPadding(48, 96, 48, 48);
        FrameLayout layout = new FrameLayout(this);
        layout.addView(text);
        Button tapTarget = new Button(this);
        tapTarget.setText("Tap target");
        tapTarget.setAllCaps(false);
        final int[] taps = {0};
        tapTarget.setOnClickListener(view -> tapTarget.setText("Tapped " + (++taps[0])));
        layout.addView(tapTarget, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                (int) (56 * getResources().getDisplayMetrics().density), Gravity.TOP));
        setContentView(layout);
    }
}
