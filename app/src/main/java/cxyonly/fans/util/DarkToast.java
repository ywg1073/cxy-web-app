package cxyonly.fans.util;

import android.content.Context;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import cxyonly.fans.R;

public class DarkToast {
    public static void show(Context context, String message) {
        if (context == null) return;
        Toast toast = new Toast(context.getApplicationContext());
        View view = LayoutInflater.from(context).inflate(R.layout.toast_dark, null);
        TextView tv = view.findViewById(R.id.toast_text);
        tv.setText(message);
        toast.setView(view);
        toast.setDuration(Toast.LENGTH_SHORT);
        toast.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, 160);
        toast.show();
    }
}
