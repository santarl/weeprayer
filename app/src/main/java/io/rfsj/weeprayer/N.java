package io.rfsj.weeprayer;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;

/** Compact "next prayer" widget; W does the drawing. */
public class N extends AppWidgetProvider {
    @Override public void onUpdate(Context c, AppWidgetManager am, int[] ids) { W.all(c); }
}
